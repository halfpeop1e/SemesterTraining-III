import type { Protocol704Status } from '../types/protocol704';

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
