# LLM Tree

A tree-structured LLM conversation manager that lets you **branch, fork, and compare** conversations like git branches. Built with a Spring Boot backend (Java 21), PostgreSQL with recursive CTEs, reactive WebSocket streaming, and a React/TypeScript frontend.

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                         React + TypeScript (Vite)                              │
│                                                                                │
│  ┌──────────────┐  ┌──────────────────────┐  ┌─────────────────────────────┐  │
│  │ Tree          │  │ Chat view            │  │ Diff panel                  │  │
│  │ navigator     │  │ (active branch)      │  │ (side-by-side comparison)   │  │
│  │ (git-graph)   │  │                      │  │                             │  │
│  └──────────────┘  └──────────────────────┘  └─────────────────────────────┘  │
│           │                    │                            │                   │
│           └────────── REST ────┴──── WebSocket ─────────────┘                  │
└────────────────────────────────────────┬───────────────────────────────────────┘
                                         │
┌────────────────────────────────────────┴───────────────────────────────────────┐
│                      Spring Boot 3 + Java 21                                   │
│                                                                                │
│  ┌────────────┐  ┌─────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ Controller  │→ │ TreeService  │→ │ Repository   │  │ LLM Provider Layer   │ │
│  │ (REST + WS) │  │ (tree ops)  │  │ (JPA + CTE)  │  │ (Claude/GPT/Gemini)  │ │
│  └────────────┘  └─────────────┘  └──────┬───────┘  └──────────┬───────────┘ │
└──────────────────────────────────────────┼──────────────────────┼─────────────┘
                                           │                      │
                                    ┌──────┴───────┐    ┌────────┴────────┐
                                    │  PostgreSQL   │    │   LLM APIs      │
                                    │  adjacency    │    │   (streaming)   │
                                    │  list + CTE   │    │                 │
                                    └──────────────┘    └─────────────────┘
```

## The Problem

Every LLM interface forces conversations into a single linear thread. You can't:
- Ask the same question to different models and compare answers
- Go back to message #5 and try a different direction without losing messages #6-20
- Split a conversation into parallel explorations when the topic naturally branches

GPT's "edit" feature copies the entire conversation — it doesn't give you a tree. Claude has no branching at all. This tool solves that by treating conversations as what they naturally are: **trees, not lists**.

## Quick Start

```bash
# 1. Clone and set up environment
git clone https://github.com/YOUR_USERNAME/llm-tree.git
cd llm-tree
cp .env.example .env
# Edit .env — add at least one API key

# 2. Start everything with Docker
docker-compose up -d

# 3. Open the UI
open http://localhost:5173
```

### Running without Docker (development)

**Backend:**
```bash
cd backend

# Generate Maven wrapper (one-time)
mvn wrapper:wrapper

# Start PostgreSQL (via Docker or local install)
docker run -d --name llm-tree-db \
  -e POSTGRES_DB=llm_tree \
  -e POSTGRES_USER=llm_tree \
  -e POSTGRES_PASSWORD=llm_tree_dev \
  -p 5432:5432 postgres:16-alpine

# Run the backend
./mvnw spring-boot:run
```

**Frontend:**
```bash
cd frontend
npm install
npm run dev
```

The Vite dev server proxies `/api` and `/ws` to `localhost:8080` automatically.

## Core Concepts

### Conversations are trees

Every message is a **node** with a `parent_id` pointing to the previous message. The first message has `parent_id = NULL` (root). When you **fork** from any message, you create a new child node — this is a new branch.

```
root (system prompt)
 └─ "Explain recursion" (user)
     └─ "Recursion is..." (assistant, Claude)
         ├─ "Now explain it with code" (user)           ← branch A
         │   └─ "Here's a Python example..." (assistant)
         └─ "Explain it to a 5-year-old" (user)         ← branch B
             └─ "Imagine Russian dolls..." (assistant)
```

A **branch** is the path from root to any leaf. The tree navigator shows all branches; clicking one loads that conversation path.

### Why this data model?

Each node stores: `id`, `conversation_id`, `parent_id`, `role`, `content`, `provider`, `model`, `token_count`, `metadata` (JSONB), `created_at`.

This is an **adjacency list** — the same structure git uses for commits. Alternatives considered:

| Model | Pros | Cons | Why rejected |
|---|---|---|---|
| **Adjacency list** (chosen) | Simple inserts, natural for trees, PostgreSQL recursive CTEs handle traversal | Reads require recursion | CTEs solve the read problem elegantly |
| **Nested sets** | Fast subtree reads | Expensive inserts (must renumber) | Conversations grow at the leaves — fast inserts matter more |
| **Materialized path** | Fast ancestor queries | Path strings grow unbounded | Deeply branched conversations would hit practical limits |
| **Closure table** | Fast reads in all directions | O(n) extra rows per insert | Overkill — we almost always read root-to-leaf, which CTEs handle |

### Recursive CTEs — the key query

The single most important query in the project retrieves a complete branch (root to leaf) in one SQL call:

```sql
WITH RECURSIVE branch AS (
    SELECT * FROM node WHERE id = :leafId
    UNION ALL
    SELECT n.* FROM node n
    INNER JOIN branch b ON n.id = b.parent_id
)
SELECT * FROM branch ORDER BY created_at ASC;
```

This traces from any leaf back to root by following `parent_id` links. PostgreSQL executes this as an iterative loop internally — no application-level recursion needed. The same pattern is used for:

- **Subtree retrieval** (all descendants of a node)
- **Common ancestor** (intersecting two ancestor chains for diff)
- **Leaf detection** (nodes with no children)

### Provider abstraction

All LLM providers implement a single Java interface:

```java
public interface LlmProvider {
    String name();
    List<String> models();
    Flux<String> stream(List<Map<String, String>> messages, String model);
}
```

`Flux<String>` is Spring WebFlux's reactive stream — each element is a token chunk. The WebSocket handler subscribes to this Flux and pushes each token to the frontend in real time. This design means:

1. Adding a new provider = implementing one interface (no changes to service or controller layer)
2. Streaming is uniform regardless of provider-specific SSE format differences
3. The reactive pipeline naturally handles backpressure

Provider differences handled internally:
- **Claude**: Separates system prompt from messages, uses `x-api-key` header, parses `content_block_delta` events
- **OpenAI**: Uses `Authorization: Bearer` header, parses `choices[0].delta.content` from SSE
- **Gemini**: Converts role names (`assistant` → `model`), uses URL-based auth, different JSON structure

## API Reference

### REST Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Health check |
| `POST` | `/api/conversations` | Create conversation (optional: `title`, `systemPrompt`) |
| `GET` | `/api/conversations` | List all conversations |
| `GET` | `/api/conversations/{id}` | Get conversation details |
| `GET` | `/api/conversations/{id}/tree` | Full tree structure with all nodes |
| `GET` | `/api/nodes/{id}/branch` | Complete branch (root → leaf) via recursive CTE |
| `GET` | `/api/nodes/{id}/children` | Direct children of a node |
| `POST` | `/api/nodes/{parentId}/fork` | Create a new branch from any node |
| `GET` | `/api/conversations/{id}/diff?left={id}&right={id}` | Compare two branches |
| `GET` | `/api/providers` | List available LLM providers and models |

### WebSocket Protocol

Connect to `/ws/chat`. Send JSON, receive JSON.

**Client → Server:**
```json
{
  "type": "send_message",
  "conversationId": "uuid",
  "parentId": "uuid",
  "content": "Your message",
  "provider": "claude",
  "model": "claude-sonnet-4-20250514"
}
```

**Server → Client (sequence):**
```json
{ "type": "node_created", "nodeId": "uuid", "role": "user" }
{ "type": "stream_start", "provider": "claude", "model": "claude-sonnet-4-20250514" }
{ "type": "token", "content": "Hello" }
{ "type": "token", "content": ", I" }
{ "type": "token", "content": " can help" }
{ "type": "stream_end", "nodeId": "uuid", "tokenCount": 150 }
```

## Project Structure

```
llm-tree/
├── docker-compose.yml
├── .env.example
│
├── backend/                          # Spring Boot 3 + Java 21
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/llmtree/
│       │   ├── LlmTreeApplication.java
│       │   ├── config/
│       │   │   ├── CorsConfig.java
│       │   │   └── WebSocketConfig.java
│       │   ├── entity/
│       │   │   ├── Conversation.java       # JPA entity
│       │   │   └── Node.java               # JPA entity (tree node)
│       │   ├── dto/
│       │   │   └── Dtos.java               # All request/response types
│       │   ├── repository/
│       │   │   ├── ConversationRepository.java
│       │   │   └── NodeRepository.java     # ★ 5 recursive CTE queries
│       │   ├── provider/
│       │   │   ├── LlmProvider.java        # ★ Provider interface
│       │   │   ├── ClaudeProvider.java
│       │   │   ├── OpenAiProvider.java
│       │   │   └── GeminiProvider.java
│       │   ├── service/
│       │   │   ├── TreeService.java        # ★ Core tree operations
│       │   │   └── LlmService.java         # Provider orchestration
│       │   ├── controller/
│       │   │   ├── ConversationController.java
│       │   │   └── GlobalExceptionHandler.java
│       │   └── websocket/
│       │       └── ChatWebSocketHandler.java # ★ Streaming handler
│       └── resources/
│           ├── application.yml
│           └── db/migration/
│               └── V1__init.sql            # Flyway migration
│
└── frontend/                         # React + TypeScript (Vite)
    ├── package.json
    ├── vite.config.ts
    ├── index.html
    └── src/
        ├── main.tsx
        ├── App.tsx                   # State management + orchestration
        ├── types/index.ts            # TypeScript types (mirrors DTOs)
        ├── api/client.ts             # REST API client
        ├── hooks/useWebSocket.ts     # WebSocket hook with auto-reconnect
        ├── styles/globals.css        # Dark theme, developer-tool aesthetic
        └── components/
            ├── Sidebar.tsx           # Conversation list
            ├── TreeNavigator.tsx     # ★ Git-graph-style tree view
            ├── ChatView.tsx          # Message list + input + provider selector
            ├── MessageBubble.tsx     # Single message with fork button
            └── DiffPanel.tsx         # Side-by-side branch comparison
```

## Design Decisions Worth Discussing

### Why PostgreSQL adjacency list + recursive CTEs instead of a graph database?

Graph databases (Neo4j, etc.) are designed for arbitrary graph traversal — "find all friends of friends of friends." Our access patterns are much simpler: we almost always read a single root-to-leaf path. PostgreSQL's recursive CTEs handle this in a single indexed query. Adding a graph database would mean another infrastructure dependency, another query language to learn, and another failure mode — all for access patterns that SQL handles natively.

### Why WebSocket for streaming instead of SSE?

Server-Sent Events (SSE) would work for one-way streaming (server → client). But our protocol is bidirectional: the client sends a message, the server creates a node, starts streaming, and sends completion events. WebSocket handles this naturally. SSE would require a separate POST endpoint for sending messages plus an SSE endpoint for receiving — two connections instead of one.

### Why separate backend and frontend instead of Next.js?

Three reasons: (1) Java/Spring Boot is the target tech stack for the companies I'm applying to — Next.js API routes wouldn't demonstrate Java proficiency. (2) A separate backend exposes every architectural layer (controller → service → repository → provider) for interview discussion. (3) The backend can be consumed by other clients (CLI, mobile) without modification.

### Why `Flux<String>` for the provider interface?

Spring WebFlux's `Flux` is a reactive stream that naturally represents "a sequence of values arriving over time" — exactly what LLM token streaming is. The WebSocket handler subscribes to the Flux and forwards each element. If we used blocking I/O, each streaming connection would hold a thread for the entire response duration. With reactive streams, the thread is released between tokens.

## Interview Preparation

**Q: Walk me through what happens when a user sends a message.**

The frontend sends a JSON payload over WebSocket with the conversation ID, parent node ID, content, and selected provider. The `ChatWebSocketHandler` receives this and: (1) creates a user node in the database with the given parent, (2) calls `TreeService.buildMessageHistory()` which runs a recursive CTE to trace from the new node back to root, assembling the full conversation context, (3) passes that history to `LlmService.stream()` which resolves the provider, calls the provider's `stream()` method, and returns a `Flux<String>`, (4) subscribes to the Flux, forwarding each token over WebSocket, (5) on completion, saves the full response as an assistant node in the database.

**Q: How does forking work?**

Forking is just creating a new child node on an existing node that already has a child. The tree structure handles it naturally — node A might have children B and C, which means two branches pass through A. The frontend detects fork points (nodes with `childIds.length > 1`) and shows a branch indicator. Clicking "Fork from here" on any message creates a new user node as a child of that message's node, which starts a new branch.

**Q: How do you handle different streaming formats across providers?**

Each provider implementation parses its own SSE format internally and emits a uniform `Flux<String>` of plain text tokens. Claude sends `content_block_delta` events, OpenAI sends `choices[0].delta.content`, Gemini sends `candidates[0].content.parts[0].text`. The service layer never sees these differences — it just subscribes to the Flux.

**Q: What's the time complexity of getting a branch?**

O(d) where d is the depth of the tree (length of the branch). The recursive CTE follows `parent_id` links from leaf to root. With the index on `parent_id`, each hop is an indexed lookup. For typical conversations (depth < 100), this is effectively constant time.

**Q: What would you change for production?**

Connection pooling tuning (HikariCP config for concurrent users). Authentication (JWT or session-based — currently there's none). Rate limiting on the LLM proxy. A token budget system to track and limit API spend per user. Tree pruning for abandoned branches. Full-text search across conversations using PostgreSQL `tsvector`. Proper error recovery for interrupted streams (partially written assistant nodes).

## Tech Stack Summary

| Layer | Technology | Why |
|---|---|---|
| Backend | Spring Boot 3, Java 21 | Industry standard for enterprise backend; target stack for fintech roles |
| Database | PostgreSQL 16 | Recursive CTEs for tree traversal; JSONB for flexible metadata |
| Migrations | Flyway | Version-controlled schema changes |
| Streaming | WebFlux `Flux<String>` + WebSocket | Non-blocking reactive streams for LLM token streaming |
| Frontend | React 18, TypeScript, Vite | Type safety, fast dev server, standard SPA tooling |
| Styling | Custom CSS (no framework) | Full control over developer-tool aesthetic |
| Deploy | Docker Compose (dev), Railway (prod) | One-command local setup; managed Postgres in production |

## License

MIT
## Testing

### Run all tests

```bash
# Start the database (required for integration tests)
docker-compose up db -d

# Run backend tests
cd backend
mvn verify -Dspring.profiles.active=test

# Run frontend typecheck
cd ../frontend
npx tsc --noEmit
```

### Test structure

| File | Type | What it tests |
|---|---|---|
| `NodeRepositoryTest` | Integration | All 5 recursive CTE queries against real Postgres — branch traversal, subtree, leaves, common ancestor, root finding. Builds a 7-node tree with 2 branches before each test. |
| `TreeServiceTest` | Unit (mocked) | Tree operations: conversation creation, system prompt injection, tree building with correct childIds, branch retrieval, diff with common prefix calculation, node creation with parent resolution. |
| `LlmServiceTest` | Unit | Provider resolution priority, error cases (unknown/unavailable provider), stream delegation. Uses a fake provider that emits deterministic tokens. |
| `ConversationControllerTest` | Integration (MockMvc) | REST endpoint contracts: health, create, list, getTree, getBranch, listProviders. Verifies HTTP status codes and JSON response shapes. |

### CI

GitHub Actions runs automatically on push/PR to `main`:

1. **Backend**: Spins up a Postgres 16 service container, runs `mvn verify` with JDK 21
2. **Frontend**: Runs TypeScript typecheck (`tsc --noEmit`) and production build

See `.github/workflows/ci.yml`.

## Production Readiness — What Would Change

This section is written for interview discussion. It separates "what I deliberately scoped out" from "what I would do differently in production."

### Authentication & Authorization

Currently there's no auth — any request can access any conversation. In production:

- **Spring Security filter chain** with JWT bearer tokens. Stateless auth, no server-side session.
- Each `conversation` row gets a `user_id` column. All repository queries filter by the authenticated user's ID.
- The WebSocket handshake validates the JWT from a query parameter (WebSocket doesn't support custom headers in the browser API).
- API key storage: user-provided LLM API keys encrypted at rest with AES-256, decrypted in-memory per request. Never logged, never returned in API responses.

### Stream Interruption Recovery

If the WebSocket disconnects mid-stream, the partially accumulated assistant response is lost in the current implementation. Fix:

- **Write-ahead pattern**: Before starting the stream, create the assistant node with `status = 'streaming'` and empty content. Update the content field on every N tokens (batched, not per-token). On stream completion, set `status = 'complete'`.
- **Client reconnect**: On WebSocket reconnect, the client queries for the latest node. If it has `status = 'streaming'`, display the partial content and show a "Resume" or "Regenerate" button.
- **Cleanup job**: A scheduled task (`@Scheduled`) finds nodes with `status = 'streaming'` older than 5 minutes and marks them as `status = 'interrupted'`.

### Rate Limiting & Token Budget

- **Per-user token budget**: A `token_budget` table tracking daily/monthly usage per user. The `LlmService` checks the budget before calling the provider. If exceeded, return 429.
- **Per-provider rate limiting**: Use a token bucket algorithm (Resilience4j `RateLimiter`) per provider to stay within API rate limits. Queue excess requests rather than failing immediately.
- **Cost tracking**: Each assistant node already stores `token_count`. Aggregate this per user per day for billing/monitoring.

### Observability

- **Structured logging**: Replace the current `log.info()` calls with structured fields (conversation_id, node_id, provider, model, latency_ms, token_count). Use Logback with JSON encoder.
- **Metrics**: Micrometer + Prometheus. Key metrics: stream latency p50/p99, tokens per response, error rate by provider, WebSocket connection count, CTE query time.
- **Tracing**: Spring Cloud Sleuth or OpenTelemetry for distributed tracing across the WebSocket handler → service → provider chain.

### Database Scaling

- **Connection pool tuning**: HikariCP `maximum-pool-size` based on `(2 * CPU cores) + effective_spindle_count`. For 4 vCPUs, start with 10 connections.
- **Read replicas**: Branch reads (the most common query) can go to a read replica. Only writes (node creation) need the primary.
- **Partitioning**: If the `node` table grows past ~10M rows, partition by `conversation_id` hash. Each conversation's nodes are always queried together, so partition-local CTEs remain efficient.
- **Full-text search**: Add a `tsvector` column on `node.content` with a GIN index. Enables "search across all my conversations" without external search infrastructure.

### Tree Maintenance

- **Branch pruning**: Abandoned branches (no new messages in 30 days, not bookmarked) could be soft-deleted to reduce tree complexity.
- **Conversation archival**: Move old conversations to cold storage (S3 + metadata in a separate archive table) after 90 days of inactivity.
- **Export**: Allow exporting a branch or full tree as Markdown, JSON, or PDF for sharing outside the tool.
