import { useCallback, useEffect, useRef, useState } from 'react';
import { PLOT_W, clampVal } from '../utils/pathBuilder';

const ZOOM_MIN_S = 10;
const ZOOM_MAX_S = 300;
const ZOOM_DEFAULT_S = 60;

interface UseCurveViewWindowOptions {
  curTime: number;
  isRunning: boolean;
}

export function useCurveViewWindow({ curTime, isRunning }: UseCurveViewWindowOptions) {
  const [windowDuration, setWindowDuration] = useState(ZOOM_DEFAULT_S);
  const [viewRight, setViewRight] = useState(ZOOM_DEFAULT_S);
  const [autoFollow, setAutoFollow] = useState(true);

  // ── 自动跟随 ──
  useEffect(() => {
    if (autoFollow && isRunning) {
      setViewRight(Math.max(curTime, windowDuration));
    }
  }, [curTime, autoFollow, isRunning, windowDuration]);

  // ── 拖拽（使用 React Pointer Events，与 LineRunView 一致）──
  const dragRef = useRef<{ startX: number; startViewRight: number } | null>(null);

  const handlePointerDown = useCallback(
    (e: React.PointerEvent<SVGSVGElement>) => {
      e.currentTarget.setPointerCapture(e.pointerId);
      dragRef.current = { startX: e.clientX, startViewRight: viewRight };
      setAutoFollow(false);
    },
    [viewRight],
  );

  const handlePointerMove = useCallback(
    (e: React.PointerEvent<SVGSVGElement>) => {
      const drag = dragRef.current;
      if (!drag) return;
      const dt = -((e.clientX - drag.startX) / PLOT_W) * windowDuration;
      setViewRight(Math.max(windowDuration, drag.startViewRight + dt));
    },
    [windowDuration],
  );

  const handlePointerUp = useCallback(
    (e: React.PointerEvent<SVGSVGElement>) => {
      e.currentTarget.releasePointerCapture(e.pointerId);
      dragRef.current = null;
    },
    [],
  );

  // ── 滚轮缩放（使用 React onWheel，与 LineRunView 一致）──
  const handleWheel = useCallback(
    (e: React.WheelEvent<SVGSVGElement>) => {
      e.preventDefault();
      const delta = e.deltaY > 0 ? 1.3 : 1 / 1.3;
      setWindowDuration((prev) => clampVal(prev * delta, ZOOM_MIN_S, ZOOM_MAX_S));
      setAutoFollow(false);
    },
    [],
  );

  // ── 回到最新 ──
  const returnToLatest = useCallback(() => {
    setAutoFollow(true);
    setViewRight(curTime);
  }, [curTime]);

  // ── 可视窗口 ──
  const tMax = Math.max(viewRight, windowDuration);
  const tMin = Math.max(0, tMax - windowDuration);

  return {
    tMin,
    tMax,
    windowDuration,
    autoFollow,
    handlePointerDown,
    handlePointerMove,
    handlePointerUp,
    handleWheel,
    returnToLatest,
  };
}
