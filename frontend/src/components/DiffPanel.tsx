import type { DiffResponse } from '../types';

interface Props {
  data: DiffResponse;
  onClose: () => void;
}

export function DiffPanel({ data, onClose }: Props) {
  const { left, right, commonPrefixLength } = data;

  const renderMessages = (messages: typeof left.messages, side: 'left' | 'right') => {
    return messages
      .filter(m => m.role !== 'system')
      .map((msg, i) => {
        // Determine if this message is in the common prefix
        const isCommon = i < commonPrefixLength;
        const className = isCommon
          ? 'diff-msg common'
          : side === 'left'
          ? 'diff-msg unique-left'
          : 'diff-msg unique-right';

        return (
          <div key={msg.id} className={className}>
            <div style={{
              fontSize: 10,
              fontFamily: 'var(--font-mono)',
              color: 'var(--text-tertiary)',
              marginBottom: 4,
              textTransform: 'uppercase' as const,
              letterSpacing: '0.5px',
            }}>
              {msg.role}
              {msg.provider && ` · ${msg.provider}`}
            </div>
            <div style={{ fontSize: 13, lineHeight: 1.5 }}>
              {msg.content.length > 300
                ? msg.content.slice(0, 300) + '…'
                : msg.content}
            </div>
          </div>
        );
      });
  };

  return (
    <div className="diff-overlay" onClick={onClose}>
      <div className="diff-panel" onClick={(e) => e.stopPropagation()}>
        <div className="diff-header">
          <h2>Branch comparison</h2>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{
              fontSize: 11,
              fontFamily: 'var(--font-mono)',
              color: 'var(--text-tertiary)',
            }}>
              {commonPrefixLength} shared · {left.messages.length - commonPrefixLength} left · {right.messages.length - commonPrefixLength} right
            </span>
            <button className="diff-close" onClick={onClose}>✕</button>
          </div>
        </div>

        <div className="diff-body">
          <div className="diff-column">
            <div className="diff-column-header">
              Left branch · {left.messages.filter(m => m.role !== 'system').length} messages
            </div>
            {renderMessages(left.messages, 'left')}
          </div>

          <div className="diff-column">
            <div className="diff-column-header">
              Right branch · {right.messages.filter(m => m.role !== 'system').length} messages
            </div>
            {renderMessages(right.messages, 'right')}
          </div>
        </div>
      </div>
    </div>
  );
}
