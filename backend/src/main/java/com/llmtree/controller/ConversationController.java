package com.llmtree.controller;

import com.llmtree.dto.Dtos.*;
import com.llmtree.service.LlmService;
import com.llmtree.service.TreeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConversationController {

    private final TreeService treeService;
    private final LlmService llmService;

    // ---- Health ----

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "llm-tree");
    }

    // ---- Conversations ----

    @PostMapping("/conversations")
    public ResponseEntity<ConversationResponse> createConversation(
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId,
            @RequestBody(required = false) CreateConversationRequest req) {
        if (req == null) req = new CreateConversationRequest();
        return ResponseEntity.ok(treeService.createConversation(req, ownerId));
    }

    @GetMapping("/conversations")
    public List<ConversationResponse> listConversations(
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId) {
        return treeService.listConversations(ownerId);
    }

    @GetMapping("/conversations/{id}")
    public ConversationResponse getConversation(@PathVariable UUID id) {
        return treeService.getConversation(id);
    }

    // ---- Tree ----

    @GetMapping("/conversations/{id}/tree")
    public TreeResponse getTree(@PathVariable UUID id) {
        return treeService.getTree(id);
    }

    // ---- Nodes / Branches ----

    @GetMapping("/nodes/{id}/branch")
    public BranchResponse getBranch(@PathVariable UUID id) {
        return treeService.getBranch(id);
    }

    @GetMapping("/nodes/{id}/children")
    public List<NodeResponse> getChildren(@PathVariable UUID id) {
        return treeService.getChildren(id);
    }

    /**
     * Fork: create a new branch from any existing node.
     * This creates a user message as a new child of the specified node,
     * then triggers LLM streaming via WebSocket (client handles that part).
     * Returns just the new user node — the assistant response comes via WS.
     */
    @PostMapping("/nodes/{parentId}/fork")
    public ResponseEntity<NodeResponse> fork(
            @PathVariable UUID parentId,
            @Valid @RequestBody ForkRequest req) {
        // Look up the parent to get conversationId
        var branch = treeService.getBranch(parentId);
        UUID conversationId = branch.getMessages().get(0).getConversationId();

        var userNode = treeService.createUserNode(parentId, req.getContent(), conversationId);
        return ResponseEntity.ok(NodeResponse.builder()
                .id(userNode.getId())
                .conversationId(userNode.getConversationId())
                .parentId(userNode.getParentId())
                .role(userNode.getRole())
                .content(userNode.getContent())
                .createdAt(userNode.getCreatedAt())
                .childIds(List.of())
                .build());
    }

    // ---- Diff ----

    @GetMapping("/conversations/{id}/diff")
    public DiffResponse diff(
            @PathVariable UUID id,
            @RequestParam UUID left,
            @RequestParam UUID right) {
        return treeService.diff(left, right);
    }

    // ---- Providers ----

    @GetMapping("/providers")
    public List<ProviderInfo> listProviders() {
        return llmService.listProviders();
    }
}
