import type { Conversation } from '../types';

interface Props {
  conversations: Conversation[];
  activeId: string | null;
  onSelect: (id: string) => void;
  onNewChat: () => void;
}

export function Sidebar({ conversations, activeId, onSelect, onNewChat }: Props) {
  const formatTime = (iso: string) => {
    const d = new Date(iso);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHr = Math.floor(diffMin / 60);
    if (diffHr < 24) return `${diffHr}h ago`;
    const diffDay = Math.floor(diffHr / 24);
    return `${diffDay}d ago`;
  };

  return (
    <div className="sidebar">
      <div className="sidebar-header">
        <h1>
          LLM Tree <span>v1</span>
        </h1>
      </div>

      <button className="new-chat-btn" onClick={onNewChat}>
        + New conversation
      </button>

      <div className="sidebar-list">
        {conversations.map(conv => (
          <div
            key={conv.id}
            className={`sidebar-item ${activeId === conv.id ? 'active' : ''}`}
            onClick={() => onSelect(conv.id)}
          >
            <div className="sidebar-item-title">{conv.title || 'Untitled'}</div>
            <div className="sidebar-item-meta">
              {conv.nodeCount} nodes · <span className="branch-count">{conv.branchCount} branches</span> · {formatTime(conv.updatedAt)}
            </div>
          </div>
        ))}

        {conversations.length === 0 && (
          <div style={{ padding: '20px 12px', color: 'var(--text-tertiary)', fontSize: 13, textAlign: 'center' }}>
            No conversations yet
          </div>
        )}
      </div>
    </div>
  );
}
