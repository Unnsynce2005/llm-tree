import { useState, useRef, useEffect, useCallback } from 'react';
import { MessageBubble } from './MessageBubble';
import type { TreeNode, ProviderInfo, TreeResponse } from '../types';

interface Props {
  messages: TreeNode[];
  isStreaming: boolean;
  wsStatus: string;
  onSendMessage: (content: string, forkFromNodeId?: string) => void;
  onFork: (nodeId: string) => void;
  providers: ProviderInfo[];
  selectedProvider: string;
  selectedModel: string;
  onProviderChange: (provider: string) => void;
  onModelChange: (model: string) => void;
  tree: TreeResponse;
}

export function ChatView({
  messages,
  isStreaming,
  wsStatus,
  onSendMessage,
  onFork,
  providers,
  selectedProvider,
  selectedModel,
  onProviderChange,
  onModelChange,
  tree,
}: Props) {
  const [input, setInput] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // Auto-scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isStreaming]);

  // Focus input on load
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const handleSend = useCallback(() => {
    const trimmed = input.trim();
    if (!trimmed || isStreaming) return;
    onSendMessage(trimmed);
    setInput('');
    // Reset textarea height
    if (inputRef.current) {
      inputRef.current.style.height = '44px';
    }
  }, [input, isStreaming, onSendMessage]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value);
    // Auto-resize
    const el = e.target;
    el.style.height = '44px';
    el.style.height = Math.min(el.scrollHeight, 200) + 'px';
  };

  // Find nodes with multiple children (fork points)
  const forkPoints = new Set(
    tree.nodes.filter(n => n.childIds.length > 1).map(n => n.id)
  );

  const availableModels = providers.find(p => p.name === selectedProvider)?.models || [];

  return (
    <div className="chat-area">
      {/* Header */}
      <div className="chat-header">
        <div className="chat-header-title">
          {messages.length > 0
            ? `Branch · ${messages.filter(m => m.role !== 'system').length} messages`
            : 'New branch'}
        </div>
        <div className="chat-header-actions">
          <div className="ws-status">
            <div className={`ws-dot ${wsStatus}`} />
            {wsStatus}
          </div>
        </div>
      </div>

      {/* Messages */}
      <div className="chat-messages">
        {messages.filter(m => m.role !== 'system').map(msg => (
          <MessageBubble
            key={msg.id}
            node={msg}
            isStreaming={msg.id === '__streaming__'}
            isForkPoint={forkPoints.has(msg.id)}
            forkCount={tree.nodes.find(n => n.id === msg.id)?.childIds.length || 0}
            onFork={() => onFork(msg.id)}
          />
        ))}

        {messages.filter(m => m.role !== 'system').length === 0 && !isStreaming && (
          <div className="empty-state">
            <div className="empty-state-title">Start a conversation</div>
            <div className="empty-state-sub">
              Send a message to begin. You can fork any message later to explore different directions.
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div className="chat-input-area">
        <div className="chat-input-row">
          <textarea
            ref={inputRef}
            className="chat-input"
            placeholder={isStreaming ? 'Waiting for response...' : 'Send a message... (Enter to send, Shift+Enter for newline)'}
            value={input}
            onChange={handleInput}
            onKeyDown={handleKeyDown}
            disabled={isStreaming}
            rows={1}
          />
          <button
            className="chat-send-btn"
            onClick={handleSend}
            disabled={isStreaming || !input.trim()}
          >
            {isStreaming ? 'Streaming...' : 'Send'}
          </button>
        </div>

        <div className="input-controls">
          <select
            className="provider-select"
            value={selectedProvider}
            onChange={(e) => onProviderChange(e.target.value)}
          >
            {providers.filter(p => p.available).map(p => (
              <option key={p.name} value={p.name}>{p.name}</option>
            ))}
          </select>

          <select
            className="provider-select"
            value={selectedModel}
            onChange={(e) => onModelChange(e.target.value)}
          >
            {availableModels.map(m => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </div>
      </div>
    </div>
  );
}
