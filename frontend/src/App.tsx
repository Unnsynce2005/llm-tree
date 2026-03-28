import { useState, useEffect, useCallback, useRef } from 'react';
import { api } from './api/client';
import { useWebSocket } from './hooks/useWebSocket';
import { Sidebar } from './components/Sidebar';
import { TreeNavigator } from './components/TreeNavigator';
import { ChatView } from './components/ChatView';
import { DiffPanel } from './components/DiffPanel';
import type { Conversation, TreeResponse, TreeNode, StreamChunk, ProviderInfo, DiffResponse } from './types';

export default function App() {
  // ---- Core state ----
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeConvId, setActiveConvId] = useState<string | null>(null);
  const [tree, setTree] = useState<TreeResponse | null>(null);
  const [activeLeafId, setActiveLeafId] = useState<string | null>(null);
  const [branchMessages, setBranchMessages] = useState<TreeNode[]>([]);

  // ---- Streaming state ----
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingContent, setStreamingContent] = useState('');
  const [streamingProvider, setStreamingProvider] = useState('');
  const [streamingModel, setStreamingModel] = useState('');

  // ---- Provider state ----
  const [providers, setProviders] = useState<ProviderInfo[]>([]);
  const [selectedProvider, setSelectedProvider] = useState('');
  const [selectedModel, setSelectedModel] = useState('');

  // ---- Diff state ----
  const [diffData, setDiffData] = useState<DiffResponse | null>(null);
  const [diffOpen, setDiffOpen] = useState(false);

  // ---- Refs for streaming callbacks ----
  const activeConvIdRef = useRef(activeConvId);
  activeConvIdRef.current = activeConvId;

  // ---- WebSocket ----
  const handleChunk = useCallback((chunk: StreamChunk) => {
    switch (chunk.type) {
      case 'node_created':
        // User node was created — refresh tree
        if (activeConvIdRef.current) {
          api.getTree(activeConvIdRef.current).then(setTree);
        }
        break;

      case 'stream_start':
        setIsStreaming(true);
        setStreamingContent('');
        setStreamingProvider(chunk.provider || '');
        setStreamingModel(chunk.model || '');
        break;

      case 'token':
        setStreamingContent(prev => prev + (chunk.content || ''));
        break;

      case 'stream_end':
        setIsStreaming(false);
        // Refresh tree and branch to get the new assistant node
        if (activeConvIdRef.current) {
          api.getTree(activeConvIdRef.current).then(newTree => {
            setTree(newTree);
            // Navigate to the new leaf
            if (chunk.nodeId) {
              setActiveLeafId(chunk.nodeId);
              api.getBranch(chunk.nodeId).then(b => setBranchMessages(b.messages));
            }
          });
        }
        setStreamingContent('');
        break;

      case 'error':
        setIsStreaming(false);
        setStreamingContent('');
        console.error('Stream error:', chunk.message);
        break;
    }
  }, []);

  const { status: wsStatus, sendMessage } = useWebSocket(handleChunk);

  // ---- Load initial data ----
  useEffect(() => {
    api.listConversations().then(setConversations).catch(console.error);
    api.listProviders().then(p => {
      setProviders(p);
      const available = p.find(x => x.available);
      if (available) {
        setSelectedProvider(available.name);
        setSelectedModel(available.models[0] || '');
      }
    }).catch(console.error);
  }, []);

  // ---- Load tree when conversation changes ----
  useEffect(() => {
    if (!activeConvId) {
      setTree(null);
      setBranchMessages([]);
      setActiveLeafId(null);
      return;
    }
    api.getTree(activeConvId).then(t => {
      setTree(t);
      // Auto-select the most recent leaf
      if (t.leafIds.length > 0) {
        const leafId = t.leafIds[0];
        setActiveLeafId(leafId);
        api.getBranch(leafId).then(b => setBranchMessages(b.messages));
      } else {
        setActiveLeafId(null);
        setBranchMessages([]);
      }
    }).catch(console.error);
  }, [activeConvId]);

  // ---- Handlers ----

  const handleNewConversation = useCallback(async () => {
    const conv = await api.createConversation('New conversation');
    setConversations(prev => [conv, ...prev]);
    setActiveConvId(conv.id);
  }, []);

  const handleSelectConversation = useCallback((id: string) => {
    setActiveConvId(id);
  }, []);

  const handleSelectNode = useCallback(async (nodeId: string) => {
    // Find if this node is a leaf, or find the nearest leaf descendant
    if (!tree) return;
    const node = tree.nodes.find(n => n.id === nodeId);
    if (!node) return;

    // If it's a leaf, navigate to it
    if (tree.leafIds.includes(nodeId)) {
      setActiveLeafId(nodeId);
      const branch = await api.getBranch(nodeId);
      setBranchMessages(branch.messages);
      return;
    }

    // Otherwise, find a leaf that descends from this node
    // For now, just show the branch up to this node
    const branch = await api.getBranch(nodeId);
    setBranchMessages(branch.messages);
    setActiveLeafId(nodeId);
  }, [tree]);

  const handleSendMessage = useCallback((content: string, forkFromNodeId?: string) => {
    if (!activeConvId || isStreaming) return;

    // Determine parent: if forking, use the specified node; otherwise use current leaf
    const parentId = forkFromNodeId || activeLeafId;

    sendMessage({
      type: 'send_message',
      conversationId: activeConvId,
      parentId: parentId,
      content,
      provider: selectedProvider || undefined,
      model: selectedModel || undefined,
    });
  }, [activeConvId, activeLeafId, isStreaming, selectedProvider, selectedModel, sendMessage]);

  const handleDiff = useCallback(async (leftLeafId: string, rightLeafId: string) => {
    if (!activeConvId) return;
    const diff = await api.getDiff(activeConvId, leftLeafId, rightLeafId);
    setDiffData(diff);
    setDiffOpen(true);
  }, [activeConvId]);

  const handleProviderChange = useCallback((provider: string) => {
    setSelectedProvider(provider);
    const p = providers.find(x => x.name === provider);
    if (p && p.models.length > 0) {
      setSelectedModel(p.models[0]);
    }
  }, [providers]);

  // ---- Build the streaming message for display ----
  const displayMessages = [...branchMessages];
  if (isStreaming && streamingContent) {
    displayMessages.push({
      id: '__streaming__',
      conversationId: activeConvId || '',
      parentId: activeLeafId,
      role: 'assistant',
      content: streamingContent,
      provider: streamingProvider,
      model: streamingModel,
      tokenCount: null,
      metadata: {},
      createdAt: new Date().toISOString(),
      childIds: [],
    });
  }

  return (
    <div className="app-layout">
      <Sidebar
        conversations={conversations}
        activeId={activeConvId}
        onSelect={handleSelectConversation}
        onNewChat={handleNewConversation}
      />

      {activeConvId && tree ? (
        <div className="main-content">
          <TreeNavigator
            tree={tree}
            activeLeafId={activeLeafId}
            onSelectNode={handleSelectNode}
            onDiff={handleDiff}
          />
          <ChatView
            messages={displayMessages}
            isStreaming={isStreaming}
            wsStatus={wsStatus}
            onSendMessage={handleSendMessage}
            onFork={(nodeId) => {
              const content = prompt('Enter message for new branch:');
              if (content) handleSendMessage(content, nodeId);
            }}
            providers={providers}
            selectedProvider={selectedProvider}
            selectedModel={selectedModel}
            onProviderChange={handleProviderChange}
            onModelChange={setSelectedModel}
            tree={tree}
          />
        </div>
      ) : (
        <div className="main-content">
          <div className="empty-state">
            <div className="empty-state-icon">⌥</div>
            <div className="empty-state-title">LLM Tree</div>
            <div className="empty-state-sub">
              Branch, fork, and compare LLM conversations like git branches.
              Create a new conversation to start.
            </div>
          </div>
        </div>
      )}

      {diffOpen && diffData && (
        <DiffPanel data={diffData} onClose={() => setDiffOpen(false)} />
      )}
    </div>
  );
}
