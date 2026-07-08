// 郭逸晨车载模块（成员三）——车辆仿真 API 调用
//
// 对应后端 backend/src/main/java/com/bjtu/railtransit/vehicle/controller/VehicleSimulationController.java
// 的 POST /api/vehicle/simulation/run 接口。第一版请求体为空，后端使用内置演示配置
// 一次性计算并返回完整仿真结果（states/summary/stopResult/safetyEvents）。

import type { SimulationResult, SimulationRunResponse } from '../types/vehicle';

const API_BASE = '/api';

/**
 * 调用后端车辆仿真接口，返回解析后的 SimulationResult。
 *
 * 页面展示的所有运行数据（states/summary/stopResult/safetyEvents）均来自本次
 * 后端响应，不使用前端写死数组。
 */
export async function runVehicleSimulation(): Promise<SimulationResult> {
  const res = await fetch(`${API_BASE}/vehicle/simulation/run`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
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
