package com.llmtree.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmtree.entity.Node;
import com.llmtree.service.LlmService;
import com.llmtree.service.TreeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles WebSocket connections for streaming LLM responses.
 *
 * Protocol:
 *   Client sends: { "type": "send_message", "conversationId": "...", "parentId": "...",
 *                    "content": "...", "provider": "claude", "model": "..." }
 *
 *   Server streams back:
 *     { "type": "node_created", "nodeId": "...", "role": "user" }
 *     { "type": "stream_start", "nodeId": "...", "provider": "...", "model": "..." }
 *     { "type": "token", "content": "..." }
 *     { "type": "token", "content": "..." }
 *     ...
 *     { "type": "stream_end", "nodeId": "...", "tokenCount": 123 }
 *
 *   On error:
 *     { "type": "error", "message": "..." }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final TreeService treeService;
    private final LlmService llmService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket disconnected: {} ({})", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode payload = mapper.readTree(message.getPayload());
            String type = payload.path("type").asText();

            if ("send_message".equals(type)) {
                handleSendMessage(session, payload);
            } else {
                sendJson(session, Map.of("type", "error", "message", "Unknown message type: " + type));
            }
        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
            sendJson(session, Map.of("type", "error", "message", e.getMessage()));
        }
    }

    private void handleSendMessage(WebSocketSession session, JsonNode payload) {
        UUID conversationId = UUID.fromString(payload.path("conversationId").asText());
        String parentIdStr = payload.path("parentId").asText(null);
        UUID parentId = (parentIdStr != null && !parentIdStr.isBlank() && !"null".equals(parentIdStr))
                ? UUID.fromString(parentIdStr) : null;
        String content = payload.path("content").asText();
        String provider = payload.path("provider").asText(null);
        String model = payload.path("model").asText(null);

        // 1. Create user node
        Node userNode = treeService.createUserNode(parentId, content, conversationId);
        sendJson(session, Map.of(
                "type", "node_created",
                "nodeId", userNode.getId().toString(),
                "role", "user"
        ));

        // 2. Build message history from the new user node back to root
        List<Map<String, String>> history = treeService.buildMessageHistory(userNode.getId());

        // 3. Resolve provider
        var resolvedProvider = llmService.resolveProvider(provider);
        String resolvedModel = model != null ? model : resolvedProvider.defaultModel();

        sendJson(session, Map.of(
                "type", "stream_start",
                "nodeId", "",
                "provider", resolvedProvider.name(),
                "model", resolvedModel
        ));

        // 4. Stream LLM response
        StringBuilder fullResponse = new StringBuilder();
        llmService.stream(history, provider, model)
                .doOnNext(token -> {
                    fullResponse.append(token);
                    sendJson(session, Map.of("type", "token", "content", token));
                })
                .doOnComplete(() -> {
                    // 5. Save assistant node
                    String responseText = fullResponse.toString();
                    Node assistantNode = treeService.createAssistantNode(
                            userNode.getId(), conversationId,
                            responseText, resolvedProvider.name(), resolvedModel,
                            estimateTokenCount(responseText)
                    );
                    sendJson(session, Map.of(
                            "type", "stream_end",
                            "nodeId", assistantNode.getId().toString(),
                            "tokenCount", estimateTokenCount(responseText)
                    ));
                })
                .doOnError(e -> {
                    log.error("Stream error", e);
                    sendJson(session, Map.of("type", "error", "message", e.getMessage()));
                })
                .subscribe();
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(data)));
            }
        } catch (Exception e) {
            log.error("Failed to send WebSocket message", e);
        }
    }

    /** Rough token estimate: ~4 chars per token for English */
    private int estimateTokenCount(String text) {
        return Math.max(1, text.length() / 4);
    }
}
