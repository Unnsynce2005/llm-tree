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
public class OpenAiProvider implements LlmProvider {

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String defaultModel;

    public OpenAiProvider(
            @Value("${llm.openai.api-key:}") String apiKey,
            @Value("${llm.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${llm.openai.default-model:gpt-4o}") String defaultModel) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    @Override public String name() { return "openai"; }

    @Override
    public List<String> models() {
        return List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo");
    }

    @Override public String defaultModel() { return defaultModel; }

    @Override public boolean isAvailable() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public Flux<String> stream(List<Map<String, String>> messages, String model) {
        if (!isAvailable()) return Flux.error(new IllegalStateException("OpenAI API key not configured"));

        String resolvedModel = model != null ? model : defaultModel;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolvedModel);
        body.put("stream", true);
        body.put("messages", messages);

        return client.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank() && !"[DONE]".equals(line.trim()))
                .mapNotNull(this::extractToken)
                .doOnError(e -> log.error("OpenAI stream error: {}", e.getMessage()));
    }

    private String extractToken(String data) {
        try {
            String cleaned = data.startsWith("data: ") ? data.substring(6) : data;
            if ("[DONE]".equals(cleaned.trim())) return null;
            JsonNode json = mapper.readTree(cleaned);
            JsonNode delta = json.path("choices").path(0).path("delta").path("content");
            if (!delta.isMissingNode() && !delta.isNull()) {
                return delta.asText();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
