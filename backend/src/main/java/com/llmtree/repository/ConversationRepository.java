package com.llmtree.repository;

import com.llmtree.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findAllByOrderByUpdatedAtDesc();

    @Query("SELECT COUNT(n) FROM Node n WHERE n.conversationId = :conversationId")
    int countNodes(UUID conversationId);
}
