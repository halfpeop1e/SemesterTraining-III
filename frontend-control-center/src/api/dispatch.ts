import type { ApiResponse, DataSummary, EnergySnapshot, GradientInfo, RouteInfo, Signal, SimulationSnapshot, SpeedLimitZone, StationGeo, SwitchGeo, TrackGeometryData } from '../types/dispatch';
import type { SimulationLog } from '../types';

const API_BASE = '/api';

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

export async function startSimulation(duration: number): Promise<string> {
  return request<string>('/simulations/start', {
    method: 'POST',
    body: JSON.stringify({ simulationDuration: duration }),
  });
}

/** Advance simulation by `steps` seconds. Default 1. */
export async function stepSimulation(steps: number = 1): Promise<string> {
  return request<string>('/simulations/step', {
    method: 'POST',
    body: JSON.stringify({ steps }),
  });
}

export async function getSnapshot(): Promise<SimulationSnapshot> {
  return request<SimulationSnapshot>('/simulations/snapshot');
}

export async function getDispatchPlan(): Promise<string> {
  return request<string>('/dispatch/plan');
}

export async function getLineMap(): Promise<StationGeo[]> {
  return request<StationGeo[]>('/dispatch/line-map');
}

export async function getTrackGeometry(): Promise<TrackGeometryData> {
  return request<TrackGeometryData>('/dispatch/track-geometry');
}

/** 获取实时能耗快照（基于仿真日志计算） */
export async function getEnergy(
  tractionEfficiency = 0.85,
  regenEfficiency = 0.65,
  powerThresholdKw = 2000,
): Promise<EnergySnapshot> {
  return request<EnergySnapshot>(
    `/simulations/energy?tractionEfficiency=${tractionEfficiency}&regenEfficiency=${regenEfficiency}&powerThresholdKw=${powerThresholdKw}`,
  );
}

/** 获取原始仿真日志 */
export async function getSimulationLogs(): Promise<SimulationLog[]> {
  return request<SimulationLog[]>('/simulations/logs');
}

// ============ NEW: 线路设备数据 ============

export async function getSignals(): Promise<Signal[]> {
  return request<Signal[]>('/dispatch/signals');
}

export async function getSwitches(): Promise<SwitchGeo[]> {
  return request<SwitchGeo[]>('/dispatch/switches');
}

export async function getRoutes(): Promise<RouteInfo[]> {
  return request<RouteInfo[]>('/dispatch/routes');
}

export async function getSpeedLimits(): Promise<SpeedLimitZone[]> {
  return request<SpeedLimitZone[]>('/dispatch/speed-limits');
}

export async function getGradients(): Promise<GradientInfo[]> {
  return request<GradientInfo[]>('/dispatch/gradients');
}

export async function getSpeedLimitAt(km: number): Promise<{ km: number; speedLimitKmh: number; gradientPermille: number }> {
  return request(`/dispatch/speed-limit-at?km=${km}`);
}

export async function getDataSummary(): Promise<DataSummary> {
  return request<DataSummary>('/dispatch/data-summary');
}

/** 获取隧道数据 */
export async function getTunnels(): Promise<any[]> {
  return request<any[]>('/dispatch/tunnels');
}

/** 获取计轴器数据 */
export async function getAxleCounters(): Promise<any[]> {
  return request<any[]>('/dispatch/axle-counters');
}

/** 获取车档数据 */
export async function getBumpers(): Promise<any[]> {
  return request<any[]>('/dispatch/bumpers');
}

/** 获取碰撞区域数据 */
export async function getCollisionZones(): Promise<any[]> {
  return request<any[]>('/dispatch/collision-zones');
}

/** 获取防淹门数据 */
export async function getFloodGates(): Promise<any[]> {
  return request<any[]>('/dispatch/flood-gates');
}
