package com.llmtree;

import com.llmtree.entity.Conversation;
import com.llmtree.entity.Node;
import com.llmtree.repository.ConversationRepository;
import com.llmtree.repository.NodeRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for NodeRepository recursive CTE queries.
 * Requires a running PostgreSQL instance (docker-compose up db).
 *
 * Run with: mvn test -Dspring.profiles.active=test
 * Or skip if no DB: tests are annotated to replace the datasource only
 * when an embedded DB is available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NodeRepositoryTest {

    @Autowired private ConversationRepository conversationRepo;
    @Autowired private NodeRepository nodeRepo;

    private UUID convId;
    private UUID rootId;
    private UUID userMsgId;
    private UUID assistantMsgId;
    private UUID forkUser1Id;
    private UUID forkAssistant1Id;
    private UUID forkUser2Id;
    private UUID forkAssistant2Id;

    /**
     * Build this tree before each test:
     *
     *   system (root)
     *     └─ user: "What is recursion?"
     *         └─ assistant: "Recursion is..."
     *             ├─ user: "Give me an analogy"       (branch A)
     *             │   └─ assistant: "Russian dolls..."
     *             └─ user: "Show me code"             (branch B)
     *                 └─ assistant: "def factorial..."
     */
    @BeforeEach
    void buildTree() {
        Conversation conv = conversationRepo.save(
                Conversation.builder().title("Test conversation").build());
        convId = conv.getId();

        Node root = nodeRepo.save(Node.builder()
                .conversationId(convId).parentId(null)
                .role("system").content("You are a helpful assistant.").build());
        rootId = root.getId();

        Node userMsg = nodeRepo.save(Node.builder()
                .conversationId(convId).parentId(rootId)
                .role("user").content("What is recursion?").build());
        userMsgId = userMsg.getId();

        Node assistantMsg = nodeRepo.save(Node.builder()
                .conversationId(convId).parentId(userMsgId)
                .role("assistant").content("Recursion is when a function calls itself.")
                .provider("claude").model("claude-sonnet-4-20250514").tokenCount(42).build());
        assistantMsgId = assistantMsg.getId();

        // Branch A
        Node forkUser1 = nodeRepo.save(Node.builder()
                .conversationId(convId).parentId(assistantMsgId)
                .role("user").content("Give me a real-world analogy").build());
        forkUser1Id = forkUser1.getId();

        Node forkAssistant1 = nodeRepo.save(Node.builder()
                .conversationId(convId).parentId(forkUser1Id)
                .role("assistant").content("Think of Russian nesting dolls...")
                .provider("claude").model("claude-sonnet-4-20250514").tokenCount(38).build());
        forkAssistant1Id = forkAssistant1.getId();

        // Branch B
        Node forkUser2 = nodeRepo.save(Node.builder()
                .conversationId(convId).parentId(assistantMsgId)
                .role("user").content("Show me a code example").build());
        forkUser2Id = forkUser2.getId();

        Node forkAssistant2 = nodeRepo.save(Node.builder()
                .conversationId(convId).parentId(forkUser2Id)
                .role("assistant").content("def factorial(n): return 1 if n <= 1 else n * factorial(n-1)")
                .provider("openai").model("gpt-4o").tokenCount(55).build());
        forkAssistant2Id = forkAssistant2.getId();
    }

    @AfterEach
    void cleanup() {
        nodeRepo.deleteAllInBatch();
        conversationRepo.deleteAllInBatch();
    }

    // ---- findBranch (recursive CTE: leaf → root) ----

    @Test
    @Order(1)
    void findBranch_branchA_returnsFullPathFromRootToLeaf() {
        List<Node> branch = nodeRepo.findBranch(forkAssistant1Id);

        assertThat(branch).hasSize(5);
        assertThat(branch.get(0).getId()).isEqualTo(rootId);
        assertThat(branch.get(1).getId()).isEqualTo(userMsgId);
        assertThat(branch.get(2).getId()).isEqualTo(assistantMsgId);
        assertThat(branch.get(4).getId()).isEqualTo(forkAssistant1Id);
    }

    @Test
    @Order(2)
    void findBranch_branchB_returnsFullPathFromRootToLeaf() {
        List<Node> branch = nodeRepo.findBranch(forkAssistant2Id);

        assertThat(branch).hasSize(5);
        assertThat(branch.get(0).getRole()).isEqualTo("system");
        assertThat(branch.get(4).getContent()).contains("factorial");
        // Branch B should share the first 3 nodes with branch A
        List<Node> branchA = nodeRepo.findBranch(forkAssistant1Id);
        assertThat(branch.get(0).getId()).isEqualTo(branchA.get(0).getId());
        assertThat(branch.get(1).getId()).isEqualTo(branchA.get(1).getId());
        assertThat(branch.get(2).getId()).isEqualTo(branchA.get(2).getId());
        // But diverge at position 3
        assertThat(branch.get(3).getId()).isNotEqualTo(branchA.get(3).getId()); // fork diverges at user msg // fork diverges at user msg
    }

    @Test
    @Order(3)
    void findBranch_fromMiddleNode_returnsPathToRoot() {
        List<Node> branch = nodeRepo.findBranch(assistantMsgId);

        assertThat(branch).hasSize(3); // system + user + assistant // system + user + assistant
        assertThat(branch.get(0).getId()).isEqualTo(rootId);
        assertThat(branch.get(2).getId()).isEqualTo(assistantMsgId);
    }

    // ---- findSubtree (recursive CTE: node → all descendants) ----

    @Test
    @Order(4)
    void findSubtree_fromRoot_returnsEntireTree() {
        List<Node> subtree = nodeRepo.findSubtree(rootId);

        assertThat(subtree).hasSize(7); // all nodes
    }

    @Test
    @Order(5)
    void findSubtree_fromAssistantMsg_returnsForkAndDescendants() {
        List<Node> subtree = nodeRepo.findSubtree(assistantMsgId);

        // assistantMsg + 2 user forks + 2 assistant responses = 5
        assertThat(subtree).hasSize(5);
        assertThat(subtree.stream().map(Node::getId))
                .contains(assistantMsgId, forkUser1Id, forkAssistant1Id, forkUser2Id, forkAssistant2Id);
    }

    @Test
    @Order(6)
    void findSubtree_fromLeaf_returnsOnlyThatNode() {
        List<Node> subtree = nodeRepo.findSubtree(forkAssistant1Id);

        assertThat(subtree).hasSize(1);
        assertThat(subtree.get(0).getId()).isEqualTo(forkAssistant1Id);
    }

    // ---- findLeaves ----

    @Test
    @Order(7)
    void findLeaves_returnsTwoLeaves() {
        List<Node> leaves = nodeRepo.findLeaves(convId);

        assertThat(leaves).hasSize(2);
        assertThat(leaves.stream().map(Node::getId))
                .containsExactlyInAnyOrder(forkAssistant1Id, forkAssistant2Id);
    }

    // ---- countBranches ----

    @Test
    @Order(8)
    void countBranches_returnsTwo() {
        int count = nodeRepo.countBranches(convId);

        assertThat(count).isEqualTo(2);
    }

    // ---- findCommonAncestor ----

    @Test
    @Order(9)
    void findCommonAncestor_ofTwoForkedBranches_returnsAssistantMsg() {
        Node ancestor = nodeRepo.findCommonAncestor(forkAssistant1Id, forkAssistant2Id);

        assertThat(ancestor).isNotNull();
        assertThat(ancestor.getId()).isEqualTo(assistantMsgId);
    }

    @Test
    @Order(10)
    void findCommonAncestor_ofLeafAndRoot_returnsRoot() {
        Node ancestor = nodeRepo.findCommonAncestor(forkAssistant1Id, rootId);

        assertThat(ancestor).isNotNull();
        assertThat(ancestor.getId()).isEqualTo(rootId);
    }

    // ---- findRoot ----

    @Test
    @Order(11)
    void findRoot_returnsSystemNode() {
        Node root = nodeRepo.findRoot(convId);

        assertThat(root).isNotNull();
        assertThat(root.getRole()).isEqualTo("system");
        assertThat(root.getParentId()).isNull();
    }

    // ---- Edge cases ----

    @Test
    @Order(12)
    void findBranch_nonExistentId_returnsEmpty() {
        List<Node> branch = nodeRepo.findBranch(UUID.randomUUID());

        assertThat(branch).isEmpty();
    }

    @Test
    @Order(13)
    void findLeaves_emptyConversation_returnsEmpty() {
        Conversation empty = conversationRepo.save(
                Conversation.builder().title("Empty").build());

        List<Node> leaves = nodeRepo.findLeaves(empty.getId());
        assertThat(leaves).isEmpty();

        conversationRepo.delete(empty);
    }
}
