import { useMemo, useRef, useState, useCallback, useEffect } from 'react';
import type { LineProfile, MovingAuthority, TrainState, SignalAspect, SignalEvent } from '../../../types/signal';

export interface SelectedEntity {
  type: 'signal' | 'train' | 'switch' | 'station';
  id: string | number;
}

interface TrackDiagramProps {
  lineProfile: LineProfile;
  maMap: Record<string, MovingAuthority>;
  trains: TrainState[];
  selectedEntity: SelectedEntity | null;
  onSelect: (entity: SelectedEntity | null) => void;
  mode?: 'overview' | 'station';
  stationId?: string | null;
}

// ===== 颜色映射 =====
const SIGNAL_COLOR: Record<SignalAspect, string> & { default: string } = {
  GREEN: '#06d6a0',
  RED: '#fc5c65',
  YELLOW: '#f7b731',
  RED_YELLOW: '#fc5c65',
  YELLOW_DARK: '#8c8c8c',
  RED_DARK: '#8c8c8c',
  GREEN_DARK: '#8c8c8c',
  WHITE: '#e8e8e8',
  BLUE: '#1890ff',
  RED_BROKEN: '#fc5c65',
  GREEN_BROKEN: '#fc5c65',
  YELLOW_BROKEN: '#fc5c65',
  WHITE_BROKEN: '#fc5c65',
  default: '#8c8c8c',
};

function getSignalColor(aspect: SignalAspect | null): string {
  if (aspect === null) return SIGNAL_COLOR.default;
  return SIGNAL_COLOR[aspect] ?? SIGNAL_COLOR.default;
}

const MA_COLOR: Record<SignalEvent, string> = {
  NONE: '#06d6a0',
  PRECEDING_OCCUPATION: '#06d6a0',
  SPEED_RESTRICTION: '#06d6a0',
  SWITCH_ABNORMAL: '#fc5c65',
  MA_EXPIRED: '#fc5c65',
  DEGRADED: '#fc5c65',
  SIGNAL_BOUNDARY: '#06d6a0',
  ROUTE_BLOCKED: '#fc5c65',
  AXLE_OCCUPIED: '#06d6a0',
  POSITION_LOSS: '#fc5c65',
};

const TRAIN_COLORS = ['#00a8e8', '#06d6a0', '#f7b731', '#fc5c65', '#9b59b6', '#45aaf2'];

const PADDING = 40;
const DIAGRAM_HEIGHT = 680;

// 一维概览模式常量
const OVERVIEW_TRACK_Y = 360;
const OVERVIEW_STATION_Y = OVERVIEW_TRACK_Y - 70;

// 二维站场模式常量
const STATION_TRACK_Y = 360;
const TRACK_SPACING = 70;
const TOP_TRACK_Y = STATION_TRACK_Y - TRACK_SPACING;
const BOT_TRACK_Y = STATION_TRACK_Y + TRACK_SPACING;

function isDegraded(event: SignalEvent): boolean {
  return event === 'DEGRADED' || event === 'MA_EXPIRED' || event === 'SWITCH_ABNORMAL'
    || event === 'ROUTE_BLOCKED' || event === 'POSITION_LOSS';
}

export default function TrackDiagram({ lineProfile, maMap, trains, selectedEntity, onSelect, mode = 'overview', stationId }: TrackDiagramProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(1200);
  const [viewMin, setViewMin] = useState(0); // 里程比例 0..1
  const [viewMax, setViewMax] = useState(1);
  const [dragging, setDragging] = useState(false);
  const dragRef = useRef({ x: 0, viewMin: 0, viewMax: 0 });

  // 自适应容器宽度
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const cr = entries[0].contentRect;
      if (cr.width > 0) setWidth(Math.floor(cr.width));
    });
    ro.observe(el);
    setWidth(el.clientWidth || 1200);
    return () => ro.disconnect();
  }, []);

  const totalLen = lineProfile.totalLengthM || 1;
  const zoom = viewMax - viewMin;

  // 里程 → 像素 X（按当前视图窗口）
  const mToX = useCallback((m: number): number => {
    const ratio = (m / totalLen - viewMin) / Math.max(zoom, 1e-6);
    return Math.max(0, Math.min(width, ratio * (width - PADDING * 2) + PADDING));
  }, [totalLen, viewMin, viewMax, width, zoom]);

  // 像素 X → 里程比例
  const xToRatio = useCallback((x: number): number => {
    return ((x - PADDING) / (width - PADDING * 2)) * zoom + viewMin;
  }, [width, viewMin, zoom]);

  // 滚轮缩放（以鼠标位置为中心）
  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    const rect = (e.currentTarget as HTMLDivElement).getBoundingClientRect();
    const x = e.clientX - rect.left;
    const ratio = xToRatio(x);
    const span = zoom;
    const delta = e.deltaY > 0 ? span * 0.15 : -span * 0.15;
    const newSpan = Math.max(0.03, Math.min(1, span + delta));
    const newMin = Math.max(0, ratio - (ratio - viewMin) * (newSpan / span));
    const newMax = Math.min(1, newMin + newSpan);
    setViewMin(newMin);
    setViewMax(Math.min(1, newMax));
  }, [viewMin, zoom, xToRatio]);

  // 拖拽平移
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    setDragging(true);
    dragRef.current = { x: e.clientX, viewMin, viewMax };
  }, [viewMin, viewMax]);

  const handleMouseMove = useCallback((e: React.MouseEvent) => {
    if (!dragging) return;
    const dx = e.clientX - dragRef.current.x;
    const ratioPerPx = (dragRef.current.viewMax - dragRef.current.viewMin) / Math.max(1, width - PADDING * 2);
    const delta = dx * ratioPerPx;
    const span = dragRef.current.viewMax - dragRef.current.viewMin;
    let newMin = dragRef.current.viewMin - delta;
    let newMax = newMin + span;
    if (newMin < 0) { newMin = 0; newMax = span; }
    if (newMax > 1) { newMax = 1; newMin = 1 - span; }
    setViewMin(newMin);
    setViewMax(newMax);
  }, [dragging, width]);

  const endDrag = useCallback(() => setDragging(false), []);

  // 站点
  const stations = useMemo(() => {
    return (lineProfile.stations || []).filter(s => s.positionM > 0).sort((a, b) => a.positionM - b.positionM);
  }, [lineProfile]);

  // 每个站的管辖范围（前后站中点）
  const stationRanges = useMemo(() => {
    const ranges: Record<string, [number, number]> = {};
    stations.forEach((st, i) => {
      const prevM = i > 0 ? stations[i - 1].positionM : 0;
      const nextM = i < stations.length - 1 ? stations[i + 1].positionM : totalLen;
      const startM = (st.positionM + prevM) / 2;
      const endM = (st.positionM + nextM) / 2;
      ranges[st.id] = [Math.max(0, startM), Math.min(totalLen, endM)];
    });
    return ranges;
  }, [stations, totalLen]);

  // 根据模式设置视图范围
  useEffect(() => {
    if (mode === 'station' && stationId && stationRanges[stationId]) {
      const [startM, endM] = stationRanges[stationId];
      setViewMin(startM / totalLen);
      setViewMax(endM / totalLen);
    } else {
      // 概览默认聚焦站区（0 ~ 最远站+余量），让 13 站铺满宽度、站名舒展
      const maxStationM = stations.length ? stations[stations.length - 1].positionM : totalLen;
      const focusEnd = Math.min(totalLen, maxStationM + 1500);
      setViewMin(0);
      setViewMax(focusEnd / totalLen);
    }
  }, [mode, stationId, stationRanges, totalLen, stations]);

  // 信号机里程
  const signalsWithM = useMemo(() => {
    return (lineProfile.signals || []).map(sig => {
      const mi = lineProfile.segmentMileage?.[String(sig.segId)];
      const m = mi ? mi[0] + sig.offsetCm / 100 : 0;
      return { ...sig, mileageM: m };
    }).filter(s => s.mileageM > 0);
  }, [lineProfile]);

  // 道岔
  const switches = useMemo(() => {
    return (lineProfile.switches || []).filter(s => s.positionM > 0);
  }, [lineProfile]);

  // 区段占用（基于列车位置+车长）
  const occupiedSegIds = useMemo(() => {
    const occupied = new Set<number>();
    const mileage = lineProfile.segmentMileage;
    for (const t of trains) {
      if (isNaN(t.positionM)) continue;
      const startM = Math.max(0, t.positionM - t.lengthM);
      const endM = t.positionM;
      for (const [segId, [s, e]] of Object.entries(mileage)) {
        if (e > startM && s < endM) occupied.add(Number(segId));
      }
    }
    return occupied;
  }, [lineProfile.segmentMileage, trains]);

  const segments = useMemo(() => {
    return Object.entries(lineProfile.segmentMileage || {})
      .map(([id, [startM, endM]]) => ({ id: Number(id), startM, endM }))
      .filter((s) => s.endM > s.startM);
  }, [lineProfile.segmentMileage]);

  // 当前站范围内的元素
  const currentStation = useMemo(() => stations.find(s => s.id === stationId) || null, [stations, stationId]);
  const stationRange = useMemo(() => (stationId ? stationRanges[stationId] : null), [stationId, stationRanges]);
  const visibleSignals = useMemo(() => {
    if (mode === 'overview') return [];
    if (!stationRange) return signalsWithM;
    return signalsWithM.filter(s => s.mileageM >= stationRange[0] && s.mileageM <= stationRange[1]);
  }, [signalsWithM, mode, stationRange]);
  const visibleSwitches = useMemo(() => {
    if (mode === 'overview') return [];
    if (!stationRange) return switches;
    return switches.filter(s => s.positionM >= stationRange[0] && s.positionM <= stationRange[1]);
  }, [switches, mode, stationRange]);

  // 注：限速（238 条，含 144 条≈100km/h 默认速度）不在概览渲染，
  // 因其依赖的 segmentMileage 在原始 CBTC 导出中无法可靠推导（segment 连通链断裂、
  // 按 id 累加里程与站点 chainage 偏差达数 km 且非线性）。限速信息保留在 JSON 中，
  // 可在站场详情或后续用正确里程重建后挂回概览。

  const trainColor = (i: number) => TRAIN_COLORS[i % TRAIN_COLORS.length];

  // ====== 一维概览模式 ======
  const renderOverview = () => {
    const stationGapPx = stations.length > 1
      ? (mToX(stations[stations.length - 1].positionM) - mToX(stations[0].positionM)) / (stations.length - 1)
      : 999;
    const compact = stationGapPx < 90;

    // 里程刻度
    const visibleLen = totalLen * zoom;
    let step = 2000;
    if (visibleLen > 20000) step = 5000;
    if (visibleLen > 40000) step = 10000;
    if (visibleLen < 8000) step = 1000;
    const startM = Math.max(0, Math.floor(viewMin * totalLen / step) * step);
    const endM = Math.min(totalLen, Math.ceil(viewMax * totalLen / step) * step);
    const ticks: number[] = [];
    for (let m = startM; m <= endM + 1; m += step) ticks.push(m);

    return (
      <>
        {/* 区段占用底色 */}
        <g id="sections">
          {segments.map((seg) => {
            const x1 = mToX(seg.startM);
            const x2 = mToX(seg.endM);
            const occupied = occupiedSegIds.has(seg.id);
            return (
              <rect
                key={`sec-${seg.id}`}
                x={x1}
                y={OVERVIEW_TRACK_Y - 12}
                width={Math.max(2, x2 - x1)}
                height={24}
                rx={3}
                fill={occupied ? 'rgba(252,92,101,0.16)' : 'rgba(148,163,184,0.05)'}
                stroke={occupied ? 'rgba(252,92,101,0.3)' : 'rgba(148,163,184,0.1)'}
                strokeWidth={1}
              />
            );
          })}
        </g>

        {/* 轨道主线 */}
        <g id="track-line">
          <line x1={PADDING} y1={OVERVIEW_TRACK_Y} x2={width - PADDING} y2={OVERVIEW_TRACK_Y} stroke="#334155" strokeWidth={5} />
          <line x1={PADDING} y1={OVERVIEW_TRACK_Y} x2={width - PADDING} y2={OVERVIEW_TRACK_Y} stroke="#0ea5e9" strokeWidth={1} opacity={0.35} />
        </g>

        {/* 方向箭头 */}
        <g id="direction-arrows">
          <g transform={`translate(${PADDING + 12}, ${OVERVIEW_TRACK_Y - 3})`}>
            <polygon points="0,0 10,5 0,10" fill="#475569" />
            <text x={14} y={8} fontSize={9} fill="#475569" fontWeight={600}>UP</text>
          </g>
          <g transform={`translate(${width - PADDING - 12}, ${OVERVIEW_TRACK_Y - 3})`}>
            <polygon points="0,5 10,0 10,10" fill="#475569" />
            <text x={-8} y={8} fontSize={9} fill="#475569" fontWeight={600} textAnchor="end">DOWN</text>
          </g>
        </g>

        {/* 里程轴刻度 */}
        <g id="axis">
          {ticks.map((m, i) => {
            const x = mToX(m);
            return (
              <g key={`tick-${i}`}>
                <line x1={x} y1={OVERVIEW_TRACK_Y + 22} x2={x} y2={OVERVIEW_TRACK_Y + 32} stroke="#334155" strokeWidth={1} />
                <text x={x} y={OVERVIEW_TRACK_Y + 48} textAnchor="middle" fontSize={10} fill="#64748b">
                  {(m / 1000).toFixed(1)}km
                </text>
              </g>
            );
          })}
        </g>

        {/* 站点 */}
        <g id="stations">
          {stations.map((st, i) => {
            const x = mToX(st.positionM);
            const isSel = selectedEntity?.type === 'station' && selectedEntity.id === st.id;
            const top = i % 2 === 0;
            const labelY = top ? OVERVIEW_STATION_Y - 16 : OVERVIEW_STATION_Y + 28;
            const barY = top ? OVERVIEW_STATION_Y - 8 : OVERVIEW_STATION_Y;
            const name = compact && st.name.length > 3 ? st.name.slice(0, 3) : st.name;
            return (
              <g key={`st-${i}`} onClick={(e) => { e.stopPropagation(); onSelect({ type: 'station', id: st.id }); }} style={{ cursor: 'pointer' }}>
                <line x1={x} y1={OVERVIEW_TRACK_Y - 12} x2={x} y2={barY} stroke="#475569" strokeWidth={1} />
                <rect x={x - 26} y={barY - 10} width={52} height={20} rx={10}
                  fill={isSel ? '#22c55e' : '#1e3a8a'} stroke={isSel ? '#fff' : '#3b82f6'} strokeWidth={1} />
                <text x={x} y={barY + 4} textAnchor="middle" fontSize={9} fill="#e2e8f0" fontWeight="600">
                  {name}
                </text>
                {(!compact || isSel) && (
                  <text x={x} y={labelY} textAnchor="middle" fontSize={8} fill="#64748b">
                    {(st.positionM / 1000).toFixed(2)}km
                  </text>
                )}
              </g>
            );
          })}
        </g>

        {/* 列车 + MA */}
        <g id="trains-ma">
          {trains.map((train, i) => {
            const x = mToX(train.positionM);
            if (isNaN(x)) return null;
            const ma = maMap[train.trainId];
            const isSel = selectedEntity?.type === 'train' && selectedEntity.id === train.trainId;
            const c = trainColor(i);
            const maColor = ma ? (isDegraded(ma.event) ? '#fc5c65' : '#06d6a0') : '#8c8c8c';
            const maX = ma ? mToX(ma.endOfAuthorityM) : null;
            const degraded = ma ? isDegraded(ma.event) : false;
            const tailM = Math.max(0, train.positionM - train.lengthM);
            const tailX = mToX(tailM);
            const carWidth = Math.max(2, x - tailX);
            return (
              <g key={`train-${i}`}>
                {maX !== null && (
                  <g>
                    <rect x={Math.min(x, maX)} y={OVERVIEW_TRACK_Y - 22} width={Math.abs(maX - x)} height={44}
                      fill={maColor} opacity={0.08} />
                    <line x1={maX} y1={OVERVIEW_TRACK_Y - 50} x2={maX} y2={OVERVIEW_TRACK_Y + 50}
                      stroke={maColor} strokeWidth={1.5} strokeDasharray={degraded ? 'none' : '5,4'} />
                    <circle cx={maX} cy={OVERVIEW_TRACK_Y - 55} r={3} fill={maColor} />
                    <text x={maX + 8} y={OVERVIEW_TRACK_Y - 52} textAnchor="start" fontSize={9} fill={maColor} fontWeight="bold">
                      EoA {(ma!.endOfAuthorityM / 1000).toFixed(2)}km · {ma!.maxSpeedKmh.toFixed(0)}km/h
                    </text>
                    {degraded && (
                      <text x={maX + 8} y={OVERVIEW_TRACK_Y - 66} textAnchor="start" fontSize={11} fill="#fc5c65">⚠ MA 降级</text>
                    )}
                  </g>
                )}
                <rect x={tailX} y={OVERVIEW_TRACK_Y - 6} width={carWidth} height={12} rx={4}
                  fill={c} opacity={0.22} stroke={c} strokeWidth={1} />
                <g onClick={(e) => { e.stopPropagation(); onSelect({ type: 'train', id: train.trainId }); }} style={{ cursor: 'pointer' }}>
                  <circle cx={x} cy={OVERVIEW_TRACK_Y} r={8} fill={c}
                    stroke={isSel ? '#fff' : '#0f172a'} strokeWidth={2}
                    className={train.speedKmh > 0 ? 'train-moving' : ''} />
                  {train.direction === 'UP' && (
                    <polygon points={`${x + 10},${OVERVIEW_TRACK_Y - 4} ${x + 18},${OVERVIEW_TRACK_Y} ${x + 10},${OVERVIEW_TRACK_Y + 4}`}
                      fill={c} className="dir-arrow-anim" />
                  )}
                  {train.direction === 'DOWN' && (
                    <polygon points={`${x - 10},${OVERVIEW_TRACK_Y - 4} ${x - 18},${OVERVIEW_TRACK_Y} ${x - 10},${OVERVIEW_TRACK_Y + 4}`}
                      fill={c} className="dir-arrow-anim" />
                  )}
                  <text x={x} y={OVERVIEW_TRACK_Y - 14} textAnchor="middle" fontSize={9} fill={c} fontWeight="bold">
                    {train.trainId}
                  </text>
                  <text x={x} y={OVERVIEW_TRACK_Y + 24} textAnchor="middle" fontSize={8} fill="#64748b">
                    {train.direction} · {train.speedKmh.toFixed(0)}km/h
                  </text>
                </g>
              </g>
            );
          })}
        </g>
      </>
    );
  };

  // ====== 二维站场模式 ======
  const renderStation = () => {
    if (!currentStation || !stationRange) return null;
    const [rangeStartM, rangeEndM] = stationRange;
    const rangeLenM = rangeEndM - rangeStartM;

    // 里程 → 站场局部 X
    const localMToX = (m: number) => {
      const ratio = (m - rangeStartM) / Math.max(rangeLenM, 1);
      return PADDING + ratio * (width - PADDING * 2);
    };

    // 刻度
    const ticks: number[] = [];
    const stepM = Math.max(200, Math.round(rangeLenM / 8 / 100) * 100);
    for (let m = Math.floor(rangeStartM / stepM) * stepM; m <= rangeEndM; m += stepM) {
      if (m >= rangeStartM) ticks.push(m);
    }

    // 按 segId 给元素分组，构造二维布局
    const segIds = Array.from(new Set([
      ...visibleSignals.map(s => s.segId),
      ...visibleSwitches.map(s => s.normalSegId),
      ...visibleSwitches.map(s => s.reverseSegId),
    ])).sort((a, b) => a - b);

    // 给每个 segId 分配一条股道 Y（循环上中下）
    const segY: Record<number, number> = {};
    segIds.forEach((id, i) => {
      const trackIndex = i % 3; // 0=上, 1=中, 2=下
      segY[id] = trackIndex === 0 ? TOP_TRACK_Y : trackIndex === 1 ? STATION_TRACK_Y : BOT_TRACK_Y;
    });

    // 信号机按 segId 分配到对应股道，再按里程排序后上下错开
    const signalsBySeg: Record<number, typeof visibleSignals> = {};
    visibleSignals.forEach(s => {
      const y = segY[s.segId] ?? STATION_TRACK_Y;
      if (!signalsBySeg[y]) signalsBySeg[y] = [];
      signalsBySeg[y].push(s);
    });
    Object.values(signalsBySeg).forEach(arr => arr.sort((a, b) => a.mileageM - b.mileageM));

    const showSignalNames = visibleSignals.length < 40;

    return (
      <>
        {/* 站场背景框 */}
        <rect x={PADDING} y={TOP_TRACK_Y - 55} width={width - PADDING * 2} height={BOT_TRACK_Y - TOP_TRACK_Y + 110}
          fill="rgba(15,23,42,0.4)" stroke="#1e293b" strokeWidth={1} rx={8} />

        {/* 站名标题 */}
        <text x={width / 2} y={TOP_TRACK_Y - 62} textAnchor="middle" fontSize={14} fill="#e2e8f0" fontWeight="bold">
          {currentStation.name} 站场信号图
        </text>

        {/* 股道线 */}
        {[TOP_TRACK_Y, STATION_TRACK_Y, BOT_TRACK_Y].map((y, i) => (
          <g key={`track-${i}`}>
            <line x1={PADDING} y1={y} x2={width - PADDING} y2={y} stroke="#334155" strokeWidth={4} />
            <line x1={PADDING} y1={y} x2={width - PADDING} y2={y} stroke="#0ea5e9" strokeWidth={1} opacity={0.25} />
            <text x={PADDING - 8} y={y + 4} textAnchor="end" fontSize={9} fill="#475569">股道{i + 1}</text>
          </g>
        ))}

        {/* 区段占用（三条线都覆盖） */}
        {segments.filter(s => s.endM >= rangeStartM && s.startM <= rangeEndM).map((seg) => {
          const x1 = localMToX(seg.startM);
          const x2 = localMToX(seg.endM);
          const occupied = occupiedSegIds.has(seg.id);
          return (
            <rect key={`sec-st-${seg.id}`}
              x={x1} y={TOP_TRACK_Y - 10}
              width={Math.max(2, x2 - x1)} height={BOT_TRACK_Y - TOP_TRACK_Y + 20}
              rx={3} fill={occupied ? 'rgba(252,92,101,0.12)' : 'rgba(148,163,184,0.03)'}
              stroke={occupied ? 'rgba(252,92,101,0.25)' : 'transparent'} strokeWidth={1} />
          );
        })}

        {/* 道岔连接菱形 */}
        {visibleSwitches.map((sw, i) => {
          const x = localMToX(sw.positionM);
          const isSel = selectedEntity?.type === 'switch' && selectedEntity.id === sw.id;
          const fromY = segY[sw.normalSegId] ?? STATION_TRACK_Y;
          const toY = segY[sw.reverseSegId] ?? (fromY === TOP_TRACK_Y ? BOT_TRACK_Y : TOP_TRACK_Y);
          const midY = (fromY + toY) / 2;
          const normalColor = sw.state === 'NORMAL' ? '#06d6a0' : '#64748b';
          const reverseColor = sw.state === 'REVERSE' ? '#f97316' : '#64748b';
          return (
            <g key={`sw-${i}`} onClick={(e) => { e.stopPropagation(); onSelect({ type: 'switch', id: sw.id }); }} style={{ cursor: 'pointer' }}>
              <path d={`M ${x - 18} ${fromY} L ${x} ${midY} L ${x + 18} ${fromY}`}
                fill="none" stroke={normalColor} strokeWidth={2.5}
                strokeDasharray={sw.state === 'NORMAL' ? 'none' : '4,3'} />
              <path d={`M ${x - 18} ${toY} L ${x} ${midY} L ${x + 18} ${toY}`}
                fill="none" stroke={reverseColor} strokeWidth={2.5}
                strokeDasharray={sw.state === 'REVERSE' ? 'none' : '4,3'} />
              <circle cx={x} cy={midY} r={7}
                fill={sw.state === 'NORMAL' ? '#06d6a0' : sw.state === 'REVERSE' ? '#f97316' : '#475569'}
                stroke={isSel ? '#fff' : '#1e293b'} strokeWidth={1.5} />
              <text x={x} y={midY - 12} textAnchor="middle" fontSize={9} fill="#94a3b8">W{sw.id}</text>
            </g>
          );
        })}

        {/* 信号机 */}
        {Object.entries(signalsBySeg).map(([yStr, arr]) => {
          const y = Number(yStr);
          const top = y <= STATION_TRACK_Y;
          return arr.map((sig, i) => {
            const x = localMToX(sig.mileageM);
            const color = getSignalColor(sig.aspect);
            const isSel = selectedEntity?.type === 'signal' && selectedEntity.id === sig.id;
            const nullAspect = sig.aspect === null || sig.aspect === undefined;
            const cy = top ? y - 28 : y + 28;
            const triY = top ? cy + 8 : cy - 8;
            return (
              <g key={`sig-${sig.id}`} onClick={(e) => { e.stopPropagation(); onSelect({ type: 'signal', id: sig.id }); }} style={{ cursor: 'pointer' }}>
                <line x1={x} y1={y} x2={x} y2={triY} stroke="#475569" strokeWidth={1} />
                {nullAspect && (
                  <circle cx={x} cy={cy} r={11} fill="none" stroke="#fc5c65" strokeWidth={1.5} strokeDasharray="3,2" className="sig-broken" />
                )}
                <polygon points={`${x - 7},${triY} ${x + 7},${triY} ${x},${triY + (top ? -14 : 14)}`}
                  fill={color} stroke={isSel ? '#fff' : nullAspect ? '#64748b' : '#1e293b'} strokeWidth={isSel ? 2 : 1}
                  strokeDasharray={nullAspect ? '3,2' : 'none'} className={nullAspect ? 'sig-broken' : ''} />
                {(showSignalNames || isSel) && (
                  <text x={x} y={top ? cy - 18 : cy + 28} textAnchor="middle" fontSize={8} fill="#94a3b8">{sig.name}</text>
                )}
              </g>
            );
          });
        })}

        {/* 列车（只在主线） */}
        {trains.filter(t => t.positionM >= rangeStartM && t.positionM <= rangeEndM).map((train, i) => {
          const x = localMToX(train.positionM);
          if (isNaN(x)) return null;
          const ma = maMap[train.trainId];
          const isSel = selectedEntity?.type === 'train' && selectedEntity.id === train.trainId;
          const c = trainColor(i);
          const maColor = ma ? (isDegraded(ma.event) ? '#fc5c65' : '#06d6a0') : '#8c8c8c';
          const maX = ma ? localMToX(Math.min(ma.endOfAuthorityM, rangeEndM)) : null;
          const degraded = ma ? isDegraded(ma.event) : false;
          return (
            <g key={`train-${i}`}>
              {maX !== null && (
                <g>
                  <line x1={x} y1={STATION_TRACK_Y - 35} x2={maX} y2={STATION_TRACK_Y - 35}
                    stroke={maColor} strokeWidth={2} strokeDasharray={degraded ? 'none' : '5,4'} />
                  <text x={maX + 6} y={STATION_TRACK_Y - 38} textAnchor="start" fontSize={9} fill={maColor} fontWeight="bold">
                    EoA {((ma!.endOfAuthorityM - rangeStartM) / 1000).toFixed(2)}km
                  </text>
                  {degraded && (
                    <text x={maX + 6} y={STATION_TRACK_Y - 50} textAnchor="start" fontSize={11} fill="#fc5c65">⚠ MA 降级</text>
                  )}
                </g>
              )}
              <g onClick={(e) => { e.stopPropagation(); onSelect({ type: 'train', id: train.trainId }); }} style={{ cursor: 'pointer' }}>
                <circle cx={x} cy={STATION_TRACK_Y} r={9} fill={c} stroke={isSel ? '#fff' : '#0f172a'} strokeWidth={2}
                  className={train.speedKmh > 0 ? 'train-moving' : ''} />
                <text x={x} y={STATION_TRACK_Y - 16} textAnchor="middle" fontSize={9} fill={c} fontWeight="bold">{train.trainId}</text>
                <text x={x} y={STATION_TRACK_Y + 24} textAnchor="middle" fontSize={8} fill="#64748b">{train.speedKmh.toFixed(0)}km/h</text>
              </g>
            </g>
          );
        })}

        {/* 里程刻度 */}
        <g id="axis">
          {ticks.map((m, i) => {
            const x = localMToX(m);
            return (
              <g key={`tick-${i}`}>
                <line x1={x} y1={BOT_TRACK_Y + 18} x2={x} y2={BOT_TRACK_Y + 28} stroke="#334155" strokeWidth={1} />
                <text x={x} y={BOT_TRACK_Y + 44} textAnchor="middle" fontSize={10} fill="#64748b">
                  {((m - rangeStartM) / 1000).toFixed(1)}km
                </text>
              </g>
            );
          })}
        </g>

        {/* 方向箭头 */}
        <g transform={`translate(${PADDING + 12}, ${STATION_TRACK_Y - 3})`}>
          <polygon points="0,0 10,5 0,10" fill="#475569" />
          <text x={14} y={8} fontSize={9} fill="#475569" fontWeight={600}>UP</text>
        </g>
        <g transform={`translate(${width - PADDING - 12}, ${STATION_TRACK_Y - 3})`}>
          <polygon points="0,5 10,0 10,10" fill="#475569" />
          <text x={-8} y={8} fontSize={9} fill="#475569" fontWeight={600} textAnchor="end">DOWN</text>
        </g>
      </>
    );
  };

  return (
    <div
      ref={containerRef}
      style={{ width: '100%', height: '100%', overflow: 'hidden', background: '#0b0f19', cursor: dragging ? 'grabbing' : 'grab' }}
      onWheel={handleWheel}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={endDrag}
      onMouseLeave={endDrag}
    >
      <svg width={width} height={DIAGRAM_HEIGHT} style={{ display: 'block' }}>
        <defs>
          <style>{`
            @keyframes sig-blink {
              0%, 100% { opacity: 1; }
              50% { opacity: 0.3; }
            }
            @keyframes train-pulse {
              0%, 100% { transform: scale(1); }
              50% { transform: scale(1.08); }
            }
            @keyframes arrow-flow {
              0% { opacity: 0.4; }
              50% { opacity: 1; }
              100% { opacity: 0.4; }
            }
            .sig-broken { animation: sig-blink 1.2s ease-in-out infinite; }
            .train-moving { animation: train-pulse 1.5s ease-in-out infinite; transform-origin: center; transform-box: fill-box; }
            .dir-arrow-anim { animation: arrow-flow 1.2s ease-in-out infinite; }
          `}</style>
        </defs>

        {mode === 'overview' ? renderOverview() : renderStation()}
      </svg>
    </div>
  );
}
