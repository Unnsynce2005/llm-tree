import { useState, useMemo, useRef, useCallback, useEffect } from 'react';
import type { TreeResponse, TreeNode } from '../types';

interface Props {
  tree: TreeResponse;
  activeLeafId: string | null;
  onSelectNode: (nodeId: string) => void;
  onDiff: (leftId: string, rightId: string) => void;
}

interface LayoutNode {
  node: TreeNode;
  x: number;
  y: number;
  depth: number;
  isLeaf: boolean;
  hasFork: boolean;
}

const NODE_H = 72;
const NODE_W = 210;
const H_GAP = 24;
const V_GAP = 20;
const LEFT_PAD = 28;
const TOP_PAD = 24;
const MIN_PANEL_W = 180;
const MAX_PANEL_W = 900;
const MIN_ZOOM = 0.3;
const MAX_ZOOM = 2.0;

export function TreeNavigator({ tree, activeLeafId, onSelectNode, onDiff }: Props) {
  const [diffMode, setDiffMode] = useState(false);
  const [diffLeft, setDiffLeft] = useState<string | null>(null);
  const [panelWidth, setPanelWidth] = useState(320);
  const isResizing = useRef(false);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const isPanning = useRef(false);
  const panStart = useRef({ x: 0, y: 0, panX: 0, panY: 0 });
  const containerRef = useRef<HTMLDivElement>(null);

  const nodeMap = useMemo(() => {
    const m = new Map<string, TreeNode>();
    tree.nodes.forEach(n => m.set(n.id, n));
    return m;
  }, [tree]);

  const leafSet = useMemo(() => new Set(tree.leafIds), [tree]);

  const { layoutNodes, svgWidth, svgHeight } = useMemo(() => {
    if (!tree.nodes.length) return { layoutNodes: [], svgWidth: 300, svgHeight: 100 };
    const result: LayoutNode[] = [];
    let leafIndex = 0;

    function countLeaves(id: string): number {
      const node = nodeMap.get(id);
      if (!node) return 0;
      if (leafSet.has(id)) return 1;
      let sum = 0;
      for (const cid of node.childIds) sum += countLeaves(cid);
      return sum;
    }

    function layout(id: string, depth: number): { minX: number; maxX: number } {
      const node = nodeMap.get(id);
      if (!node) return { minX: 0, maxX: 0 };
      const y = TOP_PAD + depth * (NODE_H + V_GAP);
      const isLeaf = leafSet.has(id);
      const hasFork = node.childIds.length > 1;

      if (isLeaf || node.childIds.length === 0) {
        const x = LEFT_PAD + leafIndex * (NODE_W + H_GAP);
        leafIndex++;
        result.push({ node, x, y, depth, isLeaf, hasFork });
        return { minX: x, maxX: x };
      }
      let globalMin = Infinity, globalMax = -Infinity;
      for (const cid of node.childIds) {
        const { minX, maxX } = layout(cid, depth + 1);
        globalMin = Math.min(globalMin, minX);
        globalMax = Math.max(globalMax, maxX);
      }
      const x = (globalMin + globalMax) / 2;
      result.push({ node, x, y, depth, isLeaf, hasFork });
      return { minX: globalMin, maxX: globalMax };
    }

    const roots = tree.nodes.filter(n => n.parentId === null);
    roots.forEach(root => { countLeaves(root.id); layout(root.id, 0); });
    result.sort((a, b) => a.depth - b.depth || a.x - b.x);
    const maxX = Math.max(...result.map(n => n.x)) + NODE_W + LEFT_PAD;
    const maxY = Math.max(...result.map(n => n.y)) + NODE_H + TOP_PAD;
    return { layoutNodes: result, svgWidth: Math.max(maxX, 300), svgHeight: Math.max(maxY, 100) };
  }, [tree, nodeMap, leafSet]);

  const posMap = useMemo(() => {
    const m = new Map<string, { x: number; y: number }>();
    layoutNodes.forEach(ln => m.set(ln.node.id, { x: ln.x, y: ln.y }));
    return m;
  }, [layoutNodes]);

  const activeBranchIds = useMemo(() => {
    if (!activeLeafId) return new Set<string>();
    const ids = new Set<string>();
    let current: string | null = activeLeafId;
    while (current) { ids.add(current); current = nodeMap.get(current)?.parentId || null; }
    return ids;
  }, [activeLeafId, nodeMap]);

  const fitToView = useCallback(() => {
    if (!containerRef.current || layoutNodes.length === 0) return;
    const rect = containerRef.current.getBoundingClientRect();
    const pad = 24;
    const scaleX = (rect.width - pad * 2) / svgWidth;
    const scaleY = (rect.height - pad * 2) / svgHeight;
    const newZoom = Math.min(Math.max(Math.min(scaleX, scaleY), MIN_ZOOM), MAX_ZOOM);
    setPan({ x: (rect.width - svgWidth * newZoom) / 2, y: pad });
    setZoom(newZoom);
  }, [layoutNodes, svgWidth, svgHeight]);

  useEffect(() => { fitToView(); }, [fitToView]);

  const onResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isResizing.current = true;
    const startX = e.clientX;
    const startW = panelWidth;
    const onMove = (ev: MouseEvent) => { if (!isResizing.current) return; setPanelWidth(Math.min(MAX_PANEL_W, Math.max(MIN_PANEL_W, startW + (ev.clientX - startX)))); };
    const onUp = () => { isResizing.current = false; document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }, [panelWidth]);

  const onPanStart = useCallback((e: React.MouseEvent) => {
    if (e.button !== 0) return;
    const target = e.target as SVGElement;
    if (target.closest('g[data-node]')) return;
    isPanning.current = true;
    panStart.current = { x: e.clientX, y: e.clientY, panX: pan.x, panY: pan.y };
    const onMove = (ev: MouseEvent) => { if (!isPanning.current) return; setPan({ x: panStart.current.panX + (ev.clientX - panStart.current.x), y: panStart.current.panY + (ev.clientY - panStart.current.y) }); };
    const onUp = () => { isPanning.current = false; document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  }, [pan]);

  const onWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    setZoom(z => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, z + (-e.deltaY * 0.001))));
  }, []);

  const handleNodeClick = (nodeId: string) => {
    if (diffMode) {
      if (!diffLeft) { setDiffLeft(nodeId); }
      else { onDiff(diffLeft, nodeId); setDiffLeft(null); setDiffMode(false); }
    } else { onSelectNode(nodeId); }
  };

  const truncate = (s: string, len: number) => s.length > len ? s.slice(0, len) + '…' : s;

  const summarize = (content: string): string => {
    const cleaned = content.replace(/[#*`_~>\-]/g, '').replace(/\n+/g, ' ').trim();
    return truncate(cleaned, 60);
  };

  const neonColor = (role: string, active: boolean) => {
    if (role === 'user') return active ? '#00ff88' : '#00cc6a';
    if (role === 'assistant') return active ? '#00ddff' : '#00aacc';
    return active ? '#ffaa00' : '#cc8800';
  };

  return (
    <div style={{
      width: panelWidth, minWidth: panelWidth, background: '#ffffff',
      borderRight: '1px solid #000', display: 'flex', flexDirection: 'column',
      overflow: 'hidden', position: 'relative',
    }}>
      {/* Header */}
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        padding: '12px 14px 8px', borderBottom: '1px solid #000',
        fontSize: 10, textTransform: 'uppercase' as const, letterSpacing: 2, color: '#000', fontWeight: 400,
        fontFamily: 'Arial, sans-serif',
      }}>
        <span>BRANCHES</span>
        <button onClick={() => { setDiffMode(!diffMode); setDiffLeft(null); }} style={{
          fontSize: 10, padding: '2px 8px', borderRadius: 0, letterSpacing: 1,
          border: `1px solid ${diffMode ? '#000' : '#ccc'}`, textTransform: 'uppercase' as const,
          background: diffMode ? '#000' : '#fff', color: diffMode ? '#fff' : '#999',
          cursor: 'pointer', fontFamily: 'Arial, sans-serif',
        }}>
          {diffMode ? (diffLeft ? 'PICK RIGHT' : 'PICK LEFT') : 'DIFF'}
        </button>
      </div>

      {/* Zoom bar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 6, padding: '6px 14px',
        borderBottom: '1px solid #ccc', fontSize: 10, color: '#999',
        fontFamily: 'Arial, sans-serif', letterSpacing: 1, textTransform: 'uppercase' as const,
      }}>
        <button onClick={() => setZoom(z => Math.max(MIN_ZOOM, z - 0.15))} style={ctrlBtn}>−</button>
        <span style={{ minWidth: 36, textAlign: 'center' }}>{Math.round(zoom * 100)}%</span>
        <button onClick={() => setZoom(z => Math.min(MAX_ZOOM, z + 0.15))} style={ctrlBtn}>+</button>
        <button onClick={fitToView} style={{ ...ctrlBtn, padding: '2px 8px', marginLeft: 'auto' }}>FIT</button>
      </div>

      {/* Canvas */}
      <div
        ref={containerRef} onMouseDown={onPanStart} onWheel={onWheel}
        style={{ flex: 1, overflow: 'hidden', cursor: 'grab', position: 'relative', userSelect: 'none', background: '#fafafa' }}
      >
        {layoutNodes.length === 0 ? (
          <div style={{ padding: 24, color: '#999', fontSize: 10, textAlign: 'center', textTransform: 'uppercase' as const, letterSpacing: 2, fontFamily: 'Arial, sans-serif' }}>
            EMPTY — SEND A MESSAGE
          </div>
        ) : (
          <svg
            width={svgWidth * zoom} height={svgHeight * zoom}
            viewBox={`0 0 ${svgWidth} ${svgHeight}`}
            style={{ display: 'block', transform: `translate(${pan.x}px, ${pan.y}px)` }}
          >
            {/* Edges */}
            {layoutNodes.map(ln => {
              if (!ln.node.parentId) return null;
              const pp = posMap.get(ln.node.parentId);
              if (!pp) return null;
              const fromX = pp.x + NODE_W / 2, fromY = pp.y + NODE_H;
              const toX = ln.x + NODE_W / 2, toY = ln.y;
              const midY = fromY + (toY - fromY) / 2;
              const onBranch = activeBranchIds.has(ln.node.id) && activeBranchIds.has(ln.node.parentId);
              const color = onBranch ? neonColor(ln.node.role, true) : '#ddd';
              return (
                <path key={`e-${ln.node.id}`}
                  d={`M ${fromX} ${fromY} C ${fromX} ${midY}, ${toX} ${midY}, ${toX} ${toY}`}
                  fill="none" stroke={color} strokeWidth={onBranch ? 1.5 : 0.5} opacity={onBranch ? 1 : 0.6}
                  style={{ transition: 'stroke 0.2s, stroke-width 0.2s' }}
                />
              );
            })}

            {/* Nodes */}
            {layoutNodes.map(ln => {
              const { node } = ln;
              const isActive = node.id === activeLeafId;
              const onBranch = activeBranchIds.has(node.id);
              const isDiffSel = node.id === diffLeft;
              const neon = neonColor(node.role, onBranch);
              const dimmed = activeLeafId && !onBranch;

              return (
                <g key={node.id} data-node="true" onClick={() => handleNodeClick(node.id)}
                  style={{ cursor: 'pointer' }} opacity={dimmed ? 0.25 : 1}>

                  {/* Neon glow on active/diff-selected */}
                  {(isActive || isDiffSel) && (
                    <rect x={ln.x - 2} y={ln.y - 2} width={NODE_W + 4} height={NODE_H + 4}
                      fill="none" stroke={neon} strokeWidth={2} opacity={0.5}
                      style={{ filter: `drop-shadow(0 0 6px ${neon})` }} />
                  )}

                  {/* Main rect */}
                  <rect x={ln.x} y={ln.y} width={NODE_W} height={NODE_H}
                    fill="#fff" stroke={isActive ? neon : onBranch ? neon : '#ccc'}
                    strokeWidth={isActive ? 1.5 : 0.5}
                    style={{ transition: 'stroke 0.15s' }} />

                  {/* Left neon accent bar */}
                  <rect x={ln.x} y={ln.y} width={3} height={NODE_H} fill={neon} opacity={onBranch ? 1 : 0.3} />

                  {/* Role + badges */}
                  <text x={ln.x + 12} y={ln.y + 14} fontSize={9} fontFamily="Arial, sans-serif"
                    fontWeight={400} fill={onBranch ? neon : '#aaa'}
                    style={{ textTransform: 'uppercase' as unknown as string, letterSpacing: '1.5px' } as React.CSSProperties}>
                    {node.role}
                    {ln.hasFork ? ` [${node.childIds.length}]` : ''}
                    {ln.isLeaf ? ' ▪' : ''}
                  </text>

                  {/* Content summary line 1 */}
                  <text x={ln.x + 12} y={ln.y + 30} fontSize={10.5} fontFamily="Arial, sans-serif"
                    fill={onBranch ? '#000' : '#888'} fontWeight={onBranch ? 500 : 400}>
                    {truncate(summarize(node.content), 28)}
                  </text>
                  {/* Content summary line 2 */}
                  <text x={ln.x + 12} y={ln.y + 44} fontSize={10} fontFamily="Arial, sans-serif"
                    fill={onBranch ? '#555' : '#bbb'}>
                    {(() => {
                      const full = summarize(node.content);
                      return full.length > 28 ? truncate(full.slice(28), 28) : '';
                    })()}
                  </text>

                  {/* Provider / model micro-tag */}
                  {node.provider && (
                    <text x={ln.x + 12} y={ln.y + 62} fontSize={8} fontFamily="Arial, sans-serif"
                      fill="#bbb" style={{ letterSpacing: '0.5px' } as React.CSSProperties}>
                      {node.provider}{node.model ? ` / ${node.model.split('-').slice(0, 2).join('-')}` : ''}
                    </text>
                  )}

                  {/* Node ID (top right corner) */}
                  <text x={ln.x + NODE_W - 8} y={ln.y + 14} fontSize={7} fontFamily="Arial, sans-serif"
                    fill="#ddd" textAnchor="end">
                    {node.id.slice(0, 6)}
                  </text>
                </g>
              );
            })}
          </svg>
        )}
      </div>

      {/* Resize handle */}
      <div onMouseDown={onResizeStart} style={{
        position: 'absolute', top: 0, right: -3, width: 6, height: '100%',
        cursor: 'col-resize', zIndex: 10,
      }} />
    </div>
  );
}

const ctrlBtn: React.CSSProperties = {
  background: '#fff', border: '1px solid #ccc', borderRadius: 0, color: '#666',
  cursor: 'pointer', padding: '2px 6px', fontSize: 11, fontFamily: 'Arial, sans-serif',
  lineHeight: 1, textTransform: 'uppercase', letterSpacing: 1,
};
