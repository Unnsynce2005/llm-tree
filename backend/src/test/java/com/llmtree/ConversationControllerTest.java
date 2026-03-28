package com.llmtree;

import com.llmtree.controller.ConversationController;
import com.llmtree.dto.Dtos.*;
import com.llmtree.service.LlmService;
import com.llmtree.service.TreeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConversationController.class)
class ConversationControllerTest {

    @Autowired private MockMvc mvc;

    @Autowired private TreeService treeService;
    @Autowired private LlmService llmService;

    @TestConfiguration
    static class MockConfig {
        @Bean public TreeService treeService() { return mock(TreeService.class); }
        @Bean public LlmService llmService() { return mock(LlmService.class); }
    }

    @Test
    void health_returnsOk() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void createConversation_returnsCreated() throws Exception {
        UUID id = UUID.randomUUID();
        when(treeService.createConversation(any())).thenReturn(
                ConversationResponse.builder()
                        .id(id).title("Test").createdAt(Instant.now())
                        .updatedAt(Instant.now()).nodeCount(0).branchCount(1).build());

        mvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Test"));
    }

    @Test
    void listConversations_returnsArray() throws Exception {
        when(treeService.listConversations()).thenReturn(List.of());

        mvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getTree_returnsNodes() throws Exception {
        UUID convId = UUID.randomUUID();
        when(treeService.getTree(convId)).thenReturn(
                TreeResponse.builder()
                        .conversationId(convId).nodes(List.of()).leafIds(List.of()).build());

        mvc.perform(get("/api/conversations/{id}/tree", convId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(convId.toString()));
    }

    @Test
    void listProviders_returnsProviders() throws Exception {
        when(llmService.listProviders()).thenReturn(List.of(
                ProviderInfo.builder().name("claude").models(List.of("model-1")).available(true).build()));

        mvc.perform(get("/api/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("claude"));
    }
}
