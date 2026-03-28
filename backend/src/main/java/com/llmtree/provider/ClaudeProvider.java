package com.llmtree.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;

@Slf4j
@Component
public class ClaudeProvider implements LlmProvider {

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String defaultModel;

    public ClaudeProvider(
            @Value("${llm.anthropic.api-key:}") String apiKey,
            @Value("${llm.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${llm.anthropic.default-model:claude-sonnet-4-20250514}") String defaultModel) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    @Override public String name() { return "claude"; }

    @Override
    public List<String> models() {
        return List.of("claude-sonnet-4-20250514", "claude-haiku-4-5-20251001");
    }

    @Override public String defaultModel() { return defaultModel; }

    @Override public boolean isAvailable() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public Flux<String> stream(List<Map<String, String>> messages, String model) {
        if (!isAvailable()) return Flux.error(new IllegalStateException("Claude API key not configured"));

        String resolvedModel = model != null ? model : defaultModel;

        // Separate system message from conversation messages
        String systemPrompt = null;
        List<Map<String, String>> conversationMessages = new ArrayList<>();
        for (var msg : messages) {
            if ("system".equals(msg.get("role"))) {
                systemPrompt = msg.get("content");
            } else {
                conversationMessages.add(msg);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolvedModel);
        body.put("max_tokens", 4096);
        body.put("stream", true);
        if (systemPrompt != null) {
            body.put("system", systemPrompt);
        }
        body.put("messages", conversationMessages);

        return client.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank())
                .mapNotNull(this::extractToken)
                .doOnError(e -> log.error("Claude stream error: {}", e.getMessage()));
    }

    private String extractToken(String data) {
        try {
            JsonNode json = mapper.readTree(data);
            String type = json.path("type").asText();
            if ("content_block_delta".equals(type)) {
                return json.path("delta").path("text").asText("");
            }
        } catch (Exception ignored) {}
        return null;
    }
}
