package com.llmtree;

import com.llmtree.dto.Dtos.*;
import com.llmtree.entity.Conversation;
import com.llmtree.entity.Node;
import com.llmtree.repository.ConversationRepository;
import com.llmtree.repository.NodeRepository;
import com.llmtree.service.TreeService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TreeServiceTest {

    @Mock private ConversationRepository conversationRepo;
    @Mock private NodeRepository nodeRepo;
    @InjectMocks private TreeService treeService;

    private UUID convId;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        convId = UUID.randomUUID();
        conversation = Conversation.builder()
                .id(convId).title("Test").createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    // ---- createConversation ----

    @Test
    void createConversation_withTitle_savesAndReturns() {
        when(conversationRepo.save(any())).thenReturn(conversation);
        when(conversationRepo.countNodes(convId)).thenReturn(0);
        when(nodeRepo.countBranches(convId)).thenReturn(0);

        var req = new CreateConversationRequest();
        req.setTitle("My chat");

        ConversationResponse resp = treeService.createConversation(req, null);

        assertThat(resp.getId()).isEqualTo(convId);
        assertThat(resp.getTitle()).isEqualTo("Test");
        verify(conversationRepo).save(any());
    }

    @Test
    void createConversation_withSystemPrompt_createsRootNode() {
        when(conversationRepo.save(any())).thenReturn(conversation);
        when(conversationRepo.countNodes(convId)).thenReturn(1);
        when(nodeRepo.countBranches(convId)).thenReturn(1);

        var req = new CreateConversationRequest();
        req.setTitle("Chat");
        req.setSystemPrompt("You are helpful.");

        treeService.createConversation(req, null);

        // Should save both the conversation and a system node
        verify(conversationRepo).save(any());
        verify(nodeRepo).save(argThat(node ->
                "system".equals(node.getRole()) && "You are helpful.".equals(node.getContent())));
    }

    // ---- getTree ----

    @Test
    void getTree_returnsAllNodesWithLeafIds() {
        UUID rootId = UUID.randomUUID();
        UUID leafId = UUID.randomUUID();

        Node root = Node.builder().id(rootId).conversationId(convId)
                .parentId(null).role("user").content("Hello").createdAt(Instant.now()).build();
        Node leaf = Node.builder().id(leafId).conversationId(convId)
                .parentId(rootId).role("assistant").content("Hi there").createdAt(Instant.now()).build();

        when(nodeRepo.findByConversationIdOrderByCreatedAtAsc(convId)).thenReturn(List.of(root, leaf));
        when(nodeRepo.findLeaves(convId)).thenReturn(List.of(leaf));

        TreeResponse tree = treeService.getTree(convId);

        assertThat(tree.getNodes()).hasSize(2);
        assertThat(tree.getLeafIds()).containsExactly(leafId);
        assertThat(tree.getConversationId()).isEqualTo(convId);
    }

    @Test
    void getTree_setsChildIds() {
        UUID rootId = UUID.randomUUID();
        UUID child1Id = UUID.randomUUID();
        UUID child2Id = UUID.randomUUID();

        Node root = Node.builder().id(rootId).conversationId(convId)
                .parentId(null).role("user").content("Q").createdAt(Instant.now()).build();
        Node child1 = Node.builder().id(child1Id).conversationId(convId)
                .parentId(rootId).role("assistant").content("A1").createdAt(Instant.now()).build();
        Node child2 = Node.builder().id(child2Id).conversationId(convId)
                .parentId(rootId).role("assistant").content("A2").createdAt(Instant.now()).build();

        when(nodeRepo.findByConversationIdOrderByCreatedAtAsc(convId))
                .thenReturn(List.of(root, child1, child2));
        when(nodeRepo.findLeaves(convId)).thenReturn(List.of(child1, child2));

        TreeResponse tree = treeService.getTree(convId);

        NodeResponse rootResp = tree.getNodes().stream()
                .filter(n -> n.getId().equals(rootId)).findFirst().orElseThrow();

        assertThat(rootResp.getChildIds()).containsExactlyInAnyOrder(child1Id, child2Id);
    }

    // ---- getBranch ----

    @Test
    void getBranch_returnsOrderedMessages() {
        UUID rootId = UUID.randomUUID();
        UUID leafId = UUID.randomUUID();

        Node root = Node.builder().id(rootId).conversationId(convId)
                .parentId(null).role("user").content("Start").createdAt(Instant.now()).build();
        Node leaf = Node.builder().id(leafId).conversationId(convId)
                .parentId(rootId).role("assistant").content("End").createdAt(Instant.now()).build();

        when(nodeRepo.findBranch(leafId)).thenReturn(List.of(root, leaf));

        BranchResponse branch = treeService.getBranch(leafId);

        assertThat(branch.getLeafId()).isEqualTo(leafId);
        assertThat(branch.getMessages()).hasSize(2);
        assertThat(branch.getMessages().get(0).getContent()).isEqualTo("Start");
        assertThat(branch.getMessages().get(1).getContent()).isEqualTo("End");
    }

    @Test
    void getBranch_nonExistentNode_throwsNoSuchElement() {
        UUID fakeId = UUID.randomUUID();
        when(nodeRepo.findBranch(fakeId)).thenReturn(List.of());

        assertThatThrownBy(() -> treeService.getBranch(fakeId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(fakeId.toString());
    }

    // ---- diff ----

    @Test
    void diff_computesCommonPrefixLength() {
        UUID sharedId = UUID.randomUUID();
        UUID leftLeafId = UUID.randomUUID();
        UUID rightLeafId = UUID.randomUUID();

        Node shared = Node.builder().id(sharedId).conversationId(convId)
                .parentId(null).role("user").content("Shared").createdAt(Instant.now()).build();
        Node leftLeaf = Node.builder().id(leftLeafId).conversationId(convId)
                .parentId(sharedId).role("assistant").content("Left path").createdAt(Instant.now()).build();
        Node rightLeaf = Node.builder().id(rightLeafId).conversationId(convId)
                .parentId(sharedId).role("assistant").content("Right path").createdAt(Instant.now()).build();

        when(nodeRepo.findBranch(leftLeafId)).thenReturn(List.of(shared, leftLeaf));
        when(nodeRepo.findBranch(rightLeafId)).thenReturn(List.of(shared, rightLeaf));
        when(nodeRepo.findCommonAncestor(leftLeafId, rightLeafId)).thenReturn(shared);

        DiffResponse diff = treeService.diff(leftLeafId, rightLeafId);

        assertThat(diff.getCommonPrefixLength()).isEqualTo(1); // shared node
        assertThat(diff.getCommonAncestorId()).isEqualTo(sharedId);
        assertThat(diff.getLeft().getMessages()).hasSize(2);
        assertThat(diff.getRight().getMessages()).hasSize(2);
    }

    // ---- createUserNode ----

    @Test
    void createUserNode_withParent_setsParentId() {
        UUID parentId = UUID.randomUUID();
        Node saved = Node.builder().id(UUID.randomUUID()).conversationId(convId)
                .parentId(parentId).role("user").content("Hello").createdAt(Instant.now()).build();

        when(nodeRepo.save(any())).thenReturn(saved);

        Node result = treeService.createUserNode(parentId, "Hello", convId);

        assertThat(result.getParentId()).isEqualTo(parentId);
        assertThat(result.getRole()).isEqualTo("user");
    }

    @Test
    void createUserNode_nullParent_attachesToRoot() {
        UUID rootId = UUID.randomUUID();
        Node root = Node.builder().id(rootId).conversationId(convId)
                .parentId(null).role("system").content("sys").createdAt(Instant.now()).build();

        when(nodeRepo.findRoot(convId)).thenReturn(root);
        when(nodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Node result = treeService.createUserNode(null, "Hello", convId);

        assertThat(result.getParentId()).isEqualTo(rootId);
    }

    // ---- createAssistantNode ----

    @Test
    void createAssistantNode_setsProviderAndModel() {
        UUID parentId = UUID.randomUUID();
        when(nodeRepo.save(any())).thenAnswer(inv -> {
            Node n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });

        Node result = treeService.createAssistantNode(
                parentId, convId, "Response text", "claude", "claude-sonnet-4-20250514", 100);

        assertThat(result.getProvider()).isEqualTo("claude");
        assertThat(result.getModel()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(result.getTokenCount()).isEqualTo(100);
        assertThat(result.getRole()).isEqualTo("assistant");
    }

    // ---- buildMessageHistory ----

    @Test
    void buildMessageHistory_returnsRoleContentPairs() {
        UUID leafId = UUID.randomUUID();

        Node sys = Node.builder().id(UUID.randomUUID()).conversationId(convId)
                .parentId(null).role("system").content("Be helpful").createdAt(Instant.now()).build();
        Node user = Node.builder().id(UUID.randomUUID()).conversationId(convId)
                .parentId(sys.getId()).role("user").content("Hi").createdAt(Instant.now()).build();
        Node asst = Node.builder().id(leafId).conversationId(convId)
                .parentId(user.getId()).role("assistant").content("Hello!").createdAt(Instant.now()).build();

        when(nodeRepo.findBranch(leafId)).thenReturn(List.of(sys, user, asst));

        List<Map<String, String>> history = treeService.buildMessageHistory(leafId);

        assertThat(history).hasSize(3);
        assertThat(history.get(0)).containsEntry("role", "system").containsEntry("content", "Be helpful");
        assertThat(history.get(1)).containsEntry("role", "user").containsEntry("content", "Hi");
        assertThat(history.get(2)).containsEntry("role", "assistant").containsEntry("content", "Hello!");
    }
}
