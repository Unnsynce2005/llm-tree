package com.llmtree.repository;

import com.llmtree.entity.Node;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NodeRepository extends JpaRepository<Node, UUID> {

    List<Node> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    List<Node> findByParentId(UUID parentId);

    /**
     * Recursive CTE: trace from a leaf node back to root.
     * Returns the full branch (conversation path) in chronological order.
     * This is the single most important query in the project.
     */
    @Query(value = """
        WITH RECURSIVE branch AS (
            SELECT n.* FROM node n WHERE n.id = :leafId
            UNION ALL
            SELECT n.* FROM node n
            INNER JOIN branch b ON n.id = b.parent_id
        )
        SELECT * FROM branch ORDER BY created_at ASC
        """, nativeQuery = true)
    List<Node> findBranch(@Param("leafId") UUID leafId);

    /**
     * Recursive CTE: find all descendants of a given node.
     * Used for rendering subtrees.
     */
    @Query(value = """
        WITH RECURSIVE subtree AS (
            SELECT n.* FROM node n WHERE n.id = :nodeId
            UNION ALL
            SELECT n.* FROM node n
            INNER JOIN subtree s ON n.parent_id = s.id
        )
        SELECT * FROM subtree ORDER BY created_at ASC
        """, nativeQuery = true)
    List<Node> findSubtree(@Param("nodeId") UUID nodeId);

    /**
     * Find all leaf nodes (nodes with no children) in a conversation.
     * Each leaf represents the tip of a branch.
     */
    @Query(value = """
        SELECT n.* FROM node n
        WHERE n.conversation_id = :conversationId
        AND NOT EXISTS (
            SELECT 1 FROM node child WHERE child.parent_id = n.id
        )
        ORDER BY n.created_at DESC
        """, nativeQuery = true)
    List<Node> findLeaves(@Param("conversationId") UUID conversationId);

    /**
     * Count distinct branches (leaves) in a conversation.
     */
    @Query(value = """
        SELECT COUNT(*) FROM node n
        WHERE n.conversation_id = :conversationId
        AND NOT EXISTS (
            SELECT 1 FROM node child WHERE child.parent_id = n.id
        )
        """, nativeQuery = true)
    int countBranches(@Param("conversationId") UUID conversationId);

    /**
     * Find the common ancestor of two nodes.
     * Intersects the two ancestor chains.
     */
    @Query(value = """
        WITH RECURSIVE ancestors_left AS (
            SELECT n.*, 0 AS depth FROM node n WHERE n.id = :leftId
            UNION ALL
            SELECT n.*, al.depth + 1 FROM node n
            INNER JOIN ancestors_left al ON n.id = al.parent_id
        ),
        ancestors_right AS (
            SELECT n.*, 0 AS depth FROM node n WHERE n.id = :rightId
            UNION ALL
            SELECT n.*, ar.depth + 1 FROM node n
            INNER JOIN ancestors_right ar ON n.id = ar.parent_id
        )
        SELECT al.id, al.conversation_id, al.parent_id, al.role,
               al.content, al.provider, al.model, al.token_count,
               al.metadata, al.created_at
        FROM ancestors_left al
        INNER JOIN ancestors_right ar ON al.id = ar.id
        ORDER BY al.depth ASC
        LIMIT 1
        """, nativeQuery = true)
    Node findCommonAncestor(@Param("leftId") UUID leftId, @Param("rightId") UUID rightId);

    @Query("SELECT n FROM Node n WHERE n.conversationId = :conversationId AND n.parentId IS NULL")
    Node findRoot(@Param("conversationId") UUID conversationId);
}
