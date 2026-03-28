package com.llmtree.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Dtos {

    // ---- Requests ----

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class CreateConversationRequest {
        private String title;
        private String systemPrompt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class SendMessageRequest {
        @NotBlank private String content;
        private String provider;   // "claude", "openai", "gemini"
        private String model;      // specific model override
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ForkRequest {
        @NotBlank private String content;
        private String provider;
        private String model;
    }

    // ---- Responses ----

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ConversationResponse {
        private UUID id;
        private String title;
        private Instant createdAt;
        private Instant updatedAt;
        private int nodeCount;
        private int branchCount;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NodeResponse {
        private UUID id;
        private UUID conversationId;
        private UUID parentId;
        private String role;
        private String content;
        private String provider;
        private String model;
        private Integer tokenCount;
        private Map<String, Object> metadata;
        private Instant createdAt;
        private List<UUID> childIds;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TreeResponse {
        private UUID conversationId;
        private List<NodeResponse> nodes;
        private List<UUID> leafIds;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BranchResponse {
        private UUID leafId;
        private List<NodeResponse> messages;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DiffResponse {
        private BranchResponse left;
        private BranchResponse right;
        private UUID commonAncestorId;
        private int commonPrefixLength;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ProviderInfo {
        private String name;
        private List<String> models;
        private boolean available;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StreamChunk {
        private String type;      // "token", "done", "error", "node_created"
        private String content;
        private UUID nodeId;
        private String provider;
        private String model;
    }
}
