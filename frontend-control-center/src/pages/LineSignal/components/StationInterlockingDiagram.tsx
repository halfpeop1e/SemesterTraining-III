import { useMemo, useRef, useState, useEffect, useCallback } from 'react';
import type {
  DemoSignalAspect,
  InterlockingRuntime,
  StationLayout,
} from '../data/stationLayoutTypes';

interface Props {
  layout: StationLayout;
  runtime: InterlockingRuntime;
  onButtonSelect: (buttonId: string) => void;
}

const TRACK_BASE = '#b6883d';
const TRACK_CLEAR = '#5b6678';
const TRACK_LOCKED = '#f7b731';
const TRACK_OCC = '#ef5350';
const TRACK_BUILDING = '#38bdf8';

function aspectFill(aspect: DemoSignalAspect, broken: boolean): string {
  if (broken) return '#94a3b8';
  switch (aspect) {
    case 'green':
      return '#22c55e';
    case 'yellow':
    case 'doubleYellow':
      return '#facc15';
    case 'white':
      return '#f8fafc';
    case 'blue':
      return '#2563eb';
    case 'off':
      return '#1e293b';
    default:
      return '#ef4444';
  }
}

function trackColor(
  trackId: string,
  runtime: InterlockingRuntime,
): { stroke: string; width: number } {
  const tr = runtime.tracks[trackId];
  if (!tr) return { stroke: TRACK_CLEAR, width: 6 };
  if (tr.state === 'occupied') return { stroke: TRACK_OCC, width: 10 };
  if (tr.state === 'locked') return { stroke: TRACK_LOCKED, width: 10 };
  return { stroke: TRACK_CLEAR, width: 6 };
}

export default function StationInterlockingDiagram({
  layout,
  runtime,
  onButtonSelect,
}: Props) {
  const frameRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 1200, h: 200 });

  useEffect(() => {
    const el = frameRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const r = entries[0]?.contentRect;
      if (!r) return;
      setSize({ w: r.width, h: r.height });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const aspectRatio = layout.width / layout.height;

  const hotspots = useMemo(() => {
    const base = layout.buttons
      .filter((b) => b.visibleInDiagram !== false)
      .map((b) => ({
        key: b.id,
        buttonId: b.id,
        label: b.label,
        x: b.x,
        y: b.y,
        category: b.category,
        description: b.description,
      }));
    // D1 图上别名热点
    const d1 = layout.buttons.find((b) => b.id === 'D1');
    if (d1) {
      base.push({
        key: 'D1-left',
        buttonId: 'D1',
        label: 'D1',
        x: 47,
        y: 94,
        category: d1.category,
        description: d1.description,
      });
    }
    return base;
  }, [layout.buttons]);

  const selected = useMemo(() => new Set(runtime.selection), [runtime.selection]);

  const handleClick = useCallback(
    (id: string) => onButtonSelect(id),
    [onButtonSelect],
  );

  return (
    <section className="flex h-full min-h-0 flex-col rounded-xl border border-slate-700/60 bg-slate-900/40">
      <div className="flex items-center justify-between border-b border-slate-700/50 px-4 py-2">
        <div>
          <div className="text-xs text-slate-500">站场总览</div>
          <div className="text-sm font-semibold tracking-wide text-slate-100">
            {layout.name}
          </div>
        </div>
        <div className="rounded-full border border-slate-600 px-3 py-0.5 text-xs text-slate-400">
          点击按钮选路 · 选中 {runtime.selection.join(' → ') || '无'}
        </div>
      </div>

      <div
        ref={frameRef}
        className="relative min-h-0 flex-1 w-full bg-white"
        style={{ minHeight: 180 }}
      >
        <svg
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          preserveAspectRatio="xMidYMid meet"
          className="absolute inset-0 h-full w-full"
          style={{ maxHeight: size.w / aspectRatio }}
        >
          {/* 底轨 */}
          {layout.segments.map((seg) => {
            const pts = seg.points.map((p) => p.join(',')).join(' ');
            const { stroke, width } = trackColor(seg.trackId, runtime);
            const baseW = seg.kind === 'safe' ? 4 : 5;
            return (
              <g key={seg.id}>
                <polyline
                  points={pts}
                  fill="none"
                  stroke={TRACK_BASE}
                  strokeWidth={baseW}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                {stroke !== TRACK_CLEAR && (
                  <polyline
                    points={pts}
                    fill="none"
                    stroke={stroke}
                    strokeWidth={width}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    opacity={0.85}
                  />
                )}
              </g>
            );
          })}

          {/* 信号灯 */}
          {layout.signals.map((sig) => {
            const st = runtime.signals[sig.id];
            const aspect = st?.aspect ?? 'red';
            const broken = st?.filamentBroken ?? false;
            return (
              <g key={sig.id}>
                <line
                  x1={sig.x}
                  y1={sig.y - 12}
                  x2={sig.x}
                  y2={sig.y + 8}
                  stroke="#64748b"
                  strokeWidth={2}
                />
                <circle
                  cx={sig.x}
                  cy={sig.y}
                  r={8}
                  fill={aspectFill(aspect, broken)}
                  stroke="#475569"
                  strokeWidth={1.5}
                />
                {aspect === 'doubleYellow' && !broken && (
                  <circle
                    cx={sig.x + (sig.direction === 'left' ? -16 : 16)}
                    cy={sig.y}
                    r={8}
                    fill="#facc15"
                    stroke="#475569"
                    strokeWidth={1.5}
                  />
                )}
                {broken && (
                  <line
                    x1={sig.x - 7}
                    y1={sig.y - 8}
                    x2={sig.x + 7}
                    y2={sig.y + 8}
                    stroke="#f97316"
                    strokeWidth={2}
                  />
                )}
                <text
                  x={sig.x}
                  y={sig.y - 16}
                  textAnchor="middle"
                  fontSize={12}
                  fill="#1f2937"
                  fontFamily="Times New Roman, serif"
                >
                  {sig.label}
                </text>
              </g>
            );
          })}

          {/* 道岔标记 */}
          {layout.switches.map((sw) => {
            const st = runtime.switches[sw.id];
            let ring = 'transparent';
            if (st?.faulted) ring = '#dc2626';
            else if (st?.blocked) ring = '#f97316';
            else if (st?.locked) ring = '#0284c7';
            const posLabel = st?.position === 'reverse' ? '反' : '定';
            return (
              <g key={sw.id}>
                <circle
                  cx={sw.x}
                  cy={sw.y}
                  r={11}
                  fill="none"
                  stroke={ring}
                  strokeWidth={3}
                />
                <text
                  x={sw.x}
                  y={sw.y - 16}
                  textAnchor="middle"
                  fontSize={13}
                  fontWeight={600}
                  fill="#202020"
                >
                  {sw.label}
                </text>
                <text
                  x={sw.x}
                  y={sw.y + 4}
                  textAnchor="middle"
                  fontSize={9}
                  fill="#64748b"
                >
                  {posLabel}
                </text>
              </g>
            );
          })}

          {/* 选路按钮热点 */}
          {hotspots.map((h) => {
            const isSel = selected.has(h.buttonId);
            const fill =
              h.category === 'train'
                ? isSel
                  ? 'rgba(14,165,233,0.35)'
                  : 'rgba(217,70,239,0.2)'
                : isSel
                  ? 'rgba(14,165,233,0.35)'
                  : 'rgba(139,92,246,0.2)';
            const stroke = isSel
              ? '#0284c7'
              : h.category === 'train'
                ? '#c026d3'
                : '#7c3aed';
            return (
              <g
                key={h.key}
                style={{ cursor: 'pointer' }}
                onClick={() => handleClick(h.buttonId)}
              >
                <title>{`${h.label}：${h.description}`}</title>
                <rect
                  x={h.x - 12}
                  y={h.y - 12}
                  width={24}
                  height={24}
                  rx={4}
                  fill={fill}
                  stroke={stroke}
                  strokeWidth={2}
                  strokeDasharray={isSel ? undefined : '4 3'}
                />
                <rect
                  x={h.x - 8}
                  y={h.y - 8}
                  width={16}
                  height={16}
                  rx={2}
                  fill={h.category === 'train' ? '#ef9cf7' : '#cbd5e1'}
                  stroke="#1f2937"
                  strokeWidth={1.5}
                />
              </g>
            );
          })}

          {/* 区段名（稀疏标注） */}
          {[
            { text: 'IIAG', x: 111, y: 113 },
            { text: '3G', x: 620, y: 28 },
            { text: 'IIG', x: 620, y: 86 },
            { text: '1G', x: 620, y: 143 },
            { text: 'IIBG', x: 1193, y: 74 },
            { text: '安全线', x: 240, y: 170 },
          ].map((lb) => (
            <text
              key={lb.text}
              x={lb.x}
              y={lb.y}
              fontSize={12}
              fill="#374151"
              fontFamily="Times New Roman, serif"
            >
              {lb.text}
            </text>
          ))}
        </svg>
      </div>

      {/* 图例 */}
      <div className="flex flex-wrap gap-3 border-t border-slate-700/50 px-4 py-2 text-[11px] text-slate-400">
        <span className="flex items-center gap-1">
          <i className="inline-block h-2 w-4 rounded" style={{ background: TRACK_CLEAR }} />{' '}
          空闲
        </span>
        <span className="flex items-center gap-1">
          <i className="inline-block h-2 w-4 rounded" style={{ background: TRACK_LOCKED }} />{' '}
          进路锁闭
        </span>
        <span className="flex items-center gap-1">
          <i className="inline-block h-2 w-4 rounded" style={{ background: TRACK_OCC }} />{' '}
          占用
        </span>
        <span className="flex items-center gap-1">
          <i className="inline-block h-2 w-4 rounded" style={{ background: TRACK_BUILDING }} />{' '}
          建立中(参考)
        </span>
        <span className="flex items-center gap-1">
          <i className="inline-block h-2.5 w-2.5 rounded-full bg-red-500" /> 红停
        </span>
        <span className="flex items-center gap-1">
          <i className="inline-block h-2.5 w-2.5 rounded-full bg-green-500" /> 绿行
        </span>
      </div>
    </section>
  );
}
