import { useEffect, useRef } from 'react';
import type { TrainState } from '../../../../../types/vehicle';
import type { ForcePoint } from '../utils/pathBuilder';

const TOTAL_MOTORS = 16;
const DEFAULT_MASS_KG = 225_000;
const MAX_HISTORY_POINTS = 2000;
const MIN_SAMPLE_GAP = 0.15;

/** 从后端 resistanceDecel (m/s²) 计算阻力 kN = mass * decel / 1000 */
function resistForceKN(decel: number | undefined, massKg: number): number {
  return ((decel ?? 0) * massKg) / 1000;
}

interface UseForceHistoryOptions {
  currentState: TrainState | null;
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  trainMass?: number;
}

interface CurrentForcePoint {
  time: number;
  tractionKN: number;
  brakeKN: number;
  resistKN: number;
  netKN: number;
  vKmh: number;
  phase: string;
}

export function useForceHistory({
  currentState,
  status,
  trainMass = DEFAULT_MASS_KG,
}: UseForceHistoryOptions) {
  const isRunning = status === 'playing' || status === 'finished' || status === 'error';
  const historyRef = useRef<ForcePoint[]>([]);
  const lastRecordedTimeRef = useRef(-1);
  const prevStatusRef = useRef(status);
  const massKg = trainMass;

  const curTime = currentState?.time ?? 0;
  const vKmh = (currentState?.velocity ?? 0) * 3.6;
  const phase = currentState?.phase ?? 'stopped';

  const tractionKN = (currentState?.tractionForce ?? 0) / 1000;
  const brakeKN = (currentState?.brakeForce ?? 0) / 1000;
  const resistKN = resistForceKN(currentState?.resistanceDecel, massKg);
  const netKN = tractionKN - brakeKN - resistKN;

  // ── 清空历史：当 status 从非运行态进入运行态时重置（在 effect 中执行，不在渲染期间）──
  useEffect(() => {
    if (prevStatusRef.current !== status) {
      if (status === 'playing' || status === 'loading') {
        historyRef.current = [];
        lastRecordedTimeRef.current = -1;
      }
      prevStatusRef.current = status;
    }
  }, [status]);

  // ── 累积数据点 ──
  useEffect(() => {
    if (!isRunning || !currentState || curTime <= 0) return;
    const pts = historyRef.current;

    // 跳过倒退时间
    if (curTime <= lastRecordedTimeRef.current) return;
    // 跳过过密采样
    if (
      pts.length > 0 &&
      curTime - lastRecordedTimeRef.current < MIN_SAMPLE_GAP &&
      Math.abs(pts[pts.length - 1].tractionKN - tractionKN) < 2 &&
      Math.abs(pts[pts.length - 1].brakeKN - brakeKN) < 2
    ) {
      return;
    }

    lastRecordedTimeRef.current = curTime;
    pts.push({ time: curTime, tractionKN, brakeKN, resistKN, netKN });

    // 上限截断
    if (pts.length > MAX_HISTORY_POINTS) {
      pts.splice(0, pts.length - MAX_HISTORY_POINTS);
    }
  }, [isRunning, curTime, tractionKN, brakeKN, resistKN, netKN, currentState]);

  const currentPoint: CurrentForcePoint = {
    time: curTime,
    tractionKN,
    brakeKN,
    resistKN,
    netKN,
    vKmh,
    phase,
  };

  return { historyRef, currentPoint, isRunning };
}
