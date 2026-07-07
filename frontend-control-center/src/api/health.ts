const API_BASE = '/api';

export interface HealthResponse {
  success: boolean;
  message: string;
  data: string;
}

export async function checkHealth(): Promise<HealthResponse> {
  const res = await fetch(`${API_BASE}/health`);
  if (!res.ok) {
    throw new Error(`Health check failed: ${res.status}`);
  }
  return res.json();
}
