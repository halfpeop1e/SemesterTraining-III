import { useEffect, useMemo, useRef, useState } from 'react';
import { LINE_MAP } from '../data/lineMap';
import type { StationStop, StopResult, TrainState } from '../../../types/vehicle';
import './LineRunView.css';

export interface LineRunViewProps {
  trainId: string;
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  currentState: TrainState | null;
  startPosition: number;
  targetStopPosition: number;
  speedLimit: number;
  stopResult: StopResult | null;
  positionOffset?: number;
  stationStops?: StationStop[];
}

interface ViewWindow {
  start: number;
  end: number;
}

const SVG_WIDTH = 1200;
const SVG_HEIGHT = 390;
const AXIS_Y = 190;
const MAP_LEFT = 80;
const MAP_RIGHT = 1120;
const MAP_WIDTH = MAP_RIGHT - MAP_LEFT;
const MIN_WINDOW_M = 60;
const FOLLOW_MIN_WINDOW_M = 1800;
const FINISHED_WINDOW_M = 80;
const TICK_INTERVAL_M = 500;
const STOP_WINDOW_TOLERANCE_M = 0.5;

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function formatMeters(value: number) {
  if (Math.abs(value) >= 1000) {
    return `${(value / 1000).toFixed(1)}km`;
  }
  return `${Math.round(value)}m`;
}

function normalizeWindow(window: ViewWindow): ViewWindow {
  const lineStart = LINE_MAP.lineStartM;
  const lineEnd = LINE_MAP.lineEndM;
  const lineLength = lineEnd - lineStart;
  let width = window.end - window.start;

  if (width < MIN_WINDOW_M) {
    width = MIN_WINDOW_M;
  }
  if (width > lineLength) {
    return { start: lineStart, end: lineEnd };
  }

  let start = window.start;
  let end = start + width;

  if (start < lineStart) {
    start = lineStart;
    end = start + width;
  }
  if (end > lineEnd) {
    end = lineEnd;
    start = end - width;
  }

  return { start, end };
}

function expandWindowAround(center: number, width: number): ViewWindow {
  return normalizeWindow({ start: center - width / 2, end: center + width / 2 });
}

function buildFollowWindow(position: number): ViewWindow {
  const stations = LINE_MAP.stations;
  const nextStationIndex = stations.findIndex((station) => station.positionM >= position);

  if (nextStationIndex === -1) {
    return normalizeWindow({
      start: stations[Math.max(stations.length - 3, 0)].positionM - 300,
      end: LINE_MAP.lineEndM,
    });
  }

  const startIndex = Math.max(nextStationIndex - 2, 0);
  const endIndex = Math.min(nextStationIndex + 1, stations.length - 1);
  const start = Math.min(LINE_MAP.lineStartM, stations[startIndex].positionM) - 160;
  const end = stations[endIndex].positionM + 320;
  const natural = normalizeWindow({ start, end });
  const width = Math.max(natural.end - natural.start, FOLLOW_MIN_WINDOW_M);

  return expandWindowAround(position, width);
}

function buildFinishedWindow(targetStopPosition: number, actualStopPosition: number | null): ViewWindow {
  const center = actualStopPosition === null ? targetStopPosition : (targetStopPosition + actualStopPosition) / 2;
  return expandWindowAround(center, FINISHED_WINDOW_M);
}

function normalizeStopWindowState(stopWindowState: string | undefined) {
  return (stopWindowState ?? 'not_accurate').toLowerCase();
}

function describeStopWindowState(stopWindowState: string | undefined) {
  const normalized = normalizeStopWindowState(stopWindowState);
  if (normalized === 'in_window') {
    return '停准窗内';
  }
  if (normalized === 'overshoot') {
    return '冲标';
  }
  if (normalized === 'undershoot') {
    return '欠标';
  }
  return '未停准';
}

/** 计算下一站驾驶辅助数据（方案 B 面板）*/
function computeDrivingAid(
  currentPosition: number,
  currentVelocity: number,
  speedLimit: number,
  stationStops: StationStop[] | undefined,
  targetStopPosition: number,
  positionOffset: number,
  stopResult: StopResult | null,
) {
  // 找下一个未到达的停车站（绝对里程坐标）
  let nextStopName = '终点';
  let nextStopAbsM = positionOffset + targetStopPosition;

  if (stationStops && stationStops.length > 0) {
    for (const stop of stationStops) {
      const absTarget = positionOffset + stop.targetPosition;
      if (absTarget >= currentPosition - 0.5) {
        nextStopName = stop.stationName;
        nextStopAbsM = absTarget;
        break;
      }
    }
  }

  const distanceM = Math.max(0, nextStopAbsM - currentPosition);

  // 推荐制动点：v² / (2 * a_brake)，a_brake = 1.1 m/s²（SIM 常用制动默认值）
  const SIM_BRAKE_DECEL = 1.1; // m/s²
  const brakingDistanceM = currentVelocity > 0 ? (currentVelocity * currentVelocity) / (2 * SIM_BRAKE_DECEL) : 0;
  const brakeMarginM = distanceM - brakingDistanceM;

  // 预计剩余时间（简化：匀速估算）
  const etaS = currentVelocity > 0.1 ? distanceM / currentVelocity : null;

  // 最近到站误差（上一站结果）
  let lastStopError: number | null = null;
  if (stationStops) {
    const passed = stationStops.filter((s) => positionOffset + s.targetPosition < currentPosition - 0.5 && s.actualPosition > 0);
    if (passed.length > 0) lastStopError = passed[passed.length - 1].stopError;
  } else if (stopResult) {
    lastStopError = stopResult.stopError;
  }

  return {
    nextStopName,
    distanceM,
    brakingDistanceM,
    brakeMarginM,
    etaS,
    speedLimitKmh: speedLimit * 3.6,
    lastStopError,
  };
}

function formatEta(etaS: number | null): string {
  if (etaS === null || etaS > 9999) return '—';
  if (etaS < 60) return `${Math.round(etaS)}s`;
  return `${Math.floor(etaS / 60)}m${Math.round(etaS % 60)}s`;
}

function LineRunView({
  trainId,
  status,
  currentState,
  startPosition,
  targetStopPosition,
  speedLimit,
  stopResult,
  positionOffset = 0,
  stationStops,
}: LineRunViewProps) {
  // 坐标优先级：TrainState.absolutePosition（多站时已正确填充）> positionOffset + position
  const relativePosition = currentState?.position ?? startPosition;
  const absolutePosition = currentState?.absolutePosition !== undefined
    ? currentState.absolutePosition
    : positionOffset + relativePosition;
  const currentPosition = clamp(absolutePosition, LINE_MAP.lineStartM, LINE_MAP.lineEndM);
  const currentVelocity = currentState?.velocity ?? 0;

  // 方案 B：下一站驾驶辅助数据
  const drivingAid = useMemo(
    () => computeDrivingAid(
      currentPosition,
      currentVelocity,
      speedLimit,
      stationStops,
      targetStopPosition,
      positionOffset,
      stopResult,
    ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [currentPosition, currentVelocity, speedLimit, stationStops, targetStopPosition, positionOffset, stopResult],
  );

  // startPosition 和 targetStopPosition 加偏移，用于全线地图定位
  const absoluteStartPosition = positionOffset + startPosition;
  const absoluteTargetStopPosition = positionOffset + targetStopPosition;
  const actualStopPosition = stopResult ? stopResult.actualStopPosition : null;
  // actualStopPosition 是从 fromStation 起的累积坐标，需要加 lineStartAbsM 得到全线绝对里程
  const absoluteActualStopPosition = actualStopPosition === null ? null : positionOffset + actualStopPosition;
  const [viewWindow, setViewWindow] = useState<ViewWindow>(() => buildFollowWindow(currentPosition));
  const [isFollowing, setIsFollowing] = useState(true);
  const [isDragging, setIsDragging] = useState(false);
  const dragRef = useRef<{ clientX: number; window: ViewWindow } | null>(null);

  useEffect(() => {
    if (!isFollowing) {
      return;
    }

    if (status === 'finished') {
      // 阶段4B：使用绝对坐标定位停车窗视图
      setViewWindow(buildFinishedWindow(absoluteTargetStopPosition, absoluteActualStopPosition));
      return;
    }

    setViewWindow(buildFollowWindow(currentPosition));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [absoluteActualStopPosition, absoluteTargetStopPosition, currentPosition, isFollowing, status]);

  const xScale = (position: number) => {
    const ratio = (position - viewWindow.start) / (viewWindow.end - viewWindow.start);
    return MAP_LEFT + ratio * MAP_WIDTH;
  };

  const visibleStations = useMemo(
    () =>
      LINE_MAP.stations.filter(
        (station) => station.positionM >= viewWindow.start && station.positionM <= viewWindow.end,
      ),
    [viewWindow],
  );

  const ticks = useMemo(() => {
    const dynamicInterval = viewWindow.end - viewWindow.start <= 220 ? 10 : TICK_INTERVAL_M;
    const firstTick = Math.ceil(viewWindow.start / dynamicInterval) * dynamicInterval;
    const values: number[] = [];
    for (let tick = firstTick; tick <= viewWindow.end; tick += dynamicInterval) {
      values.push(tick);
    }
    return values;
  }, [viewWindow]);

  const sectionStations = useMemo(() => {
    const previous = [...LINE_MAP.stations].reverse().find((station) => station.positionM <= currentPosition);
    const next = LINE_MAP.stations.find((station) => station.positionM > currentPosition);
    return { previous, next };
  }, [currentPosition]);

  const zoomAt = (factor: number, anchorPosition: number, keepFollowing = false) => {
    if (!keepFollowing) {
      setIsFollowing(false);
    }
    setViewWindow((current) => {
      const width = current.end - current.start;
      const nextWidth = clamp(width * factor, MIN_WINDOW_M, LINE_MAP.totalLengthM);
      const anchorRatio = (anchorPosition - current.start) / width;
      return normalizeWindow({
        start: anchorPosition - nextWidth * anchorRatio,
        end: anchorPosition + nextWidth * (1 - anchorRatio),
      });
    });
  };

  const handleWheel = (event: React.WheelEvent<SVGSVGElement>) => {
    event.preventDefault();
    setIsFollowing(false);
    const rect = event.currentTarget.getBoundingClientRect();
    const pointerRatio = clamp((event.clientX - rect.left) / rect.width, 0, 1);
    const anchorPosition = viewWindow.start + pointerRatio * (viewWindow.end - viewWindow.start);
    zoomAt(event.deltaY < 0 ? 0.82 : 1.22, anchorPosition, true);
  };

  const handlePointerDown = (event: React.PointerEvent<SVGSVGElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    setIsFollowing(false);
    setIsDragging(true);
    dragRef.current = { clientX: event.clientX, window: viewWindow };
  };

  const handlePointerMove = (event: React.PointerEvent<SVGSVGElement>) => {
    if (!dragRef.current) {
      return;
    }
    const dx = event.clientX - dragRef.current.clientX;
    const metersPerPixel = (dragRef.current.window.end - dragRef.current.window.start) / MAP_WIDTH;
    const offsetM = dx * metersPerPixel;
    setViewWindow(
      normalizeWindow({
        start: dragRef.current.window.start - offsetM,
        end: dragRef.current.window.end - offsetM,
      }),
    );
  };

  const handlePointerUp = (event: React.PointerEvent<SVGSVGElement>) => {
    event.currentTarget.releasePointerCapture(event.pointerId);
    setIsDragging(false);
    dragRef.current = null;
  };

  const resetView = () => {
    setIsFollowing(false);
    setViewWindow({ start: LINE_MAP.lineStartM, end: LINE_MAP.lineEndM });
  };

  const returnToTrain = () => {
    // 方案 A：不设 isFollowing=true，避免 useEffect 用 buildFollowWindow 覆盖当前 span。
    // 只移动视图中心到列车位置，span（缩放比例）完全保持不变。
    // "复位"按钮才恢复默认全线窗口。
    setViewWindow((current) => {
      const currentSpan = current.end - current.start;
      const center =
        status === 'finished' && stopResult?.actualStopPosition !== undefined
          ? positionOffset + stopResult.actualStopPosition
          : currentPosition;
      return expandWindowAround(center, currentSpan);
    });
    // isFollowing 保持 false，useEffect 不触发，span 不被覆盖。
  };

  // 阶段4B：所有坐标都用绝对里程，用于在全线地图上正确定位
  const targetX = xScale(absoluteTargetStopPosition);
  const startX = xScale(absoluteStartPosition);
  const trainX = xScale(currentPosition);
  const stopWindowLeftX = xScale(absoluteTargetStopPosition - STOP_WINDOW_TOLERANCE_M);
  const stopWindowRightX = xScale(absoluteTargetStopPosition + STOP_WINDOW_TOLERANCE_M);
  const actualStopX = absoluteActualStopPosition === null ? null : xScale(absoluteActualStopPosition);
  const speedLimitKmh = speedLimit * 3.6;
  const targetPathEnd = Math.max(currentPosition, absoluteTargetStopPosition);
  const targetPathStart = Math.min(currentPosition, absoluteTargetStopPosition);
  const stopState = normalizeStopWindowState(stopResult?.stopWindowState);

  return (
    <section className="line-run-view" aria-label="线路运行视图">
      <div className="line-run-view__header">
        <div>
          <div className="line-run-view__eyebrow">Line run monitor</div>
          <h3>线路运行视图</h3>
        </div>
        <div className="line-run-view__controls" aria-label="线路视图控制">
          <button type="button" onClick={() => zoomAt(0.72, currentPosition)} title="放大">
            +
          </button>
          <button type="button" onClick={() => zoomAt(1.38, currentPosition)} title="缩小">
            -
          </button>
          <button type="button" onClick={resetView} title="复位">
            复位
          </button>
          <button type="button" onClick={returnToTrain} title="回到列车">
            回到列车
          </button>
        </div>
      </div>

      <div className="line-run-view__meta">
        <span>状态 {status}</span>
        <span>{isFollowing ? '镜头跟随' : '自由查看'}</span>
        <span>位置 {currentPosition.toFixed(1)} m</span>
        <span>速度 {(currentState?.velocity ?? 0).toFixed(2)} m/s</span>
        <span>限速 {speedLimitKmh.toFixed(0)} km/h</span>
        {sectionStations.next && <span>下一站 {sectionStations.next.displayNameOverride ?? sectionStations.next.displayName}</span>}
        {stopResult && <span>停车窗 {describeStopWindowState(stopResult.stopWindowState)}</span>}
      </div>

      <svg
        className={`line-run-view__svg ${isDragging ? 'is-dragging' : ''}`}
        viewBox={`0 0 ${SVG_WIDTH} ${SVG_HEIGHT}`}
        role="img"
        aria-label="真实线路比例运行图"
        onWheel={handleWheel}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
      >
        <defs>
          <linearGradient id="lineRunAxisGradient" x1="0" x2="1" y1="0" y2="0">
            <stop offset="0%" stopColor="#0f172a" />
            <stop offset="100%" stopColor="#1e293b" />
          </linearGradient>
          <filter id="lineRunGlow" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="4" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        <rect className="line-run-view__panel" x="16" y="18" width="1168" height="350" rx="10" />

        <g className="line-run-view__ticks">
          {ticks.map((tick) => {
            const x = xScale(tick);
            return (
              <g key={tick}>
                <line x1={x} y1="76" x2={x} y2="306" />
                <line className="line-run-view__tick-mark" x1={x} y1={AXIS_Y + 18} x2={x} y2={AXIS_Y + 30} />
                <text x={x} y={AXIS_Y + 48}>{formatMeters(tick)}</text>
              </g>
            );
          })}
        </g>

        <line className="line-run-view__axis-shadow" x1={MAP_LEFT} y1={AXIS_Y} x2={MAP_RIGHT} y2={AXIS_Y} />
        <line className="line-run-view__axis line-run-view__axis--base" x1={MAP_LEFT} y1={AXIS_Y} x2={MAP_RIGHT} y2={AXIS_Y} />
        <line className="line-run-view__axis line-run-view__axis--passed" x1={xScale(absoluteStartPosition)} y1={AXIS_Y} x2={trainX} y2={AXIS_Y} />
        <line className="line-run-view__axis line-run-view__axis--target-path" x1={xScale(targetPathStart)} y1={AXIS_Y} x2={xScale(targetPathEnd)} y2={AXIS_Y} />
        {sectionStations.next && (
          <line
            className="line-run-view__axis line-run-view__axis--current"
            x1={xScale(sectionStations.previous?.positionM ?? LINE_MAP.lineStartM)}
            y1={AXIS_Y}
            x2={xScale(sectionStations.next.positionM)}
            y2={AXIS_Y}
          />
        )}

        {status === 'finished' && (
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
            {actualStopX !== null && (
              <>
                <line className="line-run-view__actual-stop-line" x1={actualStopX} y1={AXIS_Y - 58} x2={actualStopX} y2={AXIS_Y + 58} />
                <text className="line-run-view__actual-stop-text" x={actualStopX} y={AXIS_Y - 70}>
                  实停 {stopResult?.stopError.toFixed(2)}m
                </text>
              </>
            )}
          </g>
        )}

        {visibleStations.map((station, index) => {
          const x = xScale(station.positionM);
          const labelY = index % 2 === 0 ? AXIS_Y - 52 : AXIS_Y + 76;
          const stemEndY = index % 2 === 0 ? AXIS_Y - 24 : AXIS_Y + 42;
          const label = station.displayNameOverride ?? station.displayName;
          const isPassed = station.positionM < currentPosition;
          const isUpcoming = sectionStations.next?.stationId === station.stationId;
          return (
            <g
              className={`line-run-view__station ${isPassed ? 'is-passed' : ''} ${isUpcoming ? 'is-upcoming' : ''}`}
              key={station.stationId}
            >
              <line x1={x} y1={AXIS_Y} x2={x} y2={stemEndY} />
              <circle cx={x} cy={AXIS_Y} r={isUpcoming ? 9 : 7} />
              <text x={x} y={labelY}>{label}</text>
            </g>
          );
        })}

        <g className="line-run-view__marker line-run-view__marker--start" transform={`translate(${startX} ${AXIS_Y})`}>
          <path d="M0,-48 L10,-28 L3,-28 L3,-6 L-3,-6 L-3,-28 L-10,-28 Z" />
          <text x="0" y="-58">起点 {formatMeters(absoluteStartPosition)}</text>
        </g>

        <g className="line-run-view__marker line-run-view__marker--target" transform={`translate(${targetX} ${AXIS_Y})`}>
          <line x1="0" y1="-72" x2="0" y2="86" />
          <path d="M0,-72 C14,-72 22,-62 22,-50 C22,-33 0,-18 0,-18 C0,-18 -22,-33 -22,-50 C-22,-62 -14,-72 0,-72 Z" />
          <circle cx="0" cy="-52" r="7" />
          <text x="0" y="-88">目标停车点 {formatMeters(absoluteTargetStopPosition)}</text>
        </g>

        <g className="line-run-view__train" transform={`translate(${trainX} ${AXIS_Y})`} filter="url(#lineRunGlow)">
          <circle className="line-run-view__train-ring" cx="0" cy="0" r="24" />
          <path className="line-run-view__train-body" d="M-28,-12 L12,-12 Q30,-12 36,0 Q30,12 12,12 L-28,12 Q-36,12 -36,4 L-36,-4 Q-36,-12 -28,-12 Z" />
          <path className="line-run-view__train-nose" d="M14,-7 L29,0 L14,7 Z" />
          <text x="0" y="-36">{currentState?.trainId ?? trainId}</text>
        </g>
      </svg>

      {/* ── 方案 B：下一站驾驶辅助 ─────────────────────────────────── */}
      <div className="line-run-view__driving-aid" aria-label="下一站驾驶辅助">
        <div className="lrv-aid__title">下一站驾驶辅助</div>
        <div className="lrv-aid__grid">
          <div className="lrv-aid__item">
            <span className="lrv-aid__label">下一站</span>
            <span className="lrv-aid__value lrv-aid__value--name">{drivingAid.nextStopName}</span>
          </div>
          <div className="lrv-aid__item">
            <span className="lrv-aid__label">距下一站</span>
            <span className="lrv-aid__value">{drivingAid.distanceM.toFixed(0)}<small>m</small></span>
          </div>
          <div className="lrv-aid__item">
            <span className="lrv-aid__label">推荐制动距离</span>
            <span className="lrv-aid__value">{drivingAid.brakingDistanceM.toFixed(0)}<small>m</small></span>
          </div>
          <div className="lrv-aid__item">
            <span className="lrv-aid__label">制动余量</span>
            <span className={`lrv-aid__value ${drivingAid.brakeMarginM < 20 ? 'lrv-aid__value--warn' : 'lrv-aid__value--ok'}`}>
              {drivingAid.brakeMarginM.toFixed(0)}<small>m</small>
            </span>
          </div>
          <div className="lrv-aid__item">
            <span className="lrv-aid__label">预计剩余时间</span>
            <span className="lrv-aid__value">{formatEta(drivingAid.etaS)}</span>
          </div>
          <div className="lrv-aid__item">
            <span className="lrv-aid__label">当前限速</span>
            <span className="lrv-aid__value">{drivingAid.speedLimitKmh.toFixed(0)}<small>km/h</small></span>
          </div>
          <div className="lrv-aid__item lrv-aid__item--full">
            <span className="lrv-aid__label">上次到站误差</span>
            <span className={`lrv-aid__value ${drivingAid.lastStopError === null ? '' : Math.abs(drivingAid.lastStopError) <= 0.5 ? 'lrv-aid__value--ok' : 'lrv-aid__value--warn'}`}>
              {drivingAid.lastStopError === null ? '—' : `${drivingAid.lastStopError >= 0 ? '+' : ''}${drivingAid.lastStopError.toFixed(2)}`}<small>{drivingAid.lastStopError !== null ? 'm' : ''}</small>
            </span>
          </div>
        </div>
      </div>

    </section>
  );
}

export default LineRunView;
