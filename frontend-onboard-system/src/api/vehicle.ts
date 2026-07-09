// 郭逸晨车载模块（成员三）——车辆仿真 API 调用

import type {
  SimulationControlRequest,
  SimulationResult,
  SimulationRunRequest,
  SimulationRunResponse,
} from '../types/vehicle';

const API_BASE = '/api';

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
