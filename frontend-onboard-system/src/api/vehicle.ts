// 郭逸晨车载模块（成员三）——车辆仿真 API 调用
//
// 对应后端 VehicleSimulationController：
//   POST /api/vehicle/simulation/run     一次性仿真（含多站）
//   POST /api/vehicle/simulation/control 驾驶员控制续算

import type {
  SimulationControlRequest,
  SimulationResult,
  SimulationRunRequest,
  SimulationRunResponse,
} from '../types/vehicle';

const API_BASE = '/api';

/**
 * 调用后端车辆仿真接口，返回解析后的 SimulationResult。
 * toStationId > fromStationId+1 时后端自动多站连续仿真。
 */
export async function runVehicleSimulation(
  request?: SimulationRunRequest,
): Promise<SimulationResult> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: request ? JSON.stringify(request) : undefined,
  });

  if (!res.ok) {
    throw new Error(`车辆仿真接口调用失败: ${res.status}`);
  }

  const body: SimulationRunResponse = await res.json();
  if (!body.success) {
    throw new Error(body.message || '车辆仿真接口返回失败');
  }
  return body.data;
}

/**
 * 驾驶员控制续算：从当前帧状态开始，根据 currentMode 和 controlCommand 续算后续 states。
 * 前端用返回的新 states 替换当前帧之后的旧 states 继续播放。
 */
export async function callVehicleControl(
  request: SimulationControlRequest,
): Promise<SimulationResult> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/control`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });

  if (!res.ok) {
    throw new Error(`控制接口调用失败: ${res.status}`);
  }

  const body: SimulationRunResponse = await res.json();
  if (!body.success) {
    throw new Error(body.message || '控制接口返回失败');
  }
  return body.data;
}
