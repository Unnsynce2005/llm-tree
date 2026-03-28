package com.llmtree.service;

import com.llmtree.dto.Dtos.*;
import com.llmtree.provider.LlmProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmService {

    private final Map<String, LlmProvider> providers;

    public LlmService(List<LlmProvider> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(LlmProvider::name, p -> p));
        log.info("Registered LLM providers: {}", providers.keySet());
        providers.forEach((name, p) ->
            log.info("  {} — available: {}, models: {}", name, p.isAvailable(), p.models()));
    }

    public List<ProviderInfo> listProviders() {
        return providers.values().stream()
                .map(p -> ProviderInfo.builder()
                        .name(p.name())
                        .models(p.models())
                        .available(p.isAvailable())
                        .build())
                .sorted(Comparator.comparing(ProviderInfo::getName))
                .toList();
    }

    public LlmProvider getProvider(String name) {
        LlmProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown provider: " + name
                    + ". Available: " + providers.keySet());
        }
        if (!provider.isAvailable()) {
            throw new IllegalStateException("Provider " + name + " is not available (API key not set)");
        }
        return provider;
    }

    /**
     * Resolve which provider to use.
     * Priority: explicit request > first available provider.
     */
    public LlmProvider resolveProvider(String requestedProvider) {
        if (requestedProvider != null && !requestedProvider.isBlank()) {
            return getProvider(requestedProvider);
        }

        // Default priority: claude > openai > gemini
        for (String name : List.of("claude", "openai", "gemini")) {
            LlmProvider p = providers.get(name);
            if (p != null && p.isAvailable()) {
                return p;
            }
        }

        throw new IllegalStateException("No LLM providers available. Configure at least one API key.");
    }

    /**
     * Stream a completion given a message history and provider choice.
     */
    public Flux<String> stream(List<Map<String, String>> messages, String providerName, String model) {
        LlmProvider provider = resolveProvider(providerName);
        String resolvedModel = model != null ? model : provider.defaultModel();
        log.info("Streaming from {} / {}", provider.name(), resolvedModel);
        return provider.stream(messages, resolvedModel);
    }
}
