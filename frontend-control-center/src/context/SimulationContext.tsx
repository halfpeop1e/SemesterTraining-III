import {
  createContext,
  useContext,
  useState,
  useEffect,
  useRef,
  useCallback,
  type ReactNode,
} from 'react';
import { startSimulation, stepSimulation, resetSimulation, getSnapshot } from '../api/dispatch';
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
 * 因为 App 用 module 状态硬切换页面（Dispatch/LineSignal 互相卸载），
 * 若把 step 循环放在 Dispatch 内部，切走即卸载 → 仿真冻结。
 * Provider 位于页面切换层之上，interval 跨页面存活，两个页面共用同一份实时快照。
 */
export function SimulationProvider({ children }: { children: ReactNode }) {
  const [snapshot, setSnapshot] = useState<SimulationSnapshot | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [speedIndex, setSpeedIndex] = useState(0);
  const autoRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // 用 ref 持有最新速度，避免 speedIndex 变化时重建 interval
  const speedRef = useRef(speedIndex);
  speedRef.current = speedIndex;

  const refresh = useCallback(async () => {
    try {
      setError('');
      setSnapshot(await getSnapshot());
    } catch (e: any) {
      setError(e.message);
    }
  }, []);

  const start = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      await startSimulation(3600);
      await refresh();
      setIsRunning(true);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [refresh]);

  // 推进循环：仅在 provider 挂载期间运行，跨页面切换不中断
  useEffect(() => {
    if (!isRunning) return;
    if (autoRef.current) clearInterval(autoRef.current);
    autoRef.current = setInterval(async () => {
      try {
        await stepSimulation(SPEED_OPTIONS[speedRef.current].steps);
        setSnapshot(await getSnapshot());
      } catch {
        /* 单步失败不中断循环，下一拍继续 */
      }
    }, 500);
    return () => {
      if (autoRef.current) {
        clearInterval(autoRef.current);
        autoRef.current = null;
      }
    };
  }, [isRunning]);

  useEffect(
    () => () => {
      if (autoRef.current) clearInterval(autoRef.current);
    },
    [],
  );

  const stop = useCallback(() => {
    if (autoRef.current) {
      clearInterval(autoRef.current);
      autoRef.current = null;
    }
    setIsRunning(false);
  }, []);

  const step = useCallback(async () => {
    setLoading(true);
    try {
      await stepSimulation(1);
      await refresh();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [refresh]);

  const setSpeed = useCallback((i: number) => setSpeedIndex(i), []);

  const reset = useCallback(async () => {
    stop();
    setLoading(true);
    try {
      await resetSimulation();
      setSnapshot(null);
      setError('');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [stop]);

  const value: SimulationContextValue = {
    snapshot,
    isRunning,
    loading,
    error,
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
