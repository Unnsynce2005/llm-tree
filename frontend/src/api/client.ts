import type { Conversation, TreeResponse, BranchResponse, DiffResponse, ProviderInfo } from '../types';

const BASE = import.meta.env.VITE_API_URL
  ? `${import.meta.env.VITE_API_URL}/api`
  : '/api';

function getOwnerId(): string {
  const KEY = 'llm-tree-owner-id';
  let id = localStorage.getItem(KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(KEY, id);
  }
  return id;
}

export const ownerId = getOwnerId();

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Owner-Id': ownerId,
  };
  const res = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { ...headers, ...(options?.headers as Record<string, string> || {}) },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error || `HTTP ${res.status}`);
  }
  return res.json();
}

export const api = {
  createConversation(title?: string, systemPrompt?: string) {
    return request<Conversation>('/conversations', {
      method: 'POST',
      body: JSON.stringify({ title, systemPrompt }),
    });
  },
  listConversations() {
    return request<Conversation[]>('/conversations');
  },
  getConversation(id: string) {
    return request<Conversation>(`/conversations/${id}`);
  },
  getTree(conversationId: string) {
    return request<TreeResponse>(`/conversations/${conversationId}/tree`);
  },
  getBranch(nodeId: string) {
    return request<BranchResponse>(`/nodes/${nodeId}/branch`);
  },
  getDiff(conversationId: string, leftId: string, rightId: string) {
    return request<DiffResponse>(`/conversations/${conversationId}/diff?left=${leftId}&right=${rightId}`);
  },
  listProviders() {
    return request<ProviderInfo[]>('/providers');
  },
  fork(parentId: string, content: string) {
    return request<unknown>(`/nodes/${parentId}/fork`, {
      method: 'POST',
      body: JSON.stringify({ content }),
    });
  },
};
