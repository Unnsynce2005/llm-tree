import type { Conversation, TreeResponse, BranchResponse, DiffResponse, ProviderInfo } from '../types';

const BASE = import.meta.env.VITE_API_URL
  ? `${import.meta.env.VITE_API_URL}/api`
  : '/api';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
    throw new Error(body.error || `HTTP ${res.status}`);
  }
  return res.json();
}

export const api = {
  // Conversations
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

  // Tree
  getTree(conversationId: string) {
    return request<TreeResponse>(`/conversations/${conversationId}/tree`);
  },

  // Branch
  getBranch(nodeId: string) {
    return request<BranchResponse>(`/nodes/${nodeId}/branch`);
  },

  // Diff
  getDiff(conversationId: string, leftId: string, rightId: string) {
    return request<DiffResponse>(
      `/conversations/${conversationId}/diff?left=${leftId}&right=${rightId}`
    );
  },

  // Providers
  listProviders() {
    return request<ProviderInfo[]>('/providers');
  },

  // Fork
  fork(parentId: string, content: string) {
    return request<unknown>(`/nodes/${parentId}/fork`, {
      method: 'POST',
      body: JSON.stringify({ content }),
    });
  },
};
