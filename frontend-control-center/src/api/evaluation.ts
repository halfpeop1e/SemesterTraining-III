import type {
  ApiResponse,
  EnergyCalculateRequest,
  EnergyRecord,
  PeakPowerResult,
  EvaluationReport,
  EvaluationRequest,
  StopErrorResult,
  PunctualityResult,
  ComfortResult,
  SafetyEvent,
} from '../types';

const API_BASE = '/api/evaluation';

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    throw new Error(`API error: ${res.status}`);
  }
  const json: ApiResponse<T> = await res.json();
  if (!json.success) {
    throw new Error(json.message || 'API request failed');
  }
  return json.data;
}

const post = <T>(url: string, data: unknown) =>
  request<T>(url, { method: 'POST', body: JSON.stringify(data) });

// ==================== 综合报告 ====================
export function generateReport(
  energyReq: EnergyCalculateRequest,
  evalReq: EvaluationRequest
): Promise<EvaluationReport> {
  return post<EvaluationReport>('/report', {
    energyRequest: energyReq,
    evalRequest: evalReq,
  });
}

// ==================== 能耗 ====================
export function calculateEnergy(req: EnergyCalculateRequest): Promise<EnergyRecord[]> {
  return post<EnergyRecord[]>('/energy/calculate', req);
}

export function detectPeak(req: EnergyCalculateRequest): Promise<PeakPowerResult> {
  return post<PeakPowerResult>('/energy/peak', req);
}

// ==================== 评估指标 ====================
export function evaluateStopError(req: EvaluationRequest): Promise<StopErrorResult[]> {
  return post<StopErrorResult[]>('/stop-error', req);
}

export function evaluatePunctuality(req: EvaluationRequest): Promise<PunctualityResult> {
  return post<PunctualityResult>('/punctuality', req);
}

export function evaluateComfort(req: EvaluationRequest): Promise<ComfortResult> {
  return post<ComfortResult>('/comfort', req);
}

export function collectSafetyEvents(req: EvaluationRequest): Promise<SafetyEvent[]> {
  return post<SafetyEvent[]>('/safety', req);
}
