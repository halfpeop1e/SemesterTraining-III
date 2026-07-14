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
  Switch,
  SwitchState,
  SignalAspect,
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
 * 拉取线路拓扑，并合并联锁运行时状态。
 * 关键规则：远程 null/空 不得覆盖已有非空运行时状态（防「闪一下又回去」）。
 */
export async function getLine(): Promise<LineProfile> {
  const previous = fallbackLineProfile ? deepCopy(fallbackLineProfile) : null;
  try {
    const data = await request<LineProfile>('/signal/line');
    let line = enrichLineProfile(deepCopy(data));
    // 先保留上一帧非空运行时（乐观更新 / 上次合并结果）
    if (previous) {
      line = preserveRuntimeFromPrevious(line, previous);
    }
    try {
      const [aspects, switches, built] = await Promise.all([
        getSignalAspects().catch(() => null),
        getAllSwitches().catch(() => null),
        getBuiltRoutes().catch(() => null),
      ]);
      line = mergeRuntimeState(line, aspects, switches, built, previous);
    } catch {
      /* 合并失败仍返回 line */
    }
    fallbackLineProfile = deepCopy(line);
    return deepCopy(line);
  } catch (e) {
    console.warn('[signal] 后端 /line 不可用，fallback 到本地 line-profile.json', e);
    if (previous) return deepCopy(previous);
    const res = await fetch('/line-profile.json');
    if (!res.ok) throw new Error('本地 line-profile.json 加载失败');
    const raw = await res.json();
    fallbackLineProfile = enrichLineProfile(raw);
    return deepCopy(fallbackLineProfile);
  }
}

/** 上一帧运行时写回新 line：null 不盖；道岔 REVERSE 粘住（JSON 默认 NORMAL 会冲掉） */
export function preserveRuntimeFromPrevious(
  line: LineProfile,
  previous: LineProfile,
): LineProfile {
  const next = deepCopy(line);
  const prevSig = new Map((previous.signals || []).map((s) => [s.id, s]));
  for (const sig of next.signals || []) {
    const p = prevSig.get(sig.id);
    if ((sig.aspect == null || sig.aspect === undefined) && p?.aspect) {
      sig.aspect = p.aspect;
    }
  }
  const prevSw = new Map((previous.switches || []).map((s) => [String(s.id), s]));
  for (const sw of next.switches || []) {
    const p = prevSw.get(String(sw.id));
    if (!p?.state) continue;
    // 静态导出默认 NORMAL；若上一帧是 REVERSE（用户单操），禁止被 NORMAL 冲掉
    if (p.state === 'REVERSE' && (sw.state === 'NORMAL' || sw.state == null || sw.state === undefined)) {
      sw.state = 'REVERSE';
    } else if (sw.state == null || sw.state === undefined) {
      sw.state = p.state;
    }
  }
  const prevRt = new Map((previous.routes || []).map((r) => [Number(r.id), r]));
  for (const r of next.routes || []) {
    const p = prevRt.get(Number(r.id));
    if (p?.built && !r.built) {
      r.built = true;
      r.cancelled = false;
    }
  }
  return next;
}

/**
 * 合并远程运行时。
 * - aspects: 仅非 null 写入
 * - switches: REVERSE 优先；远程 NORMAL 不得盖掉 previous REVERSE
 * - built: 远程空不强制清零
 */
export function mergeRuntimeState(
  line: LineProfile,
  aspects: Record<string, SignalAspect | null> | null,
  switches: Switch[] | null,
  built: Route[] | null,
  previous?: LineProfile | null,
): LineProfile {
  const next = deepCopy(line);
  const prevSw = new Map(
    (previous?.switches || []).map((s) => [String(s.id), s.state as SwitchState | null | undefined]),
  );

  if (aspects) {
    for (const sig of next.signals || []) {
      const a = aspects[String(sig.id)];
      if (a != null) sig.aspect = a;
    }
  }

  if (switches && switches.length > 0) {
    const byId = new Map(switches.map((s) => [String(s.id), s]));
    for (const sw of next.switches || []) {
      const id = String(sw.id);
      const live = byId.get(id);
      const prevState = prevSw.get(id);
      if (live?.state === 'REVERSE') {
        sw.state = 'REVERSE';
      } else if (prevState === 'REVERSE') {
        // 远程仍回 NORMAL（缓存未热/未重启）时粘住本地反位
        sw.state = 'REVERSE';
      } else if (live?.state != null) {
        sw.state = live.state;
      }
      if (live && typeof live.positionM === 'number' && live.positionM > 0) {
        sw.positionM = live.positionM;
      }
    }
  } else {
    // 无远程 switches 列表：仍粘 previous REVERSE
    for (const sw of next.switches || []) {
      if (prevSw.get(String(sw.id)) === 'REVERSE') sw.state = 'REVERSE';
    }
  }

  if (built != null) {
    for (const r of next.routes || []) {
      const id = Number(r.id);
      const remoteHas = built.some((b) => Number(b.id) === id);
      if (remoteHas) {
        r.built = true;
        r.cancelled = false;
      } else if (built.length > 0) {
        r.built = false;
        r.cancelled = true;
      }
    }
  }

  return next;
}

/** 本地乐观更新信号 aspect（API 成功后立即上色，不依赖二次拉 line） */
export function patchSignalAspect(
  line: LineProfile,
  signalId: number,
  aspect: SignalAspect,
): LineProfile {
  const next = deepCopy(line);
  const sig = next.signals?.find((s) => s.id === signalId);
  if (sig) sig.aspect = aspect;
  if (fallbackLineProfile) {
    const fs = fallbackLineProfile.signals?.find((s) => s.id === signalId);
    if (fs) fs.aspect = aspect;
  }
  return next;
}

export function patchSwitchState(
  line: LineProfile,
  switchId: string,
  state: SwitchState,
): LineProfile {
  const next = deepCopy(line);
  for (const sw of next.switches || []) {
    if (String(sw.id) === String(switchId)) sw.state = state;
  }
  if (fallbackLineProfile?.switches) {
    for (const sw of fallbackLineProfile.switches) {
      if (String(sw.id) === String(switchId)) sw.state = state;
    }
  }
  return next;
}

export function patchRouteBuilt(
  line: LineProfile,
  routeId: number,
  built: boolean,
): LineProfile {
  const next = deepCopy(line);
  const route = next.routes?.find((r) => Number(r.id) === routeId);
  if (route) {
    route.built = built;
    route.cancelled = !built;
    if (built) {
      const start = next.signals?.find((s) => s.id === route.startSignalId);
      if (start) start.aspect = 'GREEN';
    } else {
      const start = next.signals?.find((s) => s.id === route.startSignalId);
      if (start) start.aspect = 'RED';
    }
  }
  return next;
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

/** Read the backend-owned MA registry used by ATP/ATO; this is the normal operations path. */
export async function getLatestMa(): Promise<Record<string, MovingAuthority>> {
  return request<Record<string, MovingAuthority>>('/signal/ma/latest');
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
    const state = req.position;
    await request<unknown>(
      `/signal/switch/${encodeURIComponent(req.switchId)}/operate?state=${encodeURIComponent(state)}`,
      { method: 'POST' },
    );
    // 同步 fallback（含 id 类型不一致时的宽松匹配）
    if (fallbackLineProfile?.switches) {
      for (const sw of fallbackLineProfile.switches) {
        if (String(sw.id) === String(req.switchId)) sw.state = req.position;
      }
    }
    return { success: true, message: `道岔 ${req.switchId} → ${state}` };
  } catch (e) {
    console.warn('[signal] operateSwitch 后端失败', e);
    return { success: false, message: `道岔操作失败: ${(e as Error).message}` };
  }
}

/** 进路办理结果（含可选 trainId 绑定信息） */
export interface RouteBuildResult extends ControlResult {
  bound?: boolean;       // 是否成功绑定列车
  trainId?: string;      // 绑定的列车（仅 build+assign 快捷路径时返回）
  routeId?: number;      // 进路 ID
}

export interface LaboratoryStationLegResult extends ControlResult {
  trainId?: string;
  fromStationId?: number;
  toStationId?: number;
  routeIds?: number[];
}

export interface LaboratoryStationLegCapability {
  fromStationId: number;
  toStationId: number;
  supported: boolean;
  routeIds: number[];
  reason: string;
}

export async function getLaboratoryStationLegCapabilities(): Promise<LaboratoryStationLegCapability[]> {
  return request<LaboratoryStationLegCapability[]>('/signal/laboratory/station-leg/capabilities');
}

/** Builds every CBI route in one topology-verified laboratory station leg. */
export async function buildLaboratoryStationLeg(
  trainId: string,
  fromStationId: number,
  toStationId: number,
): Promise<LaboratoryStationLegResult> {
  try {
    const query = new URLSearchParams({
      trainId,
      fromStationId: String(fromStationId),
      toStationId: String(toStationId),
    });
    const result = await request<{
      trainId?: string;
      fromStationId?: number;
      toStationId?: number;
      routeIds?: number[];
    }>(`/signal/laboratory/station-leg/build?${query.toString()}`, { method: 'POST' });
    return { success: true, message: 'Station leg established', ...result };
  } catch (e) {
    return { success: false, message: `Station leg establishment failed: ${(e as Error).message}` };
  }
}

export async function cancelLaboratoryStationLeg(trainId: string): Promise<LaboratoryStationLegResult> {
  try {
    const result = await request<{ trainId?: string; routeIds?: number[] }>(
      `/signal/laboratory/station-leg/cancel?trainId=${encodeURIComponent(trainId)}`,
      { method: 'POST' },
    );
    return { success: true, message: 'Station leg cancelled', ...result };
  } catch (e) {
    return { success: false, message: `Station leg cancellation failed: ${(e as Error).message}` };
  }
}

export async function buildRoute(req: RouteBuildRequest, trainId?: string): Promise<RouteBuildResult> {
  try {
    let url = `/signal/route/build?routeId=${encodeURIComponent(String(req.routeId))}`;
    if (trainId) url += `&trainId=${encodeURIComponent(trainId)}`;
    const res = await request<{ routeId?: number; trainId?: string | null; built?: boolean; bound?: boolean }>(url, {
      method: 'POST',
    });
    if (fallbackLineProfile) {
      const route = fallbackLineProfile.routes?.find((r) => Number(r.id) === Number(req.routeId));
      if (route) {
        route.built = true;
        route.cancelled = false;
        const start = fallbackLineProfile.signals?.find((s) => s.id === route.startSignalId);
        if (start) start.aspect = 'GREEN';
      }
    }
    const name =
      fallbackLineProfile?.routes?.find((r) => Number(r.id) === Number(req.routeId))?.name ||
      String(req.routeId);
    const bound = res?.bound === true;
    const boundTrain = res?.trainId ?? undefined;
    let msg = `进路 ${name} 已办理`;
    if (trainId && !bound) {
      msg = `进路 ${name} 已建立（站场）；绑车失败`;
    } else if (trainId && bound) {
      msg = `进路 ${name} 已建立并绑定 ${boundTrain}`;
    } else if (!trainId) {
      msg = `进路 ${name} 已建立（站场）。未绑列车，MA 进路维不生效。`;
    }
    return { success: true, message: msg, bound, trainId: boundTrain, routeId: req.routeId };
  } catch (e) {
    console.warn('[signal] buildRoute 后端失败', e);
    return {
      success: false,
      message: `进路办理失败: ${(e as Error).message}`,
      bound: false,
      routeId: req.routeId,
    };
  }
}

/** 绑定列车到已建进路（assign） */
export async function assignRoute(trainId: string, routeId: number): Promise<ControlResult> {
  try {
    const res = await request<{ routeId?: number; trainId?: string; bound?: boolean }>(
      `/signal/route/assign?trainId=${encodeURIComponent(trainId)}&routeId=${encodeURIComponent(String(routeId))}`,
      { method: 'POST' },
    );
    const bound = res?.bound === true;
    const msg = bound
      ? `列车 ${trainId} 已绑定进路 ${routeId}`
      : `绑定失败`;
    return { success: bound, message: msg };
  } catch (e) {
    console.warn('[signal] assignRoute 后端失败', e);
    return { success: false, message: `绑定失败: ${(e as Error).message}` };
  }
}

/** 解绑列车（unassign） */
export async function unassignRoute(trainId: string): Promise<ControlResult> {
  try {
    await request<unknown>(
      `/signal/route/unassign?trainId=${encodeURIComponent(trainId)}`,
      { method: 'POST' },
    );
    return { success: true, message: `列车 ${trainId} 已解绑` };
  } catch (e) {
    console.warn('[signal] unassignRoute 后端失败', e);
    return { success: false, message: `解绑失败: ${(e as Error).message}` };
  }
}

/** 查询所有列车↔进路绑定关系 */
export async function getRouteBindings(): Promise<Record<string, number>> {
  try {
    return await request<Record<string, number>>('/signal/route/bindings');
  } catch {
    return {};
  }
}

export async function cancelRoute(routeId: string | number): Promise<ControlResult> {
  try {
    await request<unknown>(
      `/signal/route/${encodeURIComponent(String(routeId))}/cancel`,
      { method: 'POST' },
    );
    if (fallbackLineProfile) {
      const route = fallbackLineProfile.routes?.find((r) => String(r.id) === String(routeId));
      if (route) {
        route.built = false;
        route.cancelled = true;
        const start = fallbackLineProfile.signals?.find((s) => s.id === route.startSignalId);
        if (start) start.aspect = 'RED';
      }
    }
    return { success: true, message: `进路 ${routeId} 已取消` };
  } catch (e) {
    console.warn('[signal] cancelRoute 后端失败', e);
    return { success: false, message: `进路取消失败: ${(e as Error).message}` };
  }
}

export async function openSignal(req: SignalOpenRequest): Promise<ControlResult> {
  try {
    const aspect = req.aspect || 'GREEN';
    await request<unknown>(
      `/signal/signal/${encodeURIComponent(String(req.signalId))}/set?aspect=${encodeURIComponent(aspect)}`,
      { method: 'POST' },
    );
    if (fallbackLineProfile) {
      const sig = fallbackLineProfile.signals?.find((s) => s.id === req.signalId);
      if (sig) sig.aspect = aspect;
    }
    const name =
      fallbackLineProfile?.signals?.find((s) => s.id === req.signalId)?.name ||
      String(req.signalId);
    return { success: true, message: `信号机 ${name} 已设置 ${aspect}` };
  } catch (e) {
    console.warn('[signal] openSignal 后端失败', e);
    return { success: false, message: `信号机设置失败: ${(e as Error).message}` };
  }
}

export async function getBuiltRoutes(): Promise<Route[]> {
  return await request<Route[]>('/signal/route/built');
}

export async function getAllSwitches(): Promise<Switch[]> {
  try {
    const remote = await request<Switch[]>('/signal/switch/all');
    if (!fallbackLineProfile?.switches?.length) return remote || [];
    // 用本地 REVERSE 补远程仍为 NORMAL 的项
    const localById = new Map(
      fallbackLineProfile.switches.map((s) => [String(s.id), s]),
    );
    return (remote || []).map((sw) => {
      const local = localById.get(String(sw.id));
      if (local?.state === 'REVERSE' && sw.state !== 'REVERSE') {
        return { ...sw, state: 'REVERSE' as SwitchState };
      }
      return sw;
    });
  } catch {
    return fallbackLineProfile?.switches ? deepCopy(fallbackLineProfile.switches) : [];
  }
}

export async function getSignalAspects(): Promise<Record<string, SignalAspect | null>> {
  try {
    const remote = await request<Record<string, SignalAspect | null>>('/signal/signal/aspects');
    // 用本地非空 aspect 补远程 null
    const out: Record<string, SignalAspect | null> = { ...(remote || {}) };
    for (const s of fallbackLineProfile?.signals || []) {
      if (s.aspect && (out[String(s.id)] == null || out[String(s.id)] === undefined)) {
        out[String(s.id)] = s.aspect;
      }
    }
    return out;
  } catch {
    const out: Record<string, SignalAspect | null> = {};
    for (const s of fallbackLineProfile?.signals || []) {
      out[String(s.id)] = s.aspect;
    }
    return out;
  }
}

export async function setTsr(req: TsrSetRequest): Promise<ControlResult> {
  try {
    const res = await request<TemporarySpeedRestriction>('/signal/tsr', {
      method: 'POST',
      body: JSON.stringify(req),
    });
    return { success: true, message: `临时限速 ${res?.id || ''} 已设置 (${req.speedLimitKmh} km/h, ${req.startM}-${req.endM}m)` };
  } catch (e) {
    return { success: false, message: `设置限速失败: ${(e as Error).message}` };
  }
}

export async function cancelTsr(tsrId: string): Promise<ControlResult> {
  try {
    await request<unknown>(`/signal/tsr/${encodeURIComponent(tsrId)}`, { method: 'DELETE' });
    return { success: true, message: `临时限速 ${tsrId} 已取消` };
  } catch (e) {
    return { success: false, message: `取消限速失败: ${(e as Error).message}` };
  }
}

export async function getTsrs(): Promise<TemporarySpeedRestriction[]> {
  try {
    return await request<TemporarySpeedRestriction[]>('/signal/tsr');
  } catch {
    return [];
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

  // 优先信任后端 segmentMileage；仅缺省时再本地推导
  let mileage: Record<string, [number, number]> = {};
  const backendMi = raw.segmentMileage;
  if (backendMi && typeof backendMi === 'object' && Object.keys(backendMi).length > 0) {
    for (const [k, v] of Object.entries(backendMi)) {
      if (Array.isArray(v) && v.length >= 2) {
        mileage[String(k)] = [Number(v[0]), Number(v[1])];
      } else if (v && typeof v === 'object') {
        const arr = v as { 0?: number; 1?: number; startM?: number; endM?: number };
        const a = arr[0] ?? arr.startM;
        const b = arr[1] ?? arr.endM;
        if (a != null && b != null) mileage[String(k)] = [Number(a), Number(b)];
      }
    }
  }
  if (Object.keys(mileage).length === 0) {
    let cum = 0;
    for (const s of segments) {
      const len = segmentLengthM(s);
      mileage[String(s.id)] = [cum, cum + len];
      cum += len;
    }
  }

  const totalLengthM =
    (raw.totalLengthM as number) > 0
      ? raw.totalLengthM
      : Math.max(0, ...Object.values(mileage).map(([, e]) => e), 0);

  const platforms = (raw.platforms || []) as any[];
  const platformMap = new Map<number, any>();
  for (const p of platforms) platformMap.set(p.id, p);

  const stations = (raw.stations || []).map((st: any) => {
    let pos = st.positionM;
    if ((!pos || pos === 0) && Array.isArray(st.platformIds)) {
      let min = Number.POSITIVE_INFINITY;
      for (const pid of st.platformIds) {
        const p = platformMap.get(pid);
        if (p?.chainage) {
          const c = parseChainage(p.chainage);
          if (c > 0) min = Math.min(min, c);
        }
        if (typeof p?.centerM === 'number' && p.centerM > 0) {
          min = Math.min(min, p.centerM);
        }
      }
      if (min !== Number.POSITIVE_INFINITY) pos = min;
    }
    return { ...st, positionM: pos || 0 };
  });

  const switches = (raw.switches || []).map((sw: any) => {
    let pos = sw.positionM;
    if ((!pos || pos === 0) && sw.mergeSegId != null) {
      const mi = mileage[String(sw.mergeSegId)];
      if (mi) pos = mi[0];
    }
    return { ...sw, positionM: pos || 0, id: String(sw.id) };
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
  const sw = fallbackLineProfile.switches.find((s) => String(s.id) === String(req.switchId));
  if (!sw) return { success: false, message: `道岔 ${req.switchId} 不存在` };
  sw.state = req.position;
  addEvent('INFO', 'SWITCH', `道岔 ${req.switchId} 单操至 ${req.position}`, req.switchId);
  return { success: true, message: `道岔 ${req.switchId} 已置 ${req.position}` };
}

function mockBuildRoute(req: RouteBuildRequest): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  const route = fallbackLineProfile.routes.find((r) => Number(r.id) === Number(req.routeId));
  if (!route) return { success: false, message: `进路 ${req.routeId} 不存在` };
  route.built = true;
  route.cancelled = false;
  const startSig = fallbackLineProfile.signals.find((s) => s.id === route.startSignalId);
  if (startSig) startSig.aspect = 'GREEN';
  const label = route.name || String(req.routeId);
  addEvent('INFO', 'SIGNAL', `进路 ${label} 办理成功`, String(req.routeId));
  return { success: true, message: `进路 ${label} 已办理` };
}

function mockCancelRoute(routeId: string): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  const route = fallbackLineProfile.routes.find((r) => String(r.id) === String(routeId));
  if (!route) return { success: false, message: `进路 ${routeId} 不存在` };
  route.built = false;
  route.cancelled = true;
  const startSig = fallbackLineProfile.signals.find((s) => s.id === route.startSignalId);
  if (startSig) startSig.aspect = 'RED';
  const label = route.name || routeId;
  addEvent('INFO', 'SIGNAL', `进路 ${label} 已取消`, routeId);
  return { success: true, message: `进路 ${label} 已取消` };
}

function mockOpenSignal(req: SignalOpenRequest): ControlResult {
  if (!fallbackLineProfile) return { success: false, message: '线路拓扑未加载' };
  const sig = fallbackLineProfile.signals.find((s) => s.id === req.signalId);
  if (!sig) return { success: false, message: `信号机 ${req.signalId} 不存在` };
  sig.aspect = req.aspect || 'GREEN';
  addEvent('INFO', 'SIGNAL', `信号机 ${sig.name || req.signalId} → ${sig.aspect}`, String(req.signalId));
  return { success: true, message: `信号机 ${sig.name || req.signalId} 已设置 ${sig.aspect}` };
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
