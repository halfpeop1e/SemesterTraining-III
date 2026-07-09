// 郭逸晨车载模块（成员三）——车辆仿真 API 调用

import type {
  SimulationControlRequest,
  SimulationResult,
  SimulationRunRequest,
  SimulationRunResponse,
} from '../types/vehicle';

const API_BASE = '/api';

export interface DispatchCommand {
  commandId: string; trainId: string; commandType: string; targetValue: number;
  reason: string; status: string; source: string;
}
export interface TimetableEntry {
  stationId: number;
  stationName: string;
  stationIndex: number;
  stationKm: number;
  plannedArrival: number;
  plannedDeparture: number;
  plannedDwell: number;
}

export interface OnboardSnapshot {
  currentTimeSeconds: number;
  train: { trainId: string; speedKmh: number; positionMeters: number; status: string } | null;
  commands: DispatchCommand[];
  movementAuthorityMeters: number;
  speedLimitKmh: number;
  communicationStatus: string;
  safetyStatus: string;
  timetable: TimetableEntry[];
}

async function integrationRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' }, ...init,
  });
  const body = await response.json();
  if (!response.ok || !body.success) throw new Error(body.message || `HTTP ${response.status}`);
  return body.data as T;
}

export const getOnboardSnapshot = (trainId: string) =>
  integrationRequest<OnboardSnapshot>(`/signal/train/${trainId}/snapshot`);
export const ackDispatchCommand = (commandId: string, executionStatus = 'EXECUTING') =>
  integrationRequest<DispatchCommand>('/dispatch/commands/ack', {
    method: 'POST', body: JSON.stringify({ commandId, accepted: true, executionStatus }),
  });
export const reportOnboardStatus = (payload: object) =>
  integrationRequest<string>('/signal/report/status', { method: 'POST', body: JSON.stringify(payload) });
export const reportOnboardEvent = (payload: object) =>
  integrationRequest<string>('/dispatch/report/event', { method: 'POST', body: JSON.stringify(payload) });

export async function runVehicleSimulation(
  request?: SimulationRunRequest,
): Promise<SimulationResult> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: request ? JSON.stringify(request) : undefined,
  });
  if (!res.ok) throw new Error(`车辆仿真接口调用失败: ${res.status}`);
  const body: SimulationRunResponse = await res.json();
  if (!body.success) throw new Error(body.message || '车辆仿真接口返回失败');
  return body.data;
}

/**
 * 驾驶员控制续算。
 * request.currentState.position 必须是全程累积里程。
 * request.totalTargetPosition 是本次仿真总目标距离（= stopResult.targetStopPosition）。
 */
export async function callVehicleControl(
  request: SimulationControlRequest,
): Promise<SimulationResult> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/control`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!res.ok) throw new Error(`控制接口调用失败: ${res.status}`);
  const body: SimulationRunResponse = await res.json();
  if (!body.success) throw new Error(body.message || '控制接口返回失败');
  return body.data;
}
