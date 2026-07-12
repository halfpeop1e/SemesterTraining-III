import type { Protocol704Status } from '../types/protocol704';
import type { SimulationControlRequest } from '../types/vehicle';

const API_BASE = '/api';

export async function getProtocol704Status(trainId = 'T1'): Promise<Protocol704Status> {
  const res = await fetch(`${API_BASE}/vehicle/protocol704/status?trainId=${encodeURIComponent(trainId)}`);
  if (!res.ok) throw new Error(`704状态接口调用失败: ${res.status}`);
  return res.json();
}

export async function connectProtocol704(trainId = 'T1'): Promise<string> {
  const res = await fetch(`${API_BASE}/vehicle/protocol704/connect?trainId=${encodeURIComponent(trainId)}`, { method: 'POST' });
  if (!res.ok) throw new Error(`704连接失败: ${res.status}`);
  return res.text();
}

export async function disconnectProtocol704(trainId = 'T1'): Promise<string> {
  const res = await fetch(`${API_BASE}/vehicle/protocol704/disconnect?trainId=${encodeURIComponent(trainId)}`, { method: 'POST' });
  if (!res.ok) throw new Error(`704断开失败: ${res.status}`);
  return res.text();
}

export async function resetProtocol704(trainId = 'T1'): Promise<string> {
  const res = await fetch(`${API_BASE}/vehicle/protocol704/reset?trainId=${encodeURIComponent(trainId)}`, { method: 'POST' });
  if (!res.ok) throw new Error(`704重置失败: ${res.status}`);
  return res.text();
}

export async function sendTestFrame(trainId = 'T1', type: string): Promise<Protocol704Status> {
  const res = await fetch(`${API_BASE}/vehicle/protocol704/test-frame?trainId=${encodeURIComponent(trainId)}&type=${encodeURIComponent(type)}`, { method: 'POST' });
  if (!res.ok) throw new Error(`测试帧发送失败: ${res.status}`);
  return res.json();
}

/**
 * 只同步状态到 Bridge，不执行物理控制。点击 704 测试帧前调用，使 EB 从当前真实位置开始制动。
 * 复用 SimulationControlRequest 作为请求体，不重复定义同义结构。
 */
export async function syncProtocol704State(payload: SimulationControlRequest): Promise<{ ok: boolean; message?: string }> {
  const res = await fetch(`${API_BASE}/vehicle/protocol704/sync-state`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    return { ok: false, message: `同步状态失败: ${res.status}` };
  }
  const data = await res.json();
  return { ok: data.ok === true, message: data.message };
}
