import type { ApiResponse, SimulationSnapshot, StationGeo, SystemStatesResponse } from '../types/dispatch';
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

export async function resetSimulation(): Promise<string> {
  return request<string>('/simulations/reset', { method: 'POST' });
}

export async function pauseSimulation(): Promise<string> {
  return request<string>('/simulations/pause', { method: 'POST' });
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
 */
export async function getSimulationLogs(): Promise<SimulationLog[]> {
  return request<SimulationLog[]>('/simulations/logs');
}

export interface IntegrationCommand {
  commandId: string; trainId: string; commandType: string; reason: string;
  status: string; source: string; targetValue: number;
}
export interface OnboardStatusReport {
  trainId: string;
  deviceId?: string;
  sourceType?: string;
  timestampSeconds: number;
  positionMeters: number;
  speedKmh: number;
  accelerationMps2: number;
  direction: string;
  currentStationId?: string;
  nextStationId?: string;
  phase: string;
  health: string;
  lineId?: string;
  routeId?: string;
  fromStationId?: string;
  toStationId?: string;
  fromStationName?: string;
  toStationName?: string;
  operatingMode?: string;
  paused: boolean;
  authoritative?: boolean;
}
export interface OnboardMonitoringItem {
  trainId: string;
  deviceId?: string;
  sourceType: string;
  online: boolean;
  ageSeconds: number;
  receivedAtEpochMillis: number;
  report: OnboardStatusReport;
}
export interface DispatcherWorkstation {
  automaticOperation: boolean;
  commands: IntegrationCommand[];
  pendingManualConfirmations: IntegrationCommand[];
  onboardReports: unknown[];
  onboardMonitoring: OnboardMonitoringItem[];
  onboardEvents: Array<{ eventId: string; trainId: string; eventType: string; severity: string; details: string }>;
  communicationStatus: { mode: string; healthy: boolean };
  protocolAdapterStatus: Record<string,string>;
}
export const getDispatcherWorkstation = () => request<DispatcherWorkstation>('/dispatch/workstation');
export const getOnboardMonitoring = () => request<OnboardMonitoringItem[]>('/onboard/monitoring');
export const issueIntegrationCommand = (
  trainId: string,
  commandType: string,
  reason: string,
  targetValue = 0,
) =>
  request<IntegrationCommand>('/dispatch/commands', {
    method: 'POST',
    body: JSON.stringify({
      trainId,
      commandType,
      reason,
      targetValue,
      source: 'DISPATCHER',
      priority: 80,
    }),
  });
export const confirmIntegrationCommand = (commandId: string, approved = true) =>
  request<IntegrationCommand>('/dispatch/commands/confirm', {
    method: 'POST', body: JSON.stringify({ commandId, approved }),
  });

// ── CBTC 执行层: 故障注入 ──
export const injectFault = (trainId: string, faultType: string, severity = 4) =>
  request<{ trainId: string; faultType: string; status: string }>('/dispatch/fault/inject', {
    method: 'POST',
    body: JSON.stringify({ trainId, faultType, severity }),
  });

export const clearFault = (trainId: string, faultType: string) =>
  request<{ trainId: string; faultType: string; status: string }>('/dispatch/fault/clear', {
    method: 'POST',
    body: JSON.stringify({ trainId, faultType }),
  });

export const getSystemStates = () =>
  request<SystemStatesResponse>('/dispatch/states');
