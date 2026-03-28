package com.llmtree.provider;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Unified interface for all LLM providers.
 *
 * Each provider (Claude, OpenAI, Gemini) implements this interface,
 * handling the differences in API format, auth, and streaming protocol.
 * The service layer only interacts with this interface — never with
 * provider-specific code.
 *
 * Design note: Flux<String> for streaming is deliberate.
 * Each element is a token chunk from the LLM. The reactive stream
 * maps naturally to WebSocket frames pushed to the frontend.
 */
public interface LlmProvider {

    /** Provider identifier: "claude", "openai", "gemini" */
    String name();

    /** Available models for this provider */
    List<String> models();

    /** Default model to use when none specified */
    String defaultModel();

    /** Whether this provider has a valid API key configured */
    boolean isAvailable();

    /**
     * Stream a completion from the LLM.
     *
     * @param messages Conversation history as role/content pairs
     * @param model    Which model to use (null = default)
     * @return Flux of token strings, completing when the response is done
     */
    Flux<String> stream(List<Map<String, String>> messages, String model);
}
