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
public class GeminiProvider implements LlmProvider {

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String defaultModel;

    public GeminiProvider(
            @Value("${llm.gemini.api-key:}") String apiKey,
            @Value("${llm.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            @Value("${llm.gemini.default-model:gemini-2.0-flash}") String defaultModel) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    @Override public String name() { return "gemini"; }

    @Override
    public List<String> models() {
        return List.of("gemini-2.0-flash", "gemini-1.5-pro");
    }

    @Override public String defaultModel() { return defaultModel; }

    @Override public boolean isAvailable() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public Flux<String> stream(List<Map<String, String>> messages, String model) {
        if (!isAvailable()) return Flux.error(new IllegalStateException("Gemini API key not configured"));

        String resolvedModel = model != null ? model : defaultModel;

        // Convert to Gemini format: contents[] with role=user/model, parts[{text}]
        List<Map<String, Object>> contents = new ArrayList<>();
        String systemInstruction = null;

        for (var msg : messages) {
            String role = msg.get("role");
            String content = msg.get("content");
            if ("system".equals(role)) {
                systemInstruction = content;
                continue;
            }
            String geminiRole = "assistant".equals(role) ? "model" : "user";
            contents.add(Map.of(
                "role", geminiRole,
                "parts", List.of(Map.of("text", content))
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", contents);
        if (systemInstruction != null) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        }

        return client.post()
                .uri("/v1beta/models/{model}:streamGenerateContent?alt=sse&key={key}",
                     resolvedModel, apiKey)
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank())
                .mapNotNull(this::extractToken)
                .doOnError(e -> log.error("Gemini stream error: {}", e.getMessage()));
    }

    private String extractToken(String data) {
        try {
            String cleaned = data.startsWith("data: ") ? data.substring(6) : data;
            JsonNode json = mapper.readTree(cleaned);
            JsonNode text = json.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text");
            if (!text.isMissingNode() && !text.isNull()) {
                return text.asText();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
