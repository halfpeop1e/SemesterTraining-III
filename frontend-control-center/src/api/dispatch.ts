import type { ApiResponse, SimulationSnapshot, StationGeo } from '../types/dispatch';

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
