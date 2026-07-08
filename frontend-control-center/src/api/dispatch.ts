import type { ApiResponse, SimulationSnapshot, StationGeo } from '../types/dispatch';
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

export async function applyStrategy(trainId: string, strategyType: string, targetValue: number = 0): Promise<string> {
  return request<string>('/dispatch/strategy', {
    method: 'POST',
    body: JSON.stringify({ trainId, strategyType, targetValue }),
  });
}

/**
 * 拉取仿真日志（列车粒度时序：速度/位置/牵引制动等），供能耗评估页使用。
 * 注意：该前端函数原在 origin/main 中被 EnergyEvaluation 引用，但从未在 dispatch.ts 中定义
 * （仅后端 EvaluationRequest 有同名 DTO 方法）——属 周 的调度/仿真域代码缺口。
 * TODO(周): 确认后端仿真日志接口的真实路径；此处按 /simulations/logs 推测，
 * 若路径不符，调用方 EnergyEvaluation 已用 try/catch 兜底（空数组 + 提示），不阻断页面。
 */
export async function getSimulationLogs(): Promise<SimulationLog[]> {
  return request<SimulationLog[]>('/simulations/logs');
}
