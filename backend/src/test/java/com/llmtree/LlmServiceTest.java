package com.llmtree;

import com.llmtree.provider.LlmProvider;
import com.llmtree.service.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class LlmServiceTest {

    private LlmService llmService;
    private FakeProvider fakeProvider;

    @BeforeEach
    void setUp() {
        fakeProvider = new FakeProvider("claude", true);
        var unavailable = new FakeProvider("unavailable", false);
        llmService = new LlmService(List.of(fakeProvider, unavailable));
    }

    @Test
    void listProviders_returnsAll() {
        var providers = llmService.listProviders();

        assertThat(providers).hasSize(2);
        assertThat(providers.stream().map(p -> p.getName()).toList())
                .containsExactlyInAnyOrder("claude", "unavailable");
    }

    @Test
    void resolveProvider_explicit_returnsRequested() {
        var provider = llmService.resolveProvider("claude");

        assertThat(provider.name()).isEqualTo("claude");
    }

    @Test
    void resolveProvider_null_returnsFirstAvailable() {
        var provider = llmService.resolveProvider(null);

        assertThat(provider.name()).isEqualTo("claude");
    }

    @Test
    void resolveProvider_unknownName_throws() {
        assertThatThrownBy(() -> llmService.resolveProvider("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown provider");
    }

    @Test
    void resolveProvider_unavailable_throws() {
        assertThatThrownBy(() -> llmService.resolveProvider("unavailable"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void stream_delegatesToProvider() {
        var messages = List.of(Map.of("role", "user", "content", "Hi"));

        List<String> tokens = llmService.stream(messages, "claude", null)
                .collectList().block();

        assertThat(tokens).containsExactly("Hello", " ", "world");
    }

    // ---- Fake provider for testing ----

    static class FakeProvider implements LlmProvider {
        private final String providerName;
        private final boolean available;

        FakeProvider(String name, boolean available) {
            this.providerName = name;
            this.available = available;
        }

        @Override public String name() { return providerName; }
        @Override public List<String> models() { return List.of("fake-model"); }
        @Override public String defaultModel() { return "fake-model"; }
        @Override public boolean isAvailable() { return available; }

        @Override
        public Flux<String> stream(List<Map<String, String>> messages, String model) {
            return Flux.just("Hello", " ", "world");
        }
    }
}
