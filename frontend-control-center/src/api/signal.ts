import type {
  ApiResponse,
  LineProfile,
  MovingAuthority,
  MaRequest,
  TrainState,
  SignalStatus,
  SignalEventItem,
  SwitchOperateRequest,
  RouteBuildRequest,
  SignalOpenRequest,
  TsrSetRequest,
  ControlResult,
  Route,
  SwitchState,
  TemporarySpeedRestriction,
} from '../types/signal';

const API_BASE = '/api';

// ===== 本地 mock 状态（后端不可用时使用，保证 UI 可演示） =====
let fallbackLineProfile: LineProfile | null = null;
let fallbackEvents: SignalEventItem[] = [];
function deepCopy<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj));
}
let eventIdSeq = 1;
let tsrIdSeq = 1;

function genId() {
  return String(eventIdSeq++);
}

function addEvent(level: SignalEventItem['level'], category: SignalEventItem['category'], message: string, sourceId?: string) {
  fallbackEvents.unshift({
    id: genId(),
    timestamp: Date.now(),
    level,
    category,
    message,
    sourceId,
  });
  if (fallbackEvents.length > 200) fallbackEvents.pop();
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    throw new Error(`请求失败: ${res.status}`);
  }
  const json: ApiResponse<T> = await res.json();
  if (!json.success) {
    throw new Error(json.message || '接口返回错误');
  }
  return json.data;
}

/**
 * 拉取静态线路拓扑（含里程索引），前端缓存一次。
 * 后端不可用时 fallback 到 public/line-profile.json（开发调试用）。
 */
export async function getLine(): Promise<LineProfile> {
  try {
    const data = await request<LineProfile>('/signal/line');
    fallbackLineProfile = enrichLineProfile(deepCopy(data));
    return deepCopy(fallbackLineProfile);
  } catch (e) {
    console.warn('[signal] 后端 /line 不可用，fallback 到本地 line-profile.json', e);
    const res = await fetch('/line-profile.json');
    if (!res.ok) throw new Error('本地 line-profile.json 加载失败');
    const raw = await res.json();
    fallbackLineProfile = enrichLineProfile(raw);
    return deepCopy(fallbackLineProfile);
  }
}

/**
 * 调用 MA 计算。后端不可用时返回 mock MA（开发调试用，仅用于验证 UI 渲染）。
 */
export async function computeMa(req: MaRequest): Promise<Record<string, MovingAuthority>> {
  try {
    return await request<Record<string, MovingAuthority>>('/signal/ma', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  } catch (e) {
    console.warn('[signal] 后端 /ma 不可用，fallback 到本地 mock MA', e);
    return mockMa(req.trains, req.lineProfile);
  }
}

// ===== 系统状态与事件 =====

export async function getStatus(): Promise<SignalStatus> {
  try {
    return await request<SignalStatus>('/signal/status');
  } catch (e) {
    return mockStatus();
  }
}

export async function getEvents(): Promise<SignalEventItem[]> {
  try {
    return await request<SignalEventItem[]>('/signal/events');
  } catch (e) {
    return [...fallbackEvents];
  }
}

// ===== 控制接口（后端可替代，前端保留本地 fallback） =====

export async function operateSwitch(req: SwitchOperateRequest): Promise<ControlResult> {
  try {
    return await request<ControlResult>(`/signal/switch/${req.switchId}/operate`, {
      method: 'POST',
      body: JSON.stringify({ position: req.position }),
    });
  } catch (e) {
    return mockOperateSwitch(req);
  }
}

export async function buildRoute(req: RouteBuildRequest): Promise<ControlResult> {
  try {
    return await request<ControlResult>('/signal/route/build', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  } catch (e) {
    return mockBuildRoute(req);
  }
}

export async function cancelRoute(routeId: string): Promise<ControlResult> {
  try {
    return await request<ControlResult>(`/signal/route/${routeId}/cancel`, { method: 'POST' });
  } catch (e) {
    return mockCancelRoute(routeId);
  }
}

export async function openSignal(req: SignalOpenRequest): Promise<ControlResult> {
  try {
    return await request<ControlResult>(`/signal/signal/${req.signalId}/open`, { method: 'POST' });
  } catch (e) {
    return mockOpenSignal(req);
  }
}

export async function setTsr(req: TsrSetRequest): Promise<ControlResult> {
  try {
    return await request<ControlResult>('/signal/tsr', {
      method: 'POST',
      body: JSON.stringify(req),
    });
  } catch (e) {
    return mockSetTsr(req);
  }
}

export async function cancelTsr(tsrId: string): Promise<ControlResult> {
  try {
    return await request<ControlResult>(`/signal/tsr/${tsrId}`, { method: 'DELETE' });
  } catch (e) {
    return mockCancelTsr(tsrId);
  }
}

// ===== 本地 enrich（简化版，够画平面图） =====

function parseChainage(chainage: string): number {
  const m = chainage.match(/[Kk](\d+)\+(\d+(?:\.\d+)?)/);
  if (!m) return 0;
  return Number(m[1]) * 1000 + Number(m[2]);
}

function segmentLengthM(s: any): number {
  if (typeof s.lengthM === 'number') return s.lengthM;
  if (typeof s.lengthCm === 'number') return s.lengthCm / 100;
  return 0;
}

function enrichLineProfile(raw: any): LineProfile {
  const segments = (raw.segments || []) as any[];
  const mileage: Record<string, [number, number]> = {};
  let cum = 0;
  for (const s of segments) {
    const len = segmentLengthM(s);
    mileage[String(s.id)] = [cum, cum + len];
    cum += len;
  }
  const totalLengthM = (raw.totalLengthM as number) > 0 ? raw.totalLengthM : cum;

  const platforms = (raw.platforms || []) as any[];
  const platformMap = new Map<number, any>();
  for (const p of platforms) platformMap.set(p.id, p);

  const stations = (raw.stations || []).map((st: any) => {
    let pos = st.positionM;
    if (!pos && Array.isArray(st.platformIds)) {
      for (const pid of st.platformIds) {
        const p = platformMap.get(pid);
        if (p?.chainage) {
          pos = parseChainage(p.chainage);
          break;
        }
      }
    }
    return { ...st, positionM: pos };
  });

  const switches = (raw.switches || []).map((sw: any) => {
    let pos = sw.positionM;
    if (!pos && sw.mergeSegId) {
      const mi = mileage[String(sw.mergeSegId)];
      if (mi) pos = mi[0];
    }
    return { ...sw, positionM: pos };
  });

  const line: LineProfile = {
    ...raw,
    stations,
    switches,
    segmentMileage: mileage,
    totalLengthM,
    axleSections: raw.axleSections || [],
    physicalSections: raw.physicalSections || [],
    logicalSections: raw.logicalSections || [],
    routes: raw.routes || [],
    overlaps: raw.overlaps || [],
    tsrs: raw.tsrs || [],
    staticSpeedRestrictions: raw.staticSpeedRestrictions || [],
    gradients: raw.gradients || [],
    balises: raw.balises || [],
    turnbacks: raw.turnbacks || [],
  };

  // 如果后端没有给出 axleSections / physicalSections，则按 segment 生成一份，用于区段占用显示
  if (!line.axleSections.length && segments.length > 0) {
    line.axleSections = segments.map((s: any) => ({
      id: `AX-${s.id}`,
      segId: Number(s.id),
      occupied: false,
    }));
  }
  if (!line.physicalSections.length && segments.length > 0) {
    line.physicalSections = segments.map((s: any) => ({
      id: `PS-${s.id}`,
      segIds: [Number(s.id)],
    }));
  }

  return line;
}

// ===== 本地 mock MA（仅 UI 调试用） =====

function mockMa(trains: TrainState[], line: LineProfile): Record<string, MovingAuthority> {
  const total = line.totalLengthM || 47500;
  const result: Record<string, MovingAuthority> = {};
  for (const t of trains) {
    const isBad = isNaN(t.positionM) || t.direction === 'INVALID';
    const eoa = isBad ? t.positionM || 0 : Math.min(t.positionM + 1500, total);
    result[t.trainId] = {
      trainId: t.trainId,
      endOfAuthorityM: eoa,
      maxSpeedKmh: isBad ? 0 : Math.min(t.speedKmh + 10, 80),
      basis: 'LINE_LIMIT',
      event: isBad ? 'DEGRADED' : 'NONE',
      capSignalId: null,
      routeId: null,
      timestamp: t.timestamp,
    };
  }
    return result;
}

function mockStatus(): SignalStatus {
  const alertCount = fallbackEvents.filter((e) => e.level === 'ERROR' || e.level === 'WARN').length;
  const health = alertCount > 5 ? 'FAULT' : alertCount > 0 ? 'DEGRADED' : 'HEALTHY';
  return {
    onlineTrains: 4,
    alertCount,
    health,
    simulationTime: Math.floor(Date.now() / 1000) % 86400,
  };
}

function mockOperateSwitch(req: SwitchOperateRequest): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  const sw = fallbackLineProfile.switches.find((s) => s.id === req.switchId);
  if (!sw) return { success: false, message: `道岔 ${req.switchId} 不存在` };
  sw.state = req.position;
  addEvent('INFO', 'SWITCH', `道岔 ${req.switchId} 单操至 ${req.position}`, req.switchId);
  return { success: true, message: `道岔 ${req.switchId} 已置 ${req.position}` };
}

function mockBuildRoute(req: RouteBuildRequest): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  const startSig = fallbackLineProfile.signals.find((s) => s.id === req.startSignalId);
  const endSig = fallbackLineProfile.signals.find((s) => s.id === req.endSignalId);
  if (!startSig || !endSig) return { success: false, message: '始端/终端信号机不存在' };
  const routeId = `R-${req.startSignalId}-${req.endSignalId}`;
  const existing = fallbackLineProfile.routes.findIndex((r) => r.id === routeId);
  const route: Route = {
    id: routeId,
    startSignalId: req.startSignalId,
    endSignalId: req.endSignalId,
    built: true,
    cancelled: false,
  };
  if (existing >= 0) fallbackLineProfile.routes[existing] = route;
  else fallbackLineProfile.routes.push(route);
  addEvent('INFO', 'SIGNAL', `进路 ${routeId} 办理成功`, routeId);
  return { success: true, message: `进路 ${routeId} 已办理` };
}

function mockCancelRoute(routeId: string): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  const idx = fallbackLineProfile.routes.findIndex((r) => r.id === routeId);
  if (idx < 0) return { success: false, message: `进路 ${routeId} 不存在` };
  fallbackLineProfile.routes.splice(idx, 1);
  addEvent('INFO', 'SIGNAL', `进路 ${routeId} 已取消`, routeId);
  return { success: true, message: `进路 ${routeId} 已取消` };
}

function mockOpenSignal(req: SignalOpenRequest): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  const sig = fallbackLineProfile.signals.find((s) => s.id === req.signalId);
  if (!sig) return { success: false, message: `信号机 ${req.signalId} 不存在` };
  sig.aspect = 'GREEN';
  addEvent('INFO', 'SIGNAL', `信号机 ${sig.name || req.signalId} 重开绿灯`, String(req.signalId));
  return { success: true, message: `信号机 ${sig.name || req.signalId} 已重开` };
}

function mockSetTsr(req: TsrSetRequest): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  if (req.startM >= req.endM) return { success: false, message: '临时限速终点必须大于起点' };
  const tsr: TemporarySpeedRestriction = {
    id: `TSR-${tsrIdSeq++}`,
    startM: req.startM,
    endM: req.endM,
    speedLimitKmh: req.speedLimitKmh,
    active: req.active,
  };
  fallbackLineProfile.tsrs.push(tsr);
  addEvent('WARN', 'TSR', `设置临时限速 ${req.speedLimitKmh} km/h (${req.startM}–${req.endM}m)`, tsr.id);
  return { success: true, message: `临时限速 ${tsr.id} 已设置` };
}

function mockCancelTsr(tsrId: string): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  const idx = fallbackLineProfile.tsrs.findIndex((t) => t.id === tsrId);
  if (idx < 0) return { success: false, message: `临时限速 ${tsrId} 不存在` };
  fallbackLineProfile.tsrs.splice(idx, 1);
  addEvent('INFO', 'TSR', `临时限速 ${tsrId} 已取消`, tsrId);
  return { success: true, message: `临时限速 ${tsrId} 已取消` };
}

// ===== 工具：计算区段占用（基于列车位置与车长） =====

export function computeOccupiedSegIds(line: LineProfile, trains: TrainState[]): Set<number> {
  const occupied = new Set<number>();
  const mileage = line.segmentMileage;
  for (const t of trains) {
    if (isNaN(t.positionM)) continue;
    const startM = Math.max(0, t.positionM - t.lengthM);
    const endM = t.positionM;
    for (const [segId, [s, e]] of Object.entries(mileage)) {
      if (e > startM && s < endM) {
        occupied.add(Number(segId));
      }
    }
  }
  return occupied;
}

// ===== 辅助：强制刷新 fallback 状态（控制操作后供 UI 重新 getLine） =====

export function refreshFallbackLine(): LineProfile | null {
  return fallbackLineProfile ? enrichLineProfile(deepCopy(fallbackLineProfile)) : null;
}

export function getFallbackEvents(): SignalEventItem[] {
  return [...fallbackEvents];
}
