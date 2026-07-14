import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import { Button, Segmented, Select, Tag, message } from 'antd';
import { DatabaseOutlined, LeftOutlined, NodeIndexOutlined, RightOutlined } from '@ant-design/icons';
import type { LineProfile, MovingAuthority, SignalAspect, TrainState } from '../../../types/signal';
import type { SelectedEntity } from './TrackDiagram';
import {
  buildStationTopologies,
  routeSegmentIds,
  type PhysicalSectionTopology,
  type StationTopology,
} from '../data/realTopology';
import { C, PLATFORM_CODES, STATION_NAMES, specOf } from '../data/teacherDiagramLayout';
import StationCoreTopologyCanvas from './StationCoreTopologyCanvas';

interface Props {
  lineProfile: LineProfile;
  stationId: string;
  trains: TrainState[];
  maMap: Record<string, MovingAuthority>;
  builtRouteIds: Set<number>;
  selectedEntity: SelectedEntity | null;
  onSelect: (entity: SelectedEntity | null) => void;
  onStationChange: (stationId: string) => void;
  onBuildRoute: (routeId: number, trainId?: string) => void;
  onCancelRoute: (routeId: number) => void;
}

interface StationCanvasProps {
  lineProfile: LineProfile;
  topology: StationTopology;
  rows: PhysicalSectionTopology[];
  pageIndex: number;
  pageCount: number;
  trains: TrainState[];
  maMap: Record<string, MovingAuthority>;
  openSignalIds: Set<number>;
  protectedSignalIds: Set<number>;
  lockedSegments: Set<number>;
  occupiedSegments: Set<number>;
  selectedEntity: SelectedEntity | null;
  routeStartSignalId?: number;
  routeEndSignalId?: number;
  onSelect: (entity: SelectedEntity | null) => void;
  onSignalClick: (signalId: number) => void;
  onPageChange: (page: number) => void;
}

const ROWS_PER_PAGE = 6;
const SVG_WIDTH = 1440;
const SVG_HEIGHT = 420;
const TRACK_LEFT = 164;
const TRACK_RIGHT = 1388;

function aspectColor(
  aspect: SignalAspect | null | undefined,
  routeOpen: boolean,
  routeProtected: boolean,
): string {
  if (routeOpen) return C.green;
  if (routeProtected) return C.red;
  if (aspect === 'GREEN' || aspect === 'GREEN_DARK') return C.green;
  if (aspect === 'YELLOW' || aspect === 'YELLOW_DARK' || aspect === 'RED_YELLOW') return C.yellow;
  if (aspect === 'WHITE') return '#f8fafc';
  if (aspect === 'BLUE') return '#38bdf8';
  return C.red;
}

function segmentLength(segment: { lengthCm: number; lengthM?: number }): number {
  return segment.lengthM ?? segment.lengthCm / 100;
}

function chunkRows(rows: PhysicalSectionTopology[]): PhysicalSectionTopology[][] {
  const result: PhysicalSectionTopology[][] = [];
  for (let index = 0; index < rows.length; index += ROWS_PER_PAGE) {
    result.push(rows.slice(index, index + ROWS_PER_PAGE));
  }
  return result.length ? result : [[]];
}

function stationLabel(topology: StationTopology): string {
  return STATION_NAMES[String(topology.station.id)] || topology.station.name;
}

function stationPageOptions(pages: PhysicalSectionTopology[][]) {
  return pages.map((rows, index) => ({
    value: index,
    label: `${index + 1}/${pages.length} · ${rows[0]?.name || '空'} - ${rows[rows.length - 1]?.name || '空'}`,
  }));
}

function StationCanvas({
  lineProfile,
  topology,
  rows,
  pageIndex,
  pageCount,
  trains,
  maMap,
  openSignalIds,
  protectedSignalIds,
  lockedSegments,
  occupiedSegments,
  selectedEntity,
  routeStartSignalId,
  routeEndSignalId,
  onSelect,
  onSignalClick,
  onPageChange,
}: StationCanvasProps) {
  const stationName = stationLabel(topology);
  const stationCode = specOf(String(topology.station.id))?.code || topology.station.name;
  const stationPlatformIds = new Set(topology.station.platformIds || []);
  const stationPlatforms = (lineProfile.platforms || []).filter((item) => stationPlatformIds.has(item.id));
  const platformCodes = PLATFORM_CODES[String(topology.station.id)] || ['A', 'B'];
  const segmentGeometry = new Map<number, { x1: number; x2: number; y: number }>();
  const rowGap = rows.length > 1 ? 280 / (rows.length - 1) : 0;

  rows.forEach((row, rowIndex) => {
    const y = rows.length > 1 ? 78 + rowIndex * rowGap : 210;
    const weights = row.segments.map((segment) => Math.max(1.5, Math.sqrt(segmentLength(segment))));
    const totalWeight = weights.reduce((sum, value) => sum + value, 0) || 1;
    let cursor = TRACK_LEFT;
    row.segments.forEach((segment, segmentIndex) => {
      const gap = segmentIndex < row.segments.length - 1 ? 5 : 0;
      const usableWidth = TRACK_RIGHT - TRACK_LEFT - Math.max(0, row.segments.length - 1) * 5;
      const width = usableWidth * (weights[segmentIndex] / totalWeight);
      const x1 = cursor;
      const x2 = segmentIndex === row.segments.length - 1 ? TRACK_RIGHT : x1 + width;
      segmentGeometry.set(Number(segment.id), { x1, x2, y });
      cursor = x2 + gap;
    });
  });

  const visibleSegmentIds = new Set(segmentGeometry.keys());
  const connections = rows.flatMap((row) => row.segments).flatMap((segment) => {
    const from = segmentGeometry.get(Number(segment.id));
    if (!from) return [];
    return [segment.forwardEndSegId, segment.sideEndSegId]
      .filter((targetId) => targetId !== 65535 && visibleSegmentIds.has(targetId))
      .map((targetId) => {
        const to = segmentGeometry.get(targetId)!;
        const fromX = Math.abs(to.x1 - from.x2) < Math.abs(to.x2 - from.x1) ? from.x2 : from.x1;
        const toX = fromX === from.x2 ? to.x1 : to.x2;
        return {
          key: `${segment.id}-${targetId}`,
          d: `M ${fromX} ${from.y} C ${(fromX + toX) / 2} ${from.y}, ${(fromX + toX) / 2} ${to.y}, ${toX} ${to.y}`,
          diverging: targetId === segment.sideEndSegId,
        };
      });
  });

  const switchesBySegment = new Map<number, StationTopology['switches']>();
  for (const item of topology.switches) {
    const hostId = [item.mergeSegId, item.normalSegId, item.reverseSegId].find((id) => visibleSegmentIds.has(id));
    if (hostId == null) continue;
    const list = switchesBySegment.get(hostId) || [];
    if (!list.some((current) => String(current.id) === String(item.id))) list.push(item);
    switchesBySegment.set(hostId, list);
  }

  return (
    <div className="station-slide-layout">
      <div className="station-slide-heading">
        <div className="min-w-0">
          <div className="flex items-baseline gap-2">
            <strong>站{topology.order} · {stationName}</strong>
            <span>{stationCode}</span>
            <em>K{(topology.station.positionM / 1000).toFixed(3)}</em>
          </div>
          <div className="station-control-lamps" aria-label="站控状态">
            {[
              ['中央控', C.controlOn],
              ['站控', C.controlOff],
              ['紧急站控', C.controlOff],
              ['联锁控', C.interlock],
            ].map(([label, color]) => (
              <span key={label}><i style={{ background: color }} />{label}</span>
            ))}
          </div>
        </div>
        <div className="station-slide-meta">
          <span>Seg <b>{topology.segments.length}</b></span>
          <span>区段 <b>{topology.physicalSections.length}</b></span>
          <span>信号 <b>{topology.signals.length}</b></span>
          <span>道岔 <b>{topology.switches.length}</b></span>
          <Select
            size="small"
            value={pageIndex}
            options={stationPageOptions(chunkRows(topology.physicalSections))}
            onChange={onPageChange}
            aria-label={`${stationName}区段分组`}
          />
        </div>
      </div>

      <div className="station-fixed-canvas">
        <svg viewBox={`0 0 ${SVG_WIDTH} ${SVG_HEIGHT}`} role="img" aria-label={`${stationName}站场线路图`}
          preserveAspectRatio="none">
          <rect width={SVG_WIDTH} height={SVG_HEIGHT} fill={C.bg} />
          {Array.from({ length: 15 }).map((_, index) => (
            <line key={`grid-${index}`} x1={index * 100} y1={0} x2={index * 100} y2={SVG_HEIGHT}
              stroke="#0d1928" strokeWidth={1} />
          ))}
          <rect x={18} y={18} width={112} height={26} fill="#0b1725" stroke="#26384d" />
          <text x={74} y={36} textAnchor="middle" fill="#a9bad0" fontSize={12}>区段组 {pageIndex + 1}/{pageCount}</text>
          <text x={1410} y={36} textAnchor="end" fill="#53657b" fontSize={11}>DOWN / UP</text>

          {connections.map((connection) => (
            <path key={connection.key} d={connection.d} fill="none"
              stroke={connection.diverging ? C.railBright : '#2f6f9f'}
              strokeWidth={connection.diverging ? 3 : 2} opacity={0.92} />
          ))}

          {rows.map((row) => {
            const first = segmentGeometry.get(Number(row.segments[0]?.id));
            if (!first) return null;
            return (
              <g key={row.id}>
                <text x={24} y={first.y - 2} fill={C.label} fontSize={13} fontWeight={700}
                  fontFamily="Consolas, monospace">{row.name}</text>
                <text x={126} y={first.y - 2} textAnchor="end" fill="#52657d" fontSize={10}>#{row.id}</text>
                <line x1={140} y1={first.y} x2={TRACK_RIGHT + 18} y2={first.y}
                  stroke="#142337" strokeWidth={1} />
                {row.segments.map((segment) => {
                  const id = Number(segment.id);
                  const geometry = segmentGeometry.get(id)!;
                  const width = geometry.x2 - geometry.x1;
                  const mid = (geometry.x1 + geometry.x2) / 2;
                  const occupied = occupiedSegments.has(id);
                  const locked = lockedSegments.has(id);
                  const stroke = occupied ? '#ff3131' : locked ? '#f5c842' : C.rail;
                  const signals = topology.signals.filter((signal) => signal.segId === id);
                  const switches = switchesBySegment.get(id) || [];
                  const platformIndex = stationPlatforms.findIndex((item) => item.segId === id);
                  return (
                    <g key={`${row.id}-${segment.id}`} data-segment-id={segment.id}>
                      <line x1={geometry.x1} y1={geometry.y} x2={geometry.x2} y2={geometry.y}
                        stroke={stroke} strokeWidth={occupied || locked ? 6 : 4} />
                      <text x={mid} y={geometry.y - 9} textAnchor="middle" fill="#7f91a8" fontSize={9}
                        fontFamily="Consolas, monospace">S{segment.id} · {segmentLength(segment).toFixed(0)}m</text>
                      {platformIndex >= 0 && (
                        <g>
                          <rect x={mid - 34} y={geometry.y + 8} width={68} height={19}
                            fill={C.platform} stroke={C.platformEdge} />
                          <rect x={mid - 34} y={geometry.y + 8} width={68} height={4} fill={C.green} />
                          <text x={mid} y={geometry.y + 23} textAnchor="middle" fill="#101010"
                            fontSize={10} fontWeight={800}>{platformCodes[platformIndex] || `P${stationPlatforms[platformIndex].id}`}</text>
                        </g>
                      )}
                      {signals.map((signal, signalIndex) => {
                        const ratio = Math.max(0.08, Math.min(0.92,
                          (signal.offsetCm / 100) / Math.max(1, segmentLength(segment))));
                        const x = geometry.x1 + ratio * width + signalIndex * 3;
                        const above = signal.protectDir !== 85;
                        const y = geometry.y + (above ? -25 : 25);
                        const color = aspectColor(signal.aspect,
                          openSignalIds.has(signal.id), protectedSignalIds.has(signal.id));
                        const selected = selectedEntity?.type === 'signal' && Number(selectedEntity.id) === signal.id;
                        const routeSelected = routeStartSignalId === signal.id || routeEndSignalId === signal.id;
                        return (
                          <g key={signal.id} className="il-hit" data-signal-id={signal.id}
                            data-route-role={routeStartSignalId === signal.id ? 'start' : routeEndSignalId === signal.id ? 'end' : 'none'}
                            aria-label={`选择信号机 ${signal.name}`} transform={`translate(${x}, ${y})`}
                            onClick={(event) => { event.stopPropagation(); onSignalClick(signal.id); }}
                            onContextMenu={(event) => {
                              event.preventDefault();
                              event.stopPropagation();
                              onSelect({ type: 'signal', id: signal.id });
                            }}>
                            <rect x={-11} y={-12} width={Math.max(38, signal.name.length * 7 + 22)} height={24}
                              fill="transparent" pointerEvents="all" />
                            <line x1={0} y1={above ? 7 : -7} x2={0} y2={above ? 24 : -24}
                              stroke="#e5e7eb" strokeWidth={1} />
                            {routeSelected && <circle r={10} fill="none"
                              stroke={routeStartSignalId === signal.id ? '#60a5fa' : '#22d3ee'} strokeWidth={2} />}
                            <circle r={selected ? 7 : 5.5} fill={color} stroke="#f8fafc" strokeWidth={1} />
                            <text x={8} y={3} fill={color} fontSize={9} fontWeight={700}>{signal.name}</text>
                          </g>
                        );
                      })}
                      {switches.slice(0, 2).map((item, switchIndex) => {
                        const x = mid + (switchIndex - 0.5) * 20;
                        const selected = selectedEntity?.type === 'switch' && String(selectedEntity.id) === String(item.id);
                        return (
                          <g key={`${item.id}-${id}`} className="il-hit" data-switch-id={item.id}
                            onClick={(event) => { event.stopPropagation(); onSelect({ type: 'switch', id: String(item.id) }); }}>
                            <path d={`M ${x - 8} ${geometry.y} L ${x} ${geometry.y - 8} L ${x + 8} ${geometry.y} L ${x} ${geometry.y + 8} Z`}
                              fill={item.state === 'REVERSE' ? '#f59e0b' : C.green}
                              stroke={selected ? '#fff' : '#07111d'} strokeWidth={selected ? 2 : 1} />
                            <text x={x} y={geometry.y + 21} textAnchor="middle" fill="#d6dce6" fontSize={8}>W{item.id}</text>
                          </g>
                        );
                      })}
                    </g>
                  );
                })}
              </g>
            );
          })}

          {trains.slice(0, 5).map((train, index) => {
            const ma = maMap[train.trainId];
            return (
              <g key={train.trainId} transform={`translate(${1160 + (index % 2) * 112}, ${18 + Math.floor(index / 2) * 30})`}
                className="il-hit" onClick={() => onSelect({ type: 'train', id: train.trainId })}>
                <rect width={102} height={23} fill="#071e2b" stroke={C.train} />
                <text x={8} y={15} fill={C.train} fontSize={10} fontWeight={800}>{train.trainId}</text>
                <text x={94} y={15} textAnchor="end" fill={ma ? C.green : C.muted} fontSize={9}>
                  {ma ? `MA ${ma.maxSpeedKmh.toFixed(0)}` : `${train.speedKmh.toFixed(0)}km/h`}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
    </div>
  );
}

export default function StationTopologyDetail({
  lineProfile,
  stationId,
  trains,
  maMap,
  builtRouteIds,
  selectedEntity,
  onSelect,
  onStationChange,
  onBuildRoute,
  onCancelRoute,
}: Props) {
  const [routeStartSignalId, setRouteStartSignalId] = useState<number>();
  const [routeEndSignalId, setRouteEndSignalId] = useState<number>();
  const [routeTrainId, setRouteTrainId] = useState<string>();
  const [pageByStation, setPageByStation] = useState<Record<string, number>>({});
  const [diagramMode, setDiagramMode] = useState<'core' | 'sections'>('core');
  const carouselRef = useRef<HTMLDivElement>(null);
  const scrollTimer = useRef<number | undefined>(undefined);
  const dragging = useRef<{
    pointerId: number;
    startX: number;
    scrollLeft: number;
    moved: boolean;
  } | undefined>(undefined);
  const topologies = useMemo(() => buildStationTopologies(lineProfile), [lineProfile]);
  const topology = topologies.find((item) => String(item.station.id) === String(stationId));
  const activeIndex = Math.max(0, topologies.findIndex((item) => String(item.station.id) === String(stationId)));

  useEffect(() => {
    setRouteStartSignalId(undefined);
    setRouteEndSignalId(undefined);
  }, [stationId]);

  useEffect(() => {
    if (scrollTimer.current) {
      window.clearTimeout(scrollTimer.current);
      scrollTimer.current = undefined;
    }
    const frame = window.requestAnimationFrame(() => {
      const node = carouselRef.current;
      if (!node || dragging.current || node.clientWidth === 0) return;
      const target = activeIndex * node.clientWidth;
      if (Math.abs(node.scrollLeft - target) > 2) node.scrollLeft = target;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [activeIndex]);

  useEffect(() => () => {
    if (scrollTimer.current) window.clearTimeout(scrollTimer.current);
  }, []);

  const openSignalIds = useMemo(() => {
    const result = new Set<number>();
    for (const route of lineProfile.routes || []) {
      if (builtRouteIds.has(Number(route.id))) result.add(route.startSignalId);
    }
    return result;
  }, [lineProfile.routes, builtRouteIds]);

  const protectedSignalIds = useMemo(() => {
    const result = new Set<number>();
    for (const route of lineProfile.routes || []) {
      if (builtRouteIds.has(Number(route.id))) result.add(route.endSignalId);
    }
    return result;
  }, [lineProfile.routes, builtRouteIds]);

  const lockedSegments = useMemo(() => {
    const result = new Set<number>();
    for (const route of lineProfile.routes || []) {
      if (!builtRouteIds.has(Number(route.id))) continue;
      for (const id of routeSegmentIds(lineProfile, Number(route.id))) result.add(id);
    }
    return result;
  }, [lineProfile, builtRouteIds]);

  const occupiedSegments = useMemo(() => {
    const result = new Set<number>();
    for (const axle of lineProfile.axleSections || []) {
      if (!axle.occupied) continue;
      for (const id of axle.segIds || []) result.add(Number(id));
      if (axle.segId != null) result.add(Number(axle.segId));
    }
    for (const train of trains) {
      if (!Number.isFinite(train.positionM)) continue;
      for (const [id, range] of Object.entries(lineProfile.segmentMileage || {})) {
        if (train.positionM >= range[0] && train.positionM <= range[1]) result.add(Number(id));
      }
    }
    return result;
  }, [lineProfile.axleSections, lineProfile.segmentMileage, trains]);

  if (!topology) {
    return <div className="flex h-full items-center justify-center text-sm text-slate-500">本站拓扑数据不可用</div>;
  }

  const stationRoutes = (lineProfile.routes || []).filter((route) => {
    const start = lineProfile.signals.find((signal) => signal.id === route.startSignalId);
    const end = lineProfile.signals.find((signal) => signal.id === route.endSignalId);
    return Boolean(start && end && topology.segmentIds.has(start.segId) && topology.segmentIds.has(end.segId));
  });
  const matchedRoute = stationRoutes.find((route) =>
    route.startSignalId === routeStartSignalId && route.endSignalId === routeEndSignalId);
  const builtStationRoutes = stationRoutes.filter((route) => builtRouteIds.has(Number(route.id)));

  const handleSignalClick = (signalId: number) => {
    if (routeStartSignalId == null || routeEndSignalId != null) {
      setRouteStartSignalId(signalId);
      setRouteEndSignalId(undefined);
    } else if (routeStartSignalId === signalId) {
      setRouteStartSignalId(undefined);
    } else {
      setRouteEndSignalId(signalId);
    }
  };

  const goToStation = useCallback((index: number) => {
    const nextIndex = Math.max(0, Math.min(topologies.length - 1, index));
    const next = topologies[nextIndex];
    const node = carouselRef.current;
    if (!next || !node) return;
    if (scrollTimer.current) {
      window.clearTimeout(scrollTimer.current);
      scrollTimer.current = undefined;
    }
    node.scrollTo({ left: nextIndex * node.clientWidth, behavior: 'smooth' });
    onStationChange(String(next.station.id));
  }, [onStationChange, topologies]);

  const handleScroll = () => {
    if (scrollTimer.current) window.clearTimeout(scrollTimer.current);
    scrollTimer.current = window.setTimeout(() => {
      const node = carouselRef.current;
      if (!node || node.clientWidth === 0) return;
      const index = Math.max(0, Math.min(topologies.length - 1, Math.round(node.scrollLeft / node.clientWidth)));
      const id = String(topologies[index]?.station.id || '');
      if (id && id !== String(stationId)) onStationChange(id);
    }, 120);
  };

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    const target = event.target as Element;
    if (target.closest('.il-hit, button, input, .ant-select, .station-slide-meta')) return;
    dragging.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      scrollLeft: event.currentTarget.scrollLeft,
      moved: false,
    };
    event.currentTarget.setPointerCapture(event.pointerId);
    event.currentTarget.classList.add('is-dragging');
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const state = dragging.current;
    if (!state || state.pointerId !== event.pointerId) return;
    const delta = event.clientX - state.startX;
    if (Math.abs(delta) > 4) state.moved = true;
    event.currentTarget.scrollLeft = state.scrollLeft - delta;
  };

  const finishPointer = (event: ReactPointerEvent<HTMLDivElement>) => {
    const state = dragging.current;
    if (!state || state.pointerId !== event.pointerId) return;
    event.currentTarget.classList.remove('is-dragging');
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    dragging.current = undefined;
    const index = Math.max(0, Math.min(topologies.length - 1,
      Math.round(event.currentTarget.scrollLeft / Math.max(1, event.currentTarget.clientWidth))));
    goToStation(index);
  };

  return (
    <section className="station-topology-detail flex h-full min-h-0 flex-col bg-black">
      <nav className="station-sequence" aria-label="1至13号站横向导航">
        <Button type="text" size="small" icon={<LeftOutlined />} title="上一站"
          disabled={activeIndex === 0} onClick={() => goToStation(activeIndex - 1)} />
        <div className="station-sequence-line">
          {topologies.map((item) => {
            const id = String(item.station.id);
            const active = id === String(stationId);
            return (
              <button key={id} type="button" className={active ? 'active' : ''}
                aria-label={`站${item.order} ${stationLabel(item)}`} aria-current={active ? 'page' : undefined}
                onClick={() => goToStation(topologies.indexOf(item))}>
                <span>{item.order}</span><small>{stationLabel(item)}</small>
              </button>
            );
          })}
        </div>
        <Button type="text" size="small" icon={<RightOutlined />} title="下一站"
          disabled={activeIndex === topologies.length - 1} onClick={() => goToStation(activeIndex + 1)} />
      </nav>

      <div className="station-operation-bar flex shrink-0 flex-wrap items-center justify-between gap-2 px-3 py-2">
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="text-slate-500">进路选排</span>
          <Tag color={routeStartSignalId ? 'blue' : 'default'}>
            始端 {lineProfile.signals.find((signal) => signal.id === routeStartSignalId)?.name || '点击信号机'}
          </Tag>
          <span className="text-slate-600">→</span>
          <Tag color={routeEndSignalId ? 'cyan' : 'default'}>
            终端 {lineProfile.signals.find((signal) => signal.id === routeEndSignalId)?.name || '点击信号机'}
          </Tag>
          {routeStartSignalId && routeEndSignalId && (
            matchedRoute ? <Tag color="green">匹配 {matchedRoute.name || `#${matchedRoute.id}`}</Tag>
              : <Tag color="red">无匹配真实进路</Tag>
          )}
          <Select size="small" allowClear style={{ width: 110 }} placeholder="绑定列车"
            options={trains.map((train) => ({ value: train.trainId, label: train.trainId }))}
            value={routeTrainId} onChange={setRouteTrainId} />
          <Button size="small" type="primary" disabled={!matchedRoute} onClick={() => {
            if (!matchedRoute) {
              message.warning('请按顺序选择有效的始端和终端信号机');
              return;
            }
            onBuildRoute(Number(matchedRoute.id), routeTrainId);
          }}>办理进路</Button>
          <Button size="small" onClick={() => {
            setRouteStartSignalId(undefined);
            setRouteEndSignalId(undefined);
          }}>重选</Button>
          {builtStationRoutes.map((route) => (
            <Tag key={String(route.id)} color="gold" closable onClose={(event) => {
              event.preventDefault();
              onCancelRoute(Number(route.id));
            }}>已锁闭 {route.name || `#${route.id}`}</Tag>
          ))}
        </div>
        <div className="station-operation-tools">
          <Segmented
            size="small"
            aria-label="站场图模式"
            value={diagramMode}
            options={[
              { value: 'core', label: '主图', icon: <NodeIndexOutlined /> },
              { value: 'sections', label: '区段', icon: <DatabaseOutlined /> },
            ]}
            onChange={(value) => setDiagramMode(value as 'core' | 'sections')}
          />
        </div>
      </div>

      <div ref={carouselRef} className="station-carousel" onScroll={handleScroll}
        onPointerDown={handlePointerDown} onPointerMove={handlePointerMove}
        onPointerUp={finishPointer} onPointerCancel={finishPointer}>
        {topologies.map((item, itemIndex) => {
          const id = String(item.station.id);
          const pages = chunkRows(item.physicalSections);
          const pageIndex = Math.min(pages.length - 1, pageByStation[id] || 0);
          const shouldRender = Math.abs(itemIndex - activeIndex) <= 1;
          return (
            <article key={id} className="station-slide" data-station-id={id}
              aria-label={`站${item.order} ${stationLabel(item)}详图`}>
              {shouldRender && diagramMode === 'core' ? (
                <StationCoreTopologyCanvas lineProfile={lineProfile} topology={item}
                  trains={trains} maMap={maMap} openSignalIds={openSignalIds}
                  protectedSignalIds={protectedSignalIds} lockedSegments={lockedSegments}
                  occupiedSegments={occupiedSegments} selectedEntity={selectedEntity}
                  routeStartSignalId={routeStartSignalId} routeEndSignalId={routeEndSignalId}
                  onSelect={onSelect} onSignalClick={handleSignalClick} />
              ) : shouldRender ? (
                <StationCanvas lineProfile={lineProfile} topology={item} rows={pages[pageIndex]}
                  pageIndex={pageIndex} pageCount={pages.length} trains={trains} maMap={maMap}
                  openSignalIds={openSignalIds} protectedSignalIds={protectedSignalIds}
                  lockedSegments={lockedSegments} occupiedSegments={occupiedSegments}
                  selectedEntity={selectedEntity} routeStartSignalId={routeStartSignalId}
                  routeEndSignalId={routeEndSignalId} onSelect={onSelect} onSignalClick={handleSignalClick}
                  onPageChange={(page) => setPageByStation((previous) => ({ ...previous, [id]: page }))} />
              ) : (
                <div className="station-slide-placeholder" aria-hidden="true" />
              )}
            </article>
          );
        })}
      </div>

      <footer className="station-detail-legend">
        <div>
          <span><i className="legend-line bg-[#3d8fd1]" />空闲 Seg</span>
          <span><i className="legend-line bg-[#f5c842]" />进路锁闭</span>
          <span><i className="legend-line bg-[#ff3131]" />列车占用</span>
          <span><i className="legend-dot bg-[#00ff40]" />信号开放 / 道岔定位</span>
          <span><i className="legend-dot bg-[#ff2020]" />信号防护</span>
        </div>
        <b>{activeIndex + 1} / {topologies.length}</b>
      </footer>
    </section>
  );
}
