package com.llmtree.service;

import com.llmtree.dto.Dtos.*;
import com.llmtree.entity.Conversation;
import com.llmtree.entity.Node;
import com.llmtree.repository.ConversationRepository;
import com.llmtree.repository.NodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TreeService {

    private final ConversationRepository conversationRepo;
    private final NodeRepository nodeRepo;

    @Transactional
    public ConversationResponse createConversation(CreateConversationRequest req) {
        Conversation conv = Conversation.builder()
                .title(req.getTitle() != null ? req.getTitle() : "New conversation")
                .build();
        conv = conversationRepo.save(conv);

        // If a system prompt is provided, create it as the root node
        if (req.getSystemPrompt() != null && !req.getSystemPrompt().isBlank()) {
            Node systemNode = Node.builder()
                    .conversationId(conv.getId())
                    .parentId(null)
                    .role("system")
                    .content(req.getSystemPrompt())
                    .build();
            nodeRepo.save(systemNode);
        }

        return toConversationResponse(conv);
    }

    public List<ConversationResponse> listConversations() {
        return conversationRepo.findAllByOrderByUpdatedAtDesc().stream()
                .map(this::toConversationResponse)
                .toList();
    }

    public ConversationResponse getConversation(UUID id) {
        Conversation conv = conversationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + id));
        return toConversationResponse(conv);
    }

    /**
     * Get the full tree structure for a conversation.
     * Returns all nodes with their child IDs, plus a list of leaf node IDs.
     */
    public TreeResponse getTree(UUID conversationId) {
        List<Node> allNodes = nodeRepo.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<Node> leaves = nodeRepo.findLeaves(conversationId);

        // Build parent -> children map
        Map<UUID, List<UUID>> childMap = new HashMap<>();
        for (Node n : allNodes) {
            if (n.getParentId() != null) {
                childMap.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n.getId());
            }
        }

        List<NodeResponse> nodeResponses = allNodes.stream()
                .map(n -> toNodeResponse(n, childMap.getOrDefault(n.getId(), List.of())))
                .toList();

        return TreeResponse.builder()
                .conversationId(conversationId)
                .nodes(nodeResponses)
                .leafIds(leaves.stream().map(Node::getId).toList())
                .build();
    }

    /**
     * Get a complete branch: trace from leaf node back to root.
     * Uses recursive CTE — single SQL query, no application-level recursion.
     */
    public BranchResponse getBranch(UUID leafId) {
        List<Node> branch = nodeRepo.findBranch(leafId);
        if (branch.isEmpty()) {
            throw new NoSuchElementException("Node not found: " + leafId);
        }

        // Build child map for this branch
        Map<UUID, List<UUID>> childMap = new HashMap<>();
        for (Node n : branch) {
            if (n.getParentId() != null) {
                childMap.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n.getId());
            }
        }

        return BranchResponse.builder()
                .leafId(leafId)
                .messages(branch.stream()
                        .map(n -> toNodeResponse(n, childMap.getOrDefault(n.getId(), List.of())))
                        .toList())
                .build();
    }

    /**
     * Diff two branches: find their common ancestor and split point.
     */
    public DiffResponse diff(UUID leftLeafId, UUID rightLeafId) {
        BranchResponse left = getBranch(leftLeafId);
        BranchResponse right = getBranch(rightLeafId);

        Node ancestor = nodeRepo.findCommonAncestor(leftLeafId, rightLeafId);
        UUID ancestorId = ancestor != null ? ancestor.getId() : null;

        // Count common prefix length
        int commonLen = 0;
        int minLen = Math.min(left.getMessages().size(), right.getMessages().size());
        for (int i = 0; i < minLen; i++) {
            if (left.getMessages().get(i).getId().equals(right.getMessages().get(i).getId())) {
                commonLen++;
            } else {
                break;
            }
        }

        return DiffResponse.builder()
                .left(left)
                .right(right)
                .commonAncestorId(ancestorId)
                .commonPrefixLength(commonLen)
                .build();
    }

    /**
     * Create a user message node as a child of the given parent.
     * This is used both for normal messages and for forking.
     */
    @Transactional
    public Node createUserNode(UUID parentId, String content, UUID conversationId) {
        // If parentId is null, this is the first message in the conversation
        if (parentId == null) {
            // Check if there's a system prompt root node
            Node root = nodeRepo.findRoot(conversationId);
            if (root != null) {
                parentId = root.getId();
            }
        }

        Node userNode = Node.builder()
                .conversationId(conversationId)
                .parentId(parentId)
                .role("user")
                .content(content)
                .build();
        return nodeRepo.save(userNode);
    }

    /**
     * Create an assistant message node (called after LLM streaming completes).
     */
    @Transactional
    public Node createAssistantNode(UUID parentId, UUID conversationId,
                                     String content, String provider, String model,
                                     Integer tokenCount) {
        Node assistantNode = Node.builder()
                .conversationId(conversationId)
                .parentId(parentId)
                .role("assistant")
                .content(content)
                .provider(provider)
                .model(model)
                .tokenCount(tokenCount)
                .build();
        return nodeRepo.save(assistantNode);
    }

    public List<NodeResponse> getChildren(UUID nodeId) {
        List<Node> children = nodeRepo.findByParentId(nodeId);
        return children.stream()
                .map(n -> toNodeResponse(n, List.of()))
                .toList();
    }

    /**
     * Build the message history for an LLM call.
     * Traces from the given node back to root and formats as role/content pairs.
     */
    public List<Map<String, String>> buildMessageHistory(UUID leafId) {
        List<Node> branch = nodeRepo.findBranch(leafId);
        return branch.stream()
                .map(n -> Map.of("role", n.getRole(), "content", n.getContent()))
                .collect(Collectors.toList());
    }

    // ---- Mapping helpers ----

    private ConversationResponse toConversationResponse(Conversation conv) {
        int nodeCount = conversationRepo.countNodes(conv.getId());
        int branchCount = 0;
        try {
            branchCount = nodeRepo.countBranches(conv.getId());
        } catch (Exception ignored) {}

        return ConversationResponse.builder()
                .id(conv.getId())
                .title(conv.getTitle())
                .createdAt(conv.getCreatedAt())
                .updatedAt(conv.getUpdatedAt())
                .nodeCount(nodeCount)
                .branchCount(Math.max(branchCount, 1))
                .build();
    }

    private NodeResponse toNodeResponse(Node n, List<UUID> childIds) {
        return NodeResponse.builder()
                .id(n.getId())
                .conversationId(n.getConversationId())
                .parentId(n.getParentId())
                .role(n.getRole())
                .content(n.getContent())
                .provider(n.getProvider())
                .model(n.getModel())
                .tokenCount(n.getTokenCount())
                .metadata(n.getMetadata())
                .createdAt(n.getCreatedAt())
                .childIds(childIds)
                .build();
    }
}
