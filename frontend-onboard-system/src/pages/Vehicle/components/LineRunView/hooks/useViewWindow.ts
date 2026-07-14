import { useCallback, useEffect, useRef, useState } from 'react';
import { LINE_MAP } from '../../../data/lineMap';
import {
  MAP_LEFT, MAP_RIGHT, MAP_WIDTH, MIN_WINDOW_M,
  clamp, normalizeWindow, expandWindowAround,
  buildFollowWindow, buildFinishedWindow,
} from '../utils/coordinates';
import type { ViewWindow } from '../types';

interface UseViewWindowOptions {
  currentPosition: number;
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  absoluteTargetStopPosition: number;
  absoluteActualStopPosition: number | null;
  /** 停车后的实停位置（绝对里程），用于 finished 状态时回列车的目标中心 */
  finishedStopAbsPosition: number | null;
}

export function useViewWindow({
  currentPosition,
  status,
  absoluteTargetStopPosition,
  absoluteActualStopPosition,
  finishedStopAbsPosition,
}: UseViewWindowOptions) {
  const [viewWindow, setViewWindow] = useState<ViewWindow>(
    () => buildFollowWindow(currentPosition),
  );
  const [isFollowing, setIsFollowing] = useState(true);
  const [isDragging, setIsDragging] = useState(false);
  const dragRef = useRef<{ clientX: number; window: ViewWindow } | null>(null);

  // ── 自动跟随 ──
  useEffect(() => {
    if (!isFollowing) return;

    if (status === 'finished') {
      setViewWindow(buildFinishedWindow(absoluteTargetStopPosition, absoluteActualStopPosition));
      return;
    }

    setViewWindow(buildFollowWindow(currentPosition));
  }, [currentPosition, absoluteTargetStopPosition, absoluteActualStopPosition, isFollowing, status]);

  // ── 坐标映射 ──
  const xScale = useCallback(
    (position: number) => {
      const ratio = (position - viewWindow.start) / (viewWindow.end - viewWindow.start);
      return MAP_LEFT + ratio * MAP_WIDTH;
    },
    [viewWindow],
  );

  // ── 缩放 ──
  const zoomAt = useCallback(
    (factor: number, anchorPosition: number, keepFollowing = false) => {
      if (!keepFollowing) setIsFollowing(false);
      setViewWindow((current) => {
        const width = current.end - current.start;
        const nextWidth = clamp(width * factor, MIN_WINDOW_M, LINE_MAP.totalLengthM);
        const anchorRatio = (anchorPosition - current.start) / width;
        return normalizeWindow({
          start: anchorPosition - nextWidth * anchorRatio,
          end: anchorPosition + nextWidth * (1 - anchorRatio),
        });
      });
    },
    [],
  );

  // ── 滚轮 ──
  const handleWheel = useCallback(
    (event: React.WheelEvent<SVGSVGElement>) => {
      event.preventDefault();
      setIsFollowing(false);
      const rect = event.currentTarget.getBoundingClientRect();
      const pointerRatio = clamp((event.clientX - rect.left) / rect.width, 0, 1);
      const anchorPosition = viewWindow.start + pointerRatio * (viewWindow.end - viewWindow.start);
      zoomAt(event.deltaY < 0 ? 0.82 : 1.22, anchorPosition, true);
    },
    [viewWindow, zoomAt],
  );

  // ── 拖拽 ──
  const handlePointerDown = useCallback(
    (event: React.PointerEvent<SVGSVGElement>) => {
      event.currentTarget.setPointerCapture(event.pointerId);
      setIsFollowing(false);
      setIsDragging(true);
      dragRef.current = { clientX: event.clientX, window: viewWindow };
    },
    [viewWindow],
  );

  const handlePointerMove = useCallback(
    (event: React.PointerEvent<SVGSVGElement>) => {
      if (!dragRef.current) return;
      const dx = event.clientX - dragRef.current.clientX;
      const metersPerPixel = (dragRef.current.window.end - dragRef.current.window.start) / MAP_WIDTH;
      const offsetM = dx * metersPerPixel;
      setViewWindow(
        normalizeWindow({
          start: dragRef.current.window.start - offsetM,
          end: dragRef.current.window.end - offsetM,
        }),
      );
    },
    [],
  );

  const handlePointerUp = useCallback(
    (event: React.PointerEvent<SVGSVGElement>) => {
      event.currentTarget.releasePointerCapture(event.pointerId);
      setIsDragging(false);
      dragRef.current = null;
    },
    [],
  );

  // ── 复位 ──
  const resetView = useCallback(() => {
    setIsFollowing(false);
    setViewWindow({ start: LINE_MAP.lineStartM, end: LINE_MAP.lineEndM });
  }, []);

  // ── 回到列车（保持当前 span，只移动中心） ──
  const returnToTrain = useCallback(() => {
    const center =
      status === 'finished' && finishedStopAbsPosition != null
        ? finishedStopAbsPosition
        : currentPosition;
    setViewWindow((current) => {
      const currentSpan = current.end - current.start;
      return expandWindowAround(center, currentSpan);
    });
  }, [status, finishedStopAbsPosition, currentPosition]);

  return {
    viewWindow,
    isFollowing,
    isDragging,
    xScale,
    zoomAt,
    handleWheel,
    handlePointerDown,
    handlePointerMove,
    handlePointerUp,
    resetView,
    returnToTrain,
  };
}
