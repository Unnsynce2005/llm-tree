import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { TreeNode } from '../types';

interface Props {
  node: TreeNode;
  isStreaming: boolean;
  isForkPoint: boolean;
  forkCount: number;
  onFork: () => void;
}

export function MessageBubble({ node, isStreaming, isForkPoint, forkCount, onFork }: Props) {
  return (
    <div className={`message ${node.role}`}>
      <div className="message-header">
        <span className={`message-role ${node.role}`}>
          {node.role}
        </span>

        {node.provider && (
          <span className="message-provider">
            {node.provider}/{node.model}
          </span>
        )}

        {isStreaming && (
          <span className="streaming-indicator">
            <span className="streaming-dot" />
            <span className="streaming-dot" />
            <span className="streaming-dot" />
          </span>
        )}

        {isForkPoint && (
          <span className="fork-indicator">
            ⑂ {forkCount} branches
          </span>
        )}
      </div>

      <div className="message-content">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>
          {node.content}
        </ReactMarkdown>
      </div>

      {!isStreaming && node.role !== 'system' && (
        <button className="message-fork-btn" onClick={onFork}>
          ⑂ Fork from here
        </button>
      )}
    </div>
  );
}
