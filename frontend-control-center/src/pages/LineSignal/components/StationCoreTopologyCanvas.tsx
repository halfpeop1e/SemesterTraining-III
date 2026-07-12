import { useMemo } from 'react';
import type {
  LineProfile,
  MovingAuthority,
  SignalAspect,
  Switch,
  TrackSegment,
  TrainState,
} from '../../../types/signal';
import type { SelectedEntity } from './TrackDiagram';
import type { StationTopology } from '../data/realTopology';
import { C, PLATFORM_CODES, STATION_NAMES, specOf } from '../data/teacherDiagramLayout';

interface Props {
  lineProfile: LineProfile;
  topology: StationTopology;
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
}

type Lane = 'DOWN' | 'UP';
type Endpoint = 'start' | 'end';

interface Point {
  x: number;
  y: number;
}

interface SegmentGeometry {
  id: number;
  start: Point;
  end: Point;
  lane?: Lane;
  branch: boolean;
}

interface SwitchGeometry {
  item: Switch;
  root: string;
  point: Point;
}

interface StationSchematic {
  segmentGeometry: Map<number, SegmentGeometry>;
  switchGeometry: SwitchGeometry[];
  platforms: Array<{ id: number; segId: number; dir: number; label: string }>;
  mainSegmentIds: Set<number>;
}

const NONE_ID = 65535;
const SVG_WIDTH = 1440;
const SVG_HEIGHT = 420;
const TRACK_LEFT = 126;
const TRACK_RIGHT = 1368;
const TRACK_CENTER = (TRACK_LEFT + TRACK_RIGHT) / 2;
const DOWN_Y = 142;
const UP_Y = 292;

function segmentLengthM(segment: TrackSegment): number {
  return segment.lengthM ?? segment.lengthCm / 100;
}

function segmentWeight(segment: TrackSegment): number {
  return Math.max(2.4, Math.min(16, Math.sqrt(Math.max(1, segmentLengthM(segment)))));
}

function endpointKey(id: number, endpoint: Endpoint): string {
  return `${id}:${endpoint === 'start' ? 's' : 'e'}`;
}

function startNeighbors(segment: TrackSegment): number[] {
  return [segment.forwardStartSegId, segment.sideStartSegId].filter((id) => id !== NONE_ID);
}

function endNeighbors(segment: TrackSegment): number[] {
  return [segment.forwardEndSegId, segment.sideEndSegId].filter((id) => id !== NONE_ID);
}

function allNeighbors(segment: TrackSegment): number[] {
  return [...startNeighbors(segment), ...endNeighbors(segment)];
}

class UnionFind {
  private readonly parent = new Map<string, string>();

  add(value: string) {
    if (!this.parent.has(value)) this.parent.set(value, value);
  }

  find(value: string): string {
    this.add(value);
    const parent = this.parent.get(value)!;
    if (parent === value) return value;
    const root = this.find(parent);
    this.parent.set(value, root);
    return root;
  }

  union(a: string, b: string) {
    const rootA = this.find(a);
    const rootB = this.find(b);
    if (rootA !== rootB) this.parent.set(rootB, rootA);
  }
}

function referencedEndpoint(segment: TrackSegment, neighborId: number): Endpoint | null {
  if (startNeighbors(segment).includes(neighborId)) return 'start';
  if (endNeighbors(segment).includes(neighborId)) return 'end';
  return null;
}

function traceMainChain(
  platformSegId: number,
  byId: Map<number, TrackSegment>,
  allowed: Set<number>,
): TrackSegment[] {
  const platform = byId.get(platformSegId);
  if (!platform) return [];
  const before: TrackSegment[] = [];
  const after: TrackSegment[] = [];
  const visited = new Set<number>([platformSegId]);

  let cursor = platform;
  while (allowed.has(cursor.forwardStartSegId) && !visited.has(cursor.forwardStartSegId)) {
    const previous = byId.get(cursor.forwardStartSegId);
    if (!previous) break;
    before.unshift(previous);
    visited.add(Number(previous.id));
    cursor = previous;
  }

  cursor = platform;
  while (allowed.has(cursor.forwardEndSegId) && !visited.has(cursor.forwardEndSegId)) {
    const next = byId.get(cursor.forwardEndSegId);
    if (!next) break;
    after.push(next);
    visited.add(Number(next.id));
    cursor = next;
  }
  return [...before, platform, ...after];
}

function setPoint(
  map: Map<string, Point>,
  rootsByLane: Map<string, Lane>,
  root: string,
  point: Point,
  lane: Lane,
) {
  const current = map.get(root);
  if (!current) {
    map.set(root, point);
    rootsByLane.set(root, lane);
    return;
  }
  map.set(root, { x: (current.x + point.x) / 2, y: (current.y + point.y) / 2 });
}

function shortestAnchor(
  origin: string,
  lane: Lane,
  graph: Map<string, Set<string>>,
  points: Map<string, Point>,
  rootsByLane: Map<string, Lane>,
): { root: string; distance: number } | null {
  const queue: Array<{ root: string; distance: number }> = [{ root: origin, distance: 0 }];
  const visited = new Set<string>([origin]);
  for (let index = 0; index < queue.length; index += 1) {
    const current = queue[index];
    if (current.root !== origin && points.has(current.root) && rootsByLane.get(current.root) === lane) {
      return current;
    }
    for (const next of graph.get(current.root) || []) {
      if (visited.has(next)) continue;
      visited.add(next);
      queue.push({ root: next, distance: current.distance + 1 });
    }
  }
  return null;
}

function buildStationSchematic(lineProfile: LineProfile, topology: StationTopology): StationSchematic {
  const byId = new Map<number, TrackSegment>(
    topology.segments.map((segment) => [Number(segment.id), segment]),
  );
  const allowed = new Set(byId.keys());
  const stationPlatformIds = new Set(topology.station.platformIds || []);
  const stationPlatforms = (lineProfile.platforms || [])
    .filter((platform) => stationPlatformIds.has(platform.id) && allowed.has(platform.segId));
  const downPlatform = stationPlatforms.find((platform) => platform.dir === 0xAA) || stationPlatforms[0];
  const upPlatform = stationPlatforms.find((platform) => platform.dir === 0x55) ||
    stationPlatforms.find((platform) => platform.id !== downPlatform?.id) || stationPlatforms[1];

  const laneEntries: Array<{ lane: Lane; y: number; platformSegId: number; chain: TrackSegment[] }> = [];
  if (downPlatform) {
    laneEntries.push({
      lane: 'DOWN',
      y: DOWN_Y,
      platformSegId: downPlatform.segId,
      chain: traceMainChain(downPlatform.segId, byId, allowed),
    });
  }
  if (upPlatform && upPlatform.id !== downPlatform?.id) {
    laneEntries.push({
      lane: 'UP',
      y: UP_Y,
      platformSegId: upPlatform.segId,
      chain: traceMainChain(upPlatform.segId, byId, allowed),
    });
  }

  const union = new UnionFind();
  for (const segment of topology.segments) {
    const id = Number(segment.id);
    union.add(endpointKey(id, 'start'));
    union.add(endpointKey(id, 'end'));
  }
  for (const segment of topology.segments) {
    const id = Number(segment.id);
    for (const neighborId of startNeighbors(segment)) {
      const neighbor = byId.get(neighborId);
      if (!neighbor) continue;
      const endpoint = referencedEndpoint(neighbor, id);
      if (endpoint) union.union(endpointKey(id, 'start'), endpointKey(neighborId, endpoint));
    }
    for (const neighborId of endNeighbors(segment)) {
      const neighbor = byId.get(neighborId);
      if (!neighbor) continue;
      const endpoint = referencedEndpoint(neighbor, id);
      if (endpoint) union.union(endpointKey(id, 'end'), endpointKey(neighborId, endpoint));
    }
  }

  const maxWeight = Math.max(1, ...laneEntries.map((entry) =>
    entry.chain.reduce((sum, segment) => sum + segmentWeight(segment), 0)));
  const scale = (TRACK_RIGHT - TRACK_LEFT - 50) / maxWeight;
  const segmentGeometry = new Map<number, SegmentGeometry>();
  const mainSegmentIds = new Set<number>();
  const nodePoints = new Map<string, Point>();
  const rootsByLane = new Map<string, Lane>();

  for (const entry of laneEntries) {
    const weights = entry.chain.map(segmentWeight);
    const platformIndex = entry.chain.findIndex((segment) => Number(segment.id) === entry.platformSegId);
    const beforeWeight = weights.slice(0, Math.max(0, platformIndex)).reduce((sum, value) => sum + value, 0);
    const platformWeight = weights[Math.max(0, platformIndex)] || 0;
    let cursor = TRACK_CENTER - (beforeWeight + platformWeight / 2) * scale;
    entry.chain.forEach((segment, index) => {
      const id = Number(segment.id);
      const width = weights[index] * scale;
      const start = { x: cursor, y: entry.y };
      const end = { x: cursor + width, y: entry.y };
      segmentGeometry.set(id, { id, start, end, lane: entry.lane, branch: false });
      mainSegmentIds.add(id);
      setPoint(nodePoints, rootsByLane, union.find(endpointKey(id, 'start')), start, entry.lane);
      setPoint(nodePoints, rootsByLane, union.find(endpointKey(id, 'end')), end, entry.lane);
      cursor += width;
    });
  }

  const segmentDistance = new Map<number, number>();
  const segmentQueue = [...mainSegmentIds];
  segmentQueue.forEach((id) => segmentDistance.set(id, 0));
  for (let index = 0; index < segmentQueue.length; index += 1) {
    const id = segmentQueue[index];
    const distance = segmentDistance.get(id) || 0;
    if (distance >= 3) continue;
    const segment = byId.get(id);
    if (!segment) continue;
    for (const next of allNeighbors(segment)) {
      if (!allowed.has(next) || segmentDistance.has(next)) continue;
      segmentDistance.set(next, distance + 1);
      segmentQueue.push(next);
    }
  }
  const visibleSegmentIds = new Set<number>([
    ...mainSegmentIds,
    ...[...segmentDistance.entries()].filter(([, distance]) => distance <= 3).map(([id]) => id),
  ]);

  const nodeGraph = new Map<string, Set<string>>();
  const addNodeEdge = (a: string, b: string) => {
    if (!nodeGraph.has(a)) nodeGraph.set(a, new Set());
    if (!nodeGraph.has(b)) nodeGraph.set(b, new Set());
    nodeGraph.get(a)!.add(b);
    nodeGraph.get(b)!.add(a);
  };
  for (const id of visibleSegmentIds) {
    addNodeEdge(
      union.find(endpointKey(id, 'start')),
      union.find(endpointKey(id, 'end')),
    );
  }

  const allRoots = new Set<string>();
  for (const id of visibleSegmentIds) {
    allRoots.add(union.find(endpointKey(id, 'start')));
    allRoots.add(union.find(endpointKey(id, 'end')));
  }
  for (const root of allRoots) {
    if (nodePoints.has(root)) continue;
    const down = shortestAnchor(root, 'DOWN', nodeGraph, nodePoints, rootsByLane);
    const up = shortestAnchor(root, 'UP', nodeGraph, nodePoints, rootsByLane);
    if (down && up) {
      const downPoint = nodePoints.get(down.root)!;
      const upPoint = nodePoints.get(up.root)!;
      const total = Math.max(1, down.distance + up.distance);
      const towardUp = down.distance / total;
      nodePoints.set(root, {
        x: downPoint.x * (1 - towardUp) + upPoint.x * towardUp,
        y: downPoint.y * (1 - towardUp) + upPoint.y * towardUp,
      });
      continue;
    }
    const anchor = down || up;
    if (anchor) {
      const point = nodePoints.get(anchor.root)!;
      const numeric = Number(root.split(':')[0]) || 0;
      const direction = numeric % 2 === 0 ? 1 : -1;
      const vertical = (anchor.distance || 1) * 34;
      nodePoints.set(root, {
        x: Math.max(TRACK_LEFT, Math.min(TRACK_RIGHT, point.x + direction * vertical * 1.4)),
        y: Math.max(62, Math.min(365, point.y + direction * vertical)),
      });
    }
  }

  for (const id of visibleSegmentIds) {
    if (segmentGeometry.has(id)) continue;
    const source = byId.get(id);
    const sourceStart = nodePoints.get(union.find(endpointKey(id, 'start')));
    const sourceEnd = nodePoints.get(union.find(endpointKey(id, 'end')));
    if (!source || !sourceStart || !sourceEnd) continue;
    let start = { ...sourceStart };
    let end = { ...sourceEnd };
    if (Math.hypot(start.x - end.x, start.y - end.y) < 2) {
      const startContinues = startNeighbors(source).some((neighbor) => visibleSegmentIds.has(neighbor));
      const endContinues = endNeighbors(source).some((neighbor) => visibleSegmentIds.has(neighbor));
      const span = Math.max(54, Math.min(105, segmentWeight(source) * 8));
      if (startContinues && !endContinues) {
        end = { x: Math.min(TRACK_RIGHT, start.x + span), y: start.y };
      } else if (endContinues && !startContinues) {
        start = { x: Math.max(TRACK_LEFT, end.x - span), y: end.y };
      } else {
        end = { x: Math.min(TRACK_RIGHT, start.x + span), y: start.y + 22 };
      }
    }
    segmentGeometry.set(id, { id, start, end, branch: true });
  }

  const switchGeometry: SwitchGeometry[] = [];
  for (const item of topology.switches) {
    const endpointRoots = [item.mergeSegId, item.normalSegId, item.reverseSegId]
      .filter((id) => allowed.has(id))
      .flatMap((id) => [
        union.find(endpointKey(id, 'start')),
        union.find(endpointKey(id, 'end')),
      ]);
    const counts = new Map<string, number>();
    endpointRoots.forEach((root) => counts.set(root, (counts.get(root) || 0) + 1));
    const root = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
    const point = root ? nodePoints.get(root) : undefined;
    if (root && point && counts.get(root)! >= 2) switchGeometry.push({ item, root, point });
  }

  const codes = PLATFORM_CODES[String(topology.station.id)] || ['A', 'B'];
  const platforms = stationPlatforms.map((platform) => ({
    id: platform.id,
    segId: platform.segId,
    dir: platform.dir,
    label: codes[(topology.station.platformIds || []).indexOf(platform.id)] || `P${platform.id}`,
  }));
  return { segmentGeometry, switchGeometry, platforms, mainSegmentIds };
}

function aspectColor(aspect: SignalAspect | null | undefined, open: boolean, protectedSignal: boolean) {
  if (open) return C.green;
  if (protectedSignal) return C.red;
  if (aspect === 'GREEN' || aspect === 'GREEN_DARK') return C.green;
  if (aspect === 'YELLOW' || aspect === 'YELLOW_DARK' || aspect === 'RED_YELLOW') return C.yellow;
  if (aspect === 'WHITE') return '#f8fafc';
  if (aspect === 'BLUE') return '#38bdf8';
  return C.red;
}

function pointAlong(geometry: SegmentGeometry, ratio: number): Point {
  return {
    x: geometry.start.x + (geometry.end.x - geometry.start.x) * ratio,
    y: geometry.start.y + (geometry.end.y - geometry.start.y) * ratio,
  };
}

function segmentOtherPoint(geometry: SegmentGeometry, junction: Point, item: Switch): Point | null {
  const ids = [item.mergeSegId, item.normalSegId, item.reverseSegId];
  if (!ids.includes(geometry.id)) return null;
  const startDistance = Math.hypot(geometry.start.x - junction.x, geometry.start.y - junction.y);
  const endDistance = Math.hypot(geometry.end.x - junction.x, geometry.end.y - junction.y);
  return startDistance > endDistance ? geometry.start : geometry.end;
}

export default function StationCoreTopologyCanvas({
  lineProfile,
  topology,
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
}: Props) {
  const schematic = useMemo(
    () => buildStationSchematic(lineProfile, topology),
    [lineProfile, topology],
  );
  const stationName = STATION_NAMES[String(topology.station.id)] || topology.station.name;
  const stationCode = specOf(String(topology.station.id))?.code || topology.station.name;
  const sectionNameBySegment = new Map<number, string>();
  topology.physicalSections.forEach((section) => {
    section.segments.forEach((segment) => {
      if (!sectionNameBySegment.has(Number(segment.id))) {
        sectionNameBySegment.set(Number(segment.id), section.name);
      }
    });
  });
  const laneExtents = (['DOWN', 'UP'] as const).map((lane) => {
    const geometries = [...schematic.segmentGeometry.values()]
      .filter((geometry) => geometry.lane === lane);
    return {
      lane,
      y: lane === 'DOWN' ? DOWN_Y : UP_Y,
      minX: geometries.length ? Math.min(...geometries.map((geometry) => geometry.start.x)) : TRACK_CENTER,
      maxX: geometries.length ? Math.max(...geometries.map((geometry) => geometry.end.x)) : TRACK_CENTER,
    };
  });

  return (
    <div className="station-slide-layout station-core-layout">
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
          <span>主线 Seg <b>{schematic.mainSegmentIds.size}</b></span>
          <span>站台 <b>{schematic.platforms.length}</b></span>
          <span>信号 <b>{topology.signals.length}</b></span>
          <span>道岔 <b>{topology.switches.length}</b></span>
          <span className="station-topology-source">XLS 端点拓扑</span>
        </div>
      </div>

      <div className="station-fixed-canvas station-core-canvas">
        <svg viewBox={`0 0 ${SVG_WIDTH} ${SVG_HEIGHT}`} preserveAspectRatio="none"
          role="img" aria-label={`${stationName}真实站场拓扑图`}>
          <rect width={SVG_WIDTH} height={SVG_HEIGHT} fill={C.bg} />
          {Array.from({ length: 15 }).map((_, index) => (
            <line key={index} x1={index * 100} y1={0} x2={index * 100} y2={SVG_HEIGHT}
              stroke="#0d1928" strokeWidth={1} />
          ))}
          <text x={28} y={DOWN_Y - 14} fill="#7f91a8" fontSize={12} fontWeight={700}>下行 0xAA</text>
          <text x={28} y={UP_Y + 22} fill="#7f91a8" fontSize={12} fontWeight={700}>上行 0x55</text>
          <text x={1410} y={31} textAnchor="end" fill="#53657b" fontSize={11}>
            主线路模式 · 非几何比例
          </text>

          {laneExtents.map((lane) => (
            <g key={lane.lane} opacity={0.58}>
              <line x1={TRACK_LEFT} y1={lane.y} x2={lane.minX} y2={lane.y}
                stroke={C.rail} strokeWidth={4} strokeDasharray="10 7" />
              <line x1={lane.maxX} y1={lane.y} x2={TRACK_RIGHT} y2={lane.y}
                stroke={C.rail} strokeWidth={4} strokeDasharray="10 7" />
            </g>
          ))}

          {[...schematic.segmentGeometry.values()]
            .sort((a, b) => Number(a.branch) - Number(b.branch))
            .map((geometry) => {
              const occupied = occupiedSegments.has(geometry.id);
              const locked = lockedSegments.has(geometry.id);
              const stroke = occupied ? '#ff3131' : locked ? '#f5c842' :
                geometry.branch ? C.railBright : C.rail;
              const middle = pointAlong(geometry, 0.5);
              const name = sectionNameBySegment.get(geometry.id) || `Seg ${geometry.id}`;
              return (
                <g key={geometry.id} data-segment-id={geometry.id}>
                  <line x1={geometry.start.x} y1={geometry.start.y}
                    x2={geometry.end.x} y2={geometry.end.y}
                    stroke={stroke} strokeWidth={occupied || locked ? 7 : geometry.branch ? 3 : 5}
                    strokeLinecap="round" />
                  <circle cx={geometry.start.x} cy={geometry.start.y} r={2.2} fill="#b6c7da" />
                  <text x={middle.x} y={middle.y - 9} textAnchor="middle"
                    fill={geometry.branch ? '#7fa9cc' : '#9bb2c9'} fontSize={9}
                    fontFamily="Consolas, monospace">
                    {name} · S{geometry.id}
                  </text>
                </g>
              );
            })}

          {schematic.platforms.map((platform) => {
            const geometry = schematic.segmentGeometry.get(platform.segId);
            if (!geometry) return null;
            const center = pointAlong(geometry, 0.5);
            const top = platform.dir === 0xAA;
            const width = Math.max(78, Math.min(150, Math.abs(geometry.end.x - geometry.start.x) * 0.78));
            const y = center.y + (top ? -42 : 17);
            return (
              <g key={platform.id} data-platform-id={platform.id}>
                <rect x={center.x - width / 2} y={y} width={width} height={22}
                  fill={C.platform} stroke={C.platformEdge} />
                <rect x={center.x - width / 2} y={top ? y + 18 : y} width={width} height={4} fill={C.green} />
                <text x={center.x} y={y + 15} textAnchor="middle" fill="#101010"
                  fontSize={10} fontWeight={800}>
                  {platform.label} · P{platform.id}
                </text>
              </g>
            );
          })}

          {schematic.switchGeometry.map(({ item, point }) => {
            const selected = selectedEntity?.type === 'switch' && String(selectedEntity.id) === String(item.id);
            const normal = schematic.segmentGeometry.get(item.normalSegId);
            const reverse = schematic.segmentGeometry.get(item.reverseSegId);
            const normalOther = normal ? segmentOtherPoint(normal, point, item) : null;
            const reverseOther = reverse ? segmentOtherPoint(reverse, point, item) : null;
            const leg = (target: Point | null, active: boolean, key: string) => {
              if (!target) return null;
              const dx = target.x - point.x;
              const dy = target.y - point.y;
              const length = Math.max(1, Math.hypot(dx, dy));
              return (
                <line key={key} x1={point.x} y1={point.y}
                  x2={point.x + (dx / length) * 24} y2={point.y + (dy / length) * 24}
                  stroke={active ? C.green : '#56677b'} strokeWidth={active ? 5 : 3} />
              );
            };
            return (
              <g key={item.id} className="il-hit" data-switch-id={item.id}
                data-merge-seg-id={item.mergeSegId} data-normal-seg-id={item.normalSegId}
                data-reverse-seg-id={item.reverseSegId}
                aria-label={`道岔 W${item.id} ${item.state === 'REVERSE' ? '反位' : '定位'}`}
                onClick={(event) => {
                  event.stopPropagation();
                  onSelect({ type: 'switch', id: String(item.id) });
                }}>
                {leg(normalOther, item.state !== 'REVERSE', 'normal')}
                {leg(reverseOther, item.state === 'REVERSE', 'reverse')}
                <circle cx={point.x} cy={point.y} r={selected ? 8 : 6}
                  fill={item.state === 'REVERSE' ? '#f59e0b' : C.green}
                  stroke={selected ? '#ffffff' : '#07111d'} strokeWidth={selected ? 2 : 1} />
                <text x={point.x + 9} y={point.y + 19} fill="#d6dce6" fontSize={9}
                  fontWeight={700}>W{item.id}</text>
              </g>
            );
          })}

          {topology.signals.map((signal) => {
            const geometry = schematic.segmentGeometry.get(signal.segId);
            if (!geometry) return null;
            const ratio = Math.max(0.06, Math.min(0.94,
              (signal.offsetCm / 100) / Math.max(1, segmentLengthM(
                topology.segments.find((segment) => Number(segment.id) === signal.segId)!,
              ))));
            const point = pointAlong(geometry, ratio);
            const dx = geometry.end.x - geometry.start.x;
            const dy = geometry.end.y - geometry.start.y;
            const length = Math.max(1, Math.hypot(dx, dy));
            const normal = { x: -dy / length, y: dx / length };
            const side = signal.protectDir === 0xAA ? -1 : 1;
            const lamp = { x: point.x + normal.x * 26 * side, y: point.y + normal.y * 26 * side };
            const color = aspectColor(signal.aspect,
              openSignalIds.has(signal.id), protectedSignalIds.has(signal.id));
            const selected = selectedEntity?.type === 'signal' && Number(selectedEntity.id) === signal.id;
            const routeSelected = routeStartSignalId === signal.id || routeEndSignalId === signal.id;
            return (
              <g key={signal.id} className="il-hit" data-signal-id={signal.id}
                aria-label={`选择信号机 ${signal.name}`}
                onClick={(event) => { event.stopPropagation(); onSignalClick(signal.id); }}
                onContextMenu={(event) => {
                  event.preventDefault();
                  event.stopPropagation();
                  onSelect({ type: 'signal', id: signal.id });
                }}>
                <line x1={point.x} y1={point.y} x2={lamp.x} y2={lamp.y}
                  stroke="#e5e7eb" strokeWidth={1} />
                {routeSelected && <circle cx={lamp.x} cy={lamp.y} r={10} fill="none"
                  stroke={routeStartSignalId === signal.id ? '#60a5fa' : '#22d3ee'} strokeWidth={2} />}
                <circle cx={lamp.x} cy={lamp.y} r={selected ? 7 : 5.5}
                  fill={color} stroke="#f8fafc" strokeWidth={1} />
                <text x={lamp.x + 8} y={lamp.y + 3} fill={color} fontSize={9} fontWeight={700}>
                  {signal.name}
                </text>
              </g>
            );
          })}

          {trains.slice(0, 5).map((train, index) => {
            const ma = maMap[train.trainId];
            return (
              <g key={train.trainId} transform={`translate(${1120 + (index % 2) * 118}, ${18 + Math.floor(index / 2) * 29})`}
                className="il-hit" onClick={() => onSelect({ type: 'train', id: train.trainId })}>
                <rect width={108} height={22} fill="#071e2b" stroke={C.train} />
                <text x={7} y={15} fill={C.train} fontSize={10} fontWeight={800}>{train.trainId}</text>
                <text x={101} y={15} textAnchor="end" fill={ma ? C.green : C.muted} fontSize={9}>
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
