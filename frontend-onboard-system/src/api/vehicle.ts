// 郭逸晨车载模块（成员三）——车辆仿真 API 调用
//
// 对应后端 VehicleSimulationController POST /api/vehicle/simulation/run。
// 阶段4B：支持可选的 fromStationId / toStationId 参数，不传时后端默认 1→2。

import type { SimulationResult, SimulationRunRequest, SimulationRunResponse } from '../types/vehicle';

const API_BASE = '/api';

/**
 * 调用后端车辆仿真接口，返回解析后的 SimulationResult。
 *
 * @param request 可选请求体，包含 fromStationId 和 toStationId。
 *                不传或传 undefined 时后端默认使用 1（郭公庄）→ 2（丰台科技园）。
 */
export async function runVehicleSimulation(
  request?: SimulationRunRequest,
): Promise<SimulationResult> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/run`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    // request 有值时发送 JSON body；无值时发送空 body，后端 required=false 会用默认值。
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
