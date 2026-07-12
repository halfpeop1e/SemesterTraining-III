import {
  createContext,
  useContext,
  useState,
  useCallback,
  useEffect,
  type ReactNode,
} from 'react';
import { startSimulation, resetSimulation, pauseSimulation } from '../api/dispatch';
import { useSimulationWS } from '../hooks/useSimulationWS';
import type { SimulationSnapshot } from '../types/dispatch';

export const SPEED_OPTIONS = [
  { label: '1x', steps: 1 },
  { label: '2x', steps: 2 },
  { label: '5x', steps: 5 },
  { label: '10x', steps: 10 },
] as const;

interface SimulationContextValue {
  snapshot: SimulationSnapshot | null;
  isRunning: boolean;
  loading: boolean;
  error: string;
  speedIndex: number;
  start: () => Promise<void>;
  stop: () => void;
  step: () => Promise<void>;
  reset: () => Promise<void>;
  setSpeed: (i: number) => void;
  setError: (msg: string) => void;
}

const SimulationContext = createContext<SimulationContextValue | null>(null);

/**
 * 仿真运行时提升到 App 层级。
 * 数据流: 后端 authoritativeTick → WebSocket/HTTP 轮询 → useSimulationWS → context
 * isRunning 以快照 running 字段为权威（兼容本地 start 按钮）。
 */
export function SimulationProvider({ children }: { children: ReactNode }) {
  const ws = useSimulationWS();

  const [isRunning, setIsRunning] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [speedIndex, setSpeedIndex] = useState(0);

  // 后端快照带 running 时同步 UI（刷新页面/WS 重连后仍正确）
  useEffect(() => {
    const snap = ws.snapshot;
    if (!snap) return;
    if (typeof snap.running === 'boolean') {
      setIsRunning(snap.running);
      return;
    }
  }, [ws.snapshot]);

  const start = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      await startSimulation(3600);
      await ws.refresh();
      setIsRunning(true);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [ws.refresh]);

  const stop = useCallback(async () => {
    setIsRunning(false);
    try {
      await pauseSimulation();
    } catch {
      // ignore
    }
  }, []);

  const step = useCallback(async () => {
    setLoading(true);
    try {
      await ws.refresh();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [ws.refresh]);

  const reset = useCallback(async () => {
    setIsRunning(false);
    setLoading(true);
    try {
      await resetSimulation();
      ws.clear();
      setError('');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [ws.clear]);

  const setSpeed = useCallback((i: number) => setSpeedIndex(i), []);

  const effectiveRunning =
    isRunning ||
    (ws.snapshot?.running === true);

  const value: SimulationContextValue = {
    snapshot: ws.snapshot,
    isRunning: effectiveRunning,
    loading,
    error: error || ws.error,
    speedIndex,
    start,
    stop,
    step,
    reset,
    setSpeed,
    setError,
  };

  return <SimulationContext.Provider value={value}>{children}</SimulationContext.Provider>;
}

export function useSimulation(): SimulationContextValue {
  const ctx = useContext(SimulationContext);
  if (!ctx) throw new Error('useSimulation 必须在 SimulationProvider 内使用');
  return ctx;
}
