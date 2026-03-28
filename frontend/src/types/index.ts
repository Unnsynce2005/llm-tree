export interface Conversation {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  nodeCount: number;
  branchCount: number;
}

export interface TreeNode {
  id: string;
  conversationId: string;
  parentId: string | null;
  role: 'user' | 'assistant' | 'system';
  content: string;
  provider: string | null;
  model: string | null;
  tokenCount: number | null;
  metadata: Record<string, unknown>;
  createdAt: string;
  childIds: string[];
}

export interface TreeResponse {
  conversationId: string;
  nodes: TreeNode[];
  leafIds: string[];
}

export interface BranchResponse {
  leafId: string;
  messages: TreeNode[];
}

export interface DiffResponse {
  left: BranchResponse;
  right: BranchResponse;
  commonAncestorId: string | null;
  commonPrefixLength: number;
}

export interface ProviderInfo {
  name: string;
  models: string[];
  available: boolean;
}

export interface StreamChunk {
  type: 'token' | 'stream_start' | 'stream_end' | 'node_created' | 'error';
  content?: string;
  nodeId?: string;
  role?: string;
  provider?: string;
  model?: string;
  tokenCount?: number;
  message?: string;
}

export interface SendMessagePayload {
  type: 'send_message';
  conversationId: string;
  parentId: string | null;
  content: string;
  provider?: string;
  model?: string;
}
