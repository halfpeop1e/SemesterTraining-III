import { useMemo, useRef, useState, useEffect, useCallback } from 'react';
import type {
  LineProfile,
  MovingAuthority,
  TrainState,
  SignalAspect,
} from '../../../types/signal';
import type { SelectedEntity } from './TrackDiagram';
import {
  buildStationView,
  occupiedSegIdsInWindow,
  type Band,
  type PlacedSignal,
  type PlacedSwitch,
} from '../data/mileage';

interface Props {
  lineProfile: LineProfile;
  stationId: string;
  trains: TrainState[];
  maMap: Record<string, MovingAuthority>;
  selectedEntity: SelectedEntity | null;
  onSelect: (entity: SelectedEntity | null) => void;
  builtRouteSignalIds?: Set<number>;
}

const PAD_L = 56;
const PAD_R = 32;
const H = 360;
const Y_UP = 108;
const Y_MID = 180;
const Y_DOWN = 252;
const MIN_GAP = 36;

function aspectStyle(a: SignalAspect | null): {
  fill: string;
  stroke: string;
  dashed: boolean;
  label: string;
} {
  if (!a) {
    return { fill: '#450a0a', stroke: '#f87171', dashed: true, label: '未接入' };
  }
  switch (a) {
    case 'GREEN':
      return { fill: '#16a34a', stroke: '#bbf7d0', dashed: false, label: '绿' };
    case 'RED':
      return { fill: '#dc2626', stroke: '#fecaca', dashed: false, label: '红' };
    case 'YELLOW':
      return { fill: '#ca8a04', stroke: '#fef08a', dashed: false, label: '黄' };
    case 'RED_YELLOW':
      return { fill: '#ea580c', stroke: '#fed7aa', dashed: false, label: '红黄' };
    case 'WHITE':
      return { fill: '#f8fafc', stroke: '#94a3b8', dashed: false, label: '白' };
    case 'BLUE':
      return { fill: '#2563eb', stroke: '#bfdbfe', dashed: false, label: '蓝' };
    default:
      return { fill: '#475569', stroke: '#94a3b8', dashed: true, label: String(a) };
  }
}

function bandY(b: Band): number {
  if (b === 'up') return Y_UP;
  if (b === 'down') return Y_DOWN;
  return Y_MID;
}

function isDegraded(ev: string | undefined): boolean {
  return (
    ev === 'DEGRADED' ||
    ev === 'MA_EXPIRED' ||
    ev === 'SWITCH_ABNORMAL' ||
    ev === 'ROUTE_BLOCKED' ||
    ev === 'POSITION_LOSS'
  );
}

/** 同轨按 x 贪心推开，保证最小间距 */
function spreadX(
  items: { key: string; x: number; y: number }[],
  minGap: number,
  xMin: number,
  xMax: number,
): Map<string, number> {
  const byY = new Map<number, { key: string; x: number }[]>();
  for (const it of items) {
    const row = byY.get(it.y) || [];
    row.push({ key: it.key, x: it.x });
    byY.set(it.y, row);
  }
  const out = new Map<string, number>();
  for (const row of byY.values()) {
    row.sort((a, b) => a.x - b.x);
    for (let i = 0; i < row.length; i++) {
      if (i === 0) {
        out.set(row[i].key, Math.max(xMin, Math.min(xMax, row[i].x)));
        continue;
      }
      const prev = out.get(row[i - 1].key)!;
      const x = Math.max(row[i].x, prev + minGap);
      out.set(row[i].key, Math.min(xMax, x));
    }
    // 若右端溢出，整体左移
    if (row.length) {
      const lastKey = row[row.length - 1].key;
      const last = out.get(lastKey)!;
      if (last > xMax) {
        const shift = last - xMax;
        for (const r of row) {
          out.set(r.key, Math.max(xMin, out.get(r.key)! - shift));
        }
      }
    }
  }
  return out;
}

/** 极近道岔合并显示（同一显示位） */
function clusterSwitches(
  switches: PlacedSwitch[],
  mToX: (m: number) => number,
  mergePx = 22,
): { id: string; mileageM: number; x: number; members: PlacedSwitch[] }[] {
  if (!switches.length) return [];
  const sorted = switches.slice().sort((a, b) => a.mileageM - b.mileageM);
  const clusters: { id: string; mileageM: number; x: number; members: PlacedSwitch[] }[] = [];
  for (const sw of sorted) {
    const x = mToX(sw.mileageM);
    const last = clusters[clusters.length - 1];
    if (last && Math.abs(x - last.x) < mergePx) {
      last.members.push(sw);
      last.mileageM =
        last.members.reduce((s, m) => s + m.mileageM, 0) / last.members.length;
      last.x = mToX(last.mileageM);
      last.id = last.members.map((m) => m.id).join('/');
    } else {
      clusters.push({ id: sw.id, mileageM: sw.mileageM, x, members: [sw] });
    }
  }
  return clusters;
}

export default function RealStationDiagram({
  lineProfile,
  stationId,
  trains,
  maMap,
  selectedEntity,
  onSelect,
  builtRouteSignalIds,
}: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(900);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width;
      if (w && w > 0) setWidth(Math.floor(w));
    });
    ro.observe(el);
    setWidth(el.clientWidth || 900);
    return () => ro.disconnect();
  }, []);

  const view = useMemo(
    () => buildStationView(lineProfile, stationId, trains, maMap),
    [lineProfile, stationId, trains, maMap],
  );

  const mToX = useCallback(
    (m: number) => {
      if (!view) return PAD_L;
      const span = Math.max(1, view.window.endM - view.window.startM);
      const t = (m - view.window.startM) / span;
      return PAD_L + Math.max(0, Math.min(1, t)) * (width - PAD_L - PAD_R);
    },
    [view, width],
  );

  const xMin = PAD_L + 8;
  const xMax = width - PAD_R - 8;

  const occ = useMemo(() => {
    if (!view) return new Set<number>();
    return occupiedSegIdsInWindow(
      lineProfile,
      trains,
      view.window.startM,
      view.window.endM,
    );
  }, [lineProfile, trains, view]);

  const occBands = useMemo(() => {
    if (!view) return [] as { x1: number; x2: number }[];
    const out: { x1: number; x2: number }[] = [];
    const mi = lineProfile.segmentMileage || {};
    for (const id of occ) {
      const r = mi[String(id)];
      if (!r) continue;
      const a = Math.max(view.window.startM, r[0]);
      const b = Math.min(view.window.endM, r[1]);
      if (b > a) out.push({ x1: mToX(a), x2: mToX(b) });
    }
    return out;
  }, [occ, lineProfile.segmentMileage, view, mToX]);

  const layout = useMemo(() => {
    if (!view) return null;
    const rawSigs = view.signals.map((s) => ({
      key: `s-${s.id}`,
      x: mToX(s.mileageM),
      y: bandY(s.band),
      s,
    }));
    const sigX = spreadX(
      rawSigs.map((r) => ({ key: r.key, x: r.x, y: r.y })),
      MIN_GAP,
      xMin,
      xMax,
    );

    const swClusters = clusterSwitches(view.switches, mToX, 24);
    const swX = spreadX(
      swClusters.map((c) => ({ key: `w-${c.id}`, x: c.x, y: Y_MID })),
      MIN_GAP + 4,
      xMin,
      xMax,
    );

    return { rawSigs, sigX, swClusters, swX };
  }, [view, mToX, xMin, xMax]);

  if (!view || !layout) {
    return (
      <div className="flex h-full items-center justify-center rounded-xl border border-slate-700/60 bg-slate-950 text-sm text-slate-500">
        无法构建站域视图
      </div>
    );
  }

  const { window: win } = view;
  const span = win.endM - win.startM;
  const ticks: number[] = [];
  const step = span > 1200 ? 300 : span > 600 ? 150 : 100;
  for (let m = Math.ceil(win.startM / step) * step; m <= win.endM; m += step) {
    ticks.push(m);
  }

  return (
    <section className="flex h-full min-h-0 flex-col overflow-hidden rounded-xl border border-slate-700/60 bg-slate-950">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-800 px-3 py-2">
        <div>
          <div className="text-[11px] text-slate-500">真线路站域 · BJLine01</div>
          <div className="text-sm font-semibold text-slate-100">
            {win.station.name}
            <span className="ml-2 font-mono text-xs font-normal text-slate-400">
              {(win.centerM / 1000).toFixed(2)} km · 窗{' '}
              {(win.startM / 1000).toFixed(2)}–{(win.endM / 1000).toFixed(2)}
            </span>
          </div>
        </div>
        <div className="flex flex-wrap gap-3 text-[11px] text-slate-400">
          <span>
            信号 {view.signals.length}
            {layout.rawSigs.length !== view.signals.length
              ? ''
              : ''}
          </span>
          <span>
            道岔 {view.switches.length}
            {layout.swClusters.length < view.switches.length
              ? ` · 显示簇 ${layout.swClusters.length}`
              : ''}
          </span>
          <span>车 {view.trains.length}</span>
        </div>
      </div>

      <div ref={wrapRef} className="min-h-0 flex-1 w-full">
        <svg width={width} height={H} className="block select-none">
          <defs>
            <linearGradient id="trackGlow" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#0ea5e9" stopOpacity="0.15" />
              <stop offset="100%" stopColor="#0ea5e9" stopOpacity="0" />
            </linearGradient>
          </defs>
          <rect x={0} y={0} width={width} height={H} fill="#020617" />

          {occBands.map((b, i) => (
            <rect
              key={`occ-${i}`}
              x={b.x1}
              y={Y_UP - 28}
              width={Math.max(2, b.x2 - b.x1)}
              height={Y_DOWN - Y_UP + 56}
              fill="rgba(239,68,68,0.12)"
              rx={3}
            />
          ))}

          {/* 轨道 */}
          {(
            [
              [Y_UP, 'UP'],
              [Y_MID, '站中'],
              [Y_DOWN, 'DOWN'],
            ] as const
          ).map(([y, label]) => (
            <g key={label}>
              <line
                x1={PAD_L}
                y1={y}
                x2={width - PAD_R}
                y2={y}
                stroke="#1e293b"
                strokeWidth={y === Y_MID ? 4 : 6}
                strokeLinecap="round"
              />
              <line
                x1={PAD_L}
                y1={y}
                x2={width - PAD_R}
                y2={y}
                stroke="#38bdf8"
                strokeWidth={1.2}
                opacity={0.22}
              />
              <text x={12} y={y + 4} fontSize={11} fill="#64748b" fontWeight={700}>
                {label}
              </text>
            </g>
          ))}

          {/* 站中心 */}
          <line
            x1={mToX(win.centerM)}
            y1={44}
            x2={mToX(win.centerM)}
            y2={H - 44}
            stroke="#475569"
            strokeDasharray="4 5"
            opacity={0.8}
          />
          <rect
            x={mToX(win.centerM) - 32}
            y={38}
            width={64}
            height={22}
            rx={6}
            fill="#1e3a8a"
            stroke="#60a5fa"
            strokeWidth={1.5}
          />
          <text
            x={mToX(win.centerM)}
            y={53}
            textAnchor="middle"
            fontSize={12}
            fill="#f1f5f9"
            fontWeight={700}
          >
            {win.station.name}
          </text>

          {/* 刻度 */}
          {ticks.map((m) => (
            <g key={m}>
              <line
                x1={mToX(m)}
                y1={H - 36}
                x2={mToX(m)}
                y2={H - 28}
                stroke="#334155"
              />
              <text
                x={mToX(m)}
                y={H - 14}
                textAnchor="middle"
                fontSize={10}
                fill="#64748b"
              >
                {(m / 1000).toFixed(2)}
              </text>
            </g>
          ))}

          {/* 道岔簇 */}
          {layout.swClusters.map((cl) => {
            const x = layout.swX.get(`w-${cl.id}`) ?? cl.x;
            const members = cl.members;
            const anyReverse = members.some((m) => m.state === 'REVERSE');
            const allNormal = members.every((m) => m.state === 'NORMAL');
            const fill = anyReverse ? '#f97316' : allNormal ? '#22c55e' : '#94a3b8';
            const sel = members.some(
              (m) =>
                selectedEntity?.type === 'switch' &&
                String(selectedEntity.id) === m.id,
            );
            const label =
              members.length === 1
                ? members[0].id
                : members.map((m) => m.id).join(',');
            const primary = members[0];
            return (
              <g
                key={`swc-${cl.id}`}
                style={{ cursor: 'pointer' }}
                onClick={(e) => {
                  e.stopPropagation();
                  onSelect({ type: 'switch', id: primary.id });
                }}
              >
                {/* 短翼：定位横线 / 反位短斜，不拉上下轨 */}
                <line
                  x1={x - 14}
                  y1={Y_MID - 11}
                  x2={x + 14}
                  y2={Y_MID - 11}
                  stroke={allNormal && !anyReverse ? '#22c55e' : '#334155'}
                  strokeWidth={allNormal && !anyReverse ? 3 : 1.5}
                  strokeLinecap="round"
                />
                <line
                  x1={x - 10}
                  y1={Y_MID + 12}
                  x2={x + 10}
                  y2={Y_MID - 2}
                  stroke={anyReverse ? '#f97316' : '#334155'}
                  strokeWidth={anyReverse ? 3 : 1.5}
                  strokeLinecap="round"
                />
                <circle
                  cx={x}
                  cy={Y_MID}
                  r={9}
                  fill={fill}
                  stroke={sel ? '#fff' : '#020617'}
                  strokeWidth={sel ? 2.5 : 1.5}
                />
                {/* 标签底 */}
                <rect
                  x={x - Math.min(28, 6 + label.length * 4)}
                  y={Y_MID + 14}
                  width={Math.min(56, 12 + label.length * 8)}
                  height={14}
                  rx={3}
                  fill="#0f172a"
                  stroke="#334155"
                />
                <text
                  x={x}
                  y={Y_MID + 24}
                  textAnchor="middle"
                  fontSize={10}
                  fill="#e2e8f0"
                  fontWeight={700}
                >
                  {label}
                </text>
                <title>
                  {members
                    .map((m) => `道岔 ${m.id} · ${m.state || '未接入'}`)
                    .join('\n')}
                </title>
              </g>
            );
          })}

          {/* 信号 */}
          {layout.rawSigs.map(({ key, s, y }) => {
            const x = layout.sigX.get(key) ?? mToX(s.mileageM);
            const st = aspectStyle(s.aspect);
            const sel =
              selectedEntity?.type === 'signal' &&
              Number(selectedEntity.id) === s.id;
            const built = builtRouteSignalIds?.has(s.id);
            const above = s.band !== 'down';
            const lampY = above ? y - 30 : y + 30;
            const nameY = above ? lampY - 14 : lampY + 16;
            // leader 从真实里程点到展示 x（若被推开）
            const xTrue = mToX(s.mileageM);
            return (
              <g
                key={key}
                style={{ cursor: 'pointer' }}
                onClick={(e) => {
                  e.stopPropagation();
                  onSelect({ type: 'signal', id: s.id });
                }}
              >
                {Math.abs(x - xTrue) > 2 && (
                  <line
                    x1={xTrue}
                    y1={y}
                    x2={x}
                    y2={y}
                    stroke="#334155"
                    strokeWidth={1}
                    strokeDasharray="2 2"
                  />
                )}
                <line
                  x1={x}
                  y1={y}
                  x2={x}
                  y2={lampY}
                  stroke="#64748b"
                  strokeWidth={1.5}
                />
                {built && (
                  <circle
                    cx={x}
                    cy={lampY}
                    r={14}
                    fill="none"
                    stroke="#38bdf8"
                    strokeWidth={2}
                  />
                )}
                <circle
                  cx={x}
                  cy={lampY}
                  r={9}
                  fill={st.fill}
                  stroke={sel ? '#fff' : st.stroke}
                  strokeWidth={sel ? 2.5 : 1.5}
                  strokeDasharray={st.dashed ? '3 2' : undefined}
                />
                <rect
                  x={x - Math.min(26, 4 + s.name.length * 4)}
                  y={nameY - 10}
                  width={Math.min(52, 10 + s.name.length * 7)}
                  height={13}
                  rx={3}
                  fill="#0f172acc"
                />
                <text
                  x={x}
                  y={nameY}
                  textAnchor="middle"
                  fontSize={10}
                  fill="#f1f5f9"
                  fontWeight={700}
                >
                  {s.name}
                </text>
                <title>{`${s.name} #${s.id} · ${st.label} · ${s.band}`}</title>
              </g>
            );
          })}

          {/* 列车：按方向画在 UP/DOWN 轨，避开站中道岔层 */}
          {view.trains.map((t, i) => {
            const xRaw = mToX(t.positionM);
            // 与附近道岔显示位错开，避免完全重合
            let x = xRaw;
            for (const cl of layout.swClusters) {
              const sx = layout.swX.get(`w-${cl.id}`) ?? cl.x;
              if (Math.abs(x - sx) < 18) {
                x = sx + (x >= sx ? 20 : -20);
                break;
              }
            }
            x = Math.max(xMin, Math.min(xMax, x));

            const trainY = t.direction === 'DOWN' ? Y_DOWN : Y_UP;
            const ma = maMap[t.trainId];
            const c = ['#38bdf8', '#a3e635', '#facc15', '#f472b6'][i % 4];
            const sel =
              selectedEntity?.type === 'train' && selectedEntity.id === t.trainId;
            const maX =
              ma && Number.isFinite(ma.endOfAuthorityM)
                ? mToX(
                    Math.min(Math.max(ma.endOfAuthorityM, win.startM), win.endM),
                  )
                : null;
            const bad = ma ? isDegraded(ma.event) : false;
            const labelAbove = trainY === Y_UP;
            const nameY = labelAbove ? trainY - 16 : trainY + 18;
            const maLineY = labelAbove ? trainY - 40 : trainY + 36;
            const speed = Number.isFinite(t.speedKmh) ? t.speedKmh.toFixed(0) : '—';
            return (
              <g key={t.trainId}>
                {/* 车体占压示意（车长） */}
                {Number.isFinite(t.lengthM) && t.lengthM > 0 && (
                  <rect
                    x={mToX(Math.max(win.startM, t.positionM - t.lengthM))}
                    y={trainY - 5}
                    width={Math.max(
                      4,
                      mToX(t.positionM) -
                        mToX(Math.max(win.startM, t.positionM - t.lengthM)),
                    )}
                    height={10}
                    rx={3}
                    fill={c}
                    opacity={0.25}
                  />
                )}
                {maX != null && (
                  <>
                    <line
                      x1={x}
                      y1={maLineY}
                      x2={maX}
                      y2={maLineY}
                      stroke={bad ? '#ef4444' : '#22c55e'}
                      strokeWidth={2}
                      strokeDasharray={bad ? undefined : '5 4'}
                    />
                    <text
                      x={(x + maX) / 2}
                      y={maLineY - 4}
                      textAnchor="middle"
                      fontSize={9}
                      fill={bad ? '#ef4444' : '#4ade80'}
                      fontWeight={600}
                    >
                      MA {ma!.maxSpeedKmh.toFixed(0)}km/h{bad ? ' ⚠' : ''}
                    </text>
                  </>
                )}
                <g
                  style={{ cursor: 'pointer' }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onSelect({ type: 'train', id: t.trainId });
                  }}
                >
                  {/* 方向小三角 */}
                  {t.direction === 'DOWN' ? (
                    <polygon
                      points={`${x - 14},${trainY} ${x - 6},${trainY - 6} ${x - 6},${trainY + 6}`}
                      fill={c}
                      opacity={0.9}
                    />
                  ) : (
                    <polygon
                      points={`${x + 14},${trainY} ${x + 6},${trainY - 6} ${x + 6},${trainY + 6}`}
                      fill={c}
                      opacity={0.9}
                    />
                  )}
                  <circle
                    cx={x}
                    cy={trainY}
                    r={11}
                    fill={c}
                    stroke={sel ? '#fff' : '#020617'}
                    strokeWidth={sel ? 3 : 2}
                  />
                  <rect
                    x={x - 22}
                    y={nameY - 10}
                    width={44}
                    height={14}
                    rx={3}
                    fill="#020617ee"
                    stroke={c}
                    strokeWidth={1}
                  />
                  <text
                    x={x}
                    y={nameY}
                    textAnchor="middle"
                    fontSize={10}
                    fill={c}
                    fontWeight={800}
                  >
                    {t.trainId}
                  </text>
                  <text
                    x={x}
                    y={nameY + (labelAbove ? -12 : 14)}
                    textAnchor="middle"
                    fontSize={9}
                    fill="#94a3b8"
                  >
                    {t.direction} {speed}km/h
                  </text>
                </g>
                <title>
                  {`${t.trainId} ${t.direction} ${speed}km/h @ ${t.positionM.toFixed(0)}m`}
                </title>
              </g>
            );
          })}

          {view.signals.length === 0 && view.switches.length === 0 && (
            <text
              x={width / 2}
              y={H / 2}
              textAnchor="middle"
              fontSize={13}
              fill="#64748b"
            >
              本站窗口无已定位设备 — 换站
            </text>
          )}
        </svg>
      </div>

      <div className="flex flex-wrap gap-x-4 gap-y-1 border-t border-slate-800 px-3 py-1.5 text-[10px] text-slate-500">
        <span>● 红/绿/黄 = aspect</span>
        <span>虚线红圈 = aspect 未接入</span>
        <span>绿点=道岔定位 · 橙点=反位</span>
        <span>车=UP/DOWN 轨 · 道岔=站中 · 近距已错开</span>
        <span>点击联动左栏</span>
      </div>
    </section>
  );
}
