import { memo } from 'react';
import { AXIS_Y } from '../utils/coordinates';

interface StopWindowOverlayProps {
  stopState: string;
  stopWindowLeftX: number;
  stopWindowRightX: number;
  targetX: number;
  actualStopX: number | null;
  stopError: number | undefined;
}

function StopWindowOverlay({
  stopState,
  stopWindowLeftX,
  stopWindowRightX,
  targetX,
  actualStopX,
  stopError,
}: StopWindowOverlayProps) {
  return (
    <g className={`line-run-view__stop-window line-run-view__stop-window--${stopState}`}>
      <rect
        x={Math.min(stopWindowLeftX, stopWindowRightX)}
        y={AXIS_Y - 34}
        width={Math.max(Math.abs(stopWindowRightX - stopWindowLeftX), 4)}
        height="68"
        rx="4"
      />
      <line x1={targetX} y1={AXIS_Y - 46} x2={targetX} y2={AXIS_Y + 46} />
      <text x={targetX} y={AXIS_Y + 68}>±0.5m 停车窗</text>
      {actualStopX !== null && stopError != null && (
        <>
          <line className="line-run-view__actual-stop-line" x1={actualStopX} y1={AXIS_Y - 58} x2={actualStopX} y2={AXIS_Y + 58} />
          <text className="line-run-view__actual-stop-text" x={actualStopX} y={AXIS_Y - 70}>
            实停 {stopError.toFixed(2)}m
          </text>
        </>
      )}
    </g>
  );
}

export default memo(StopWindowOverlay);
