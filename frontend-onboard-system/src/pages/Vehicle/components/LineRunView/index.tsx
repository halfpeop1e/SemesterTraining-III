import { memo, useMemo } from "react";
import { LINE_MAP } from "../../data/lineMap";
import type {
  SidingStatus,
  StationStop,
  StopResult,
  TrainState,
} from "../../../../types/vehicle";
import {
  SVG_WIDTH,
  SVG_HEIGHT,
  AXIS_Y,
  MAP_LEFT,
  MAP_RIGHT,
  MAP_WIDTH,
  TICK_INTERVAL_M,
  STOP_WINDOW_TOLERANCE_M,
  clamp,
  formatMeters,
  normalizeStopWindowState,
  describeStopWindowState,
  computeDrivingAid,
  formatEta,
} from "./utils/coordinates";
import { useViewWindow } from "./hooks/useViewWindow";
import StopWindowOverlay from "./components/StopWindowOverlay";
import DrivingAidPanel from "./components/DrivingAidPanel";
import "../LineRunView.css";

export interface SignalMarker {
  km: number;
  id: number;
  name: string;
  aspectCode: number; // 0=RED, 1=GREEN, 2=WHITE
}

export interface SwitchMarker {
  km: number;
  id: string;
  state: string; // NORMAL / REVERSE
}

export interface LineRunViewProps {
  trainId: string;
  status: "idle" | "loading" | "playing" | "finished" | "error";
  currentState: TrainState | null;
  startPosition: number;
  targetStopPosition: number;
  speedLimit: number;
  stopResult: StopResult | null;
  positionOffset?: number;
  stationStops?: StationStop[];
  /** Shared siding snapshot passed through the vehicle page. */
  sidingStatuses?: SidingStatus[];
  /** 信号机位置标记列表 */
  signalMarkers?: SignalMarker[];
  /** 道岔位置标记列表 */
  switchMarkers?: SwitchMarker[];
  /** 移动授权终点里程（米） */
  eoaMeters?: number;
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
  signalMarkers,
  switchMarkers,
  eoaMeters,
}: LineRunViewProps) {
  // 坐标优先级：
  // - 当 absolutePosition 未下发或与段起点差值 < 1m 且 relativePosition 已前进 > 5m 时，
  //   表明 WS 的 absolutePosition 未实时更新，回退到 positionOffset + relativePosition 推算
  // - 否则信任 WS 下发的 absolutePosition（多站累计场景）
  const relativePosition = currentState?.position ?? startPosition;
  const wsAbsPos = currentState?.absolutePosition;
  const absPosStale =
    wsAbsPos !== undefined &&
    relativePosition > 5 &&
    Math.abs(wsAbsPos - positionOffset) < 1;
  const absolutePosition =
    wsAbsPos !== undefined && !absPosStale
      ? wsAbsPos
      : positionOffset + relativePosition;
  const currentPosition = clamp(
    absolutePosition,
    LINE_MAP.lineStartM,
    LINE_MAP.lineEndM,
  );
  const currentVelocity = currentState?.velocity ?? 0;

  // 方案 B：下一站驾驶辅助数据
  const drivingAid = useMemo(
    () =>
      computeDrivingAid(
        currentPosition,
        currentVelocity,
        speedLimit,
        stationStops,
        targetStopPosition,
        positionOffset,
        stopResult,
      ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [
      currentPosition,
      currentVelocity,
      speedLimit,
      stationStops,
      targetStopPosition,
      positionOffset,
      stopResult,
    ],
  );

  // startPosition 和 targetStopPosition 加偏移，用于全线地图定位
  const absoluteStartPosition = positionOffset + startPosition;
  const absoluteTargetStopPosition = positionOffset + targetStopPosition;
  const actualStopPosition = stopResult ? stopResult.actualStopPosition : null;
  // actualStopPosition 是从 fromStation 起的累积坐标，需要加 lineStartAbsM 得到全线绝对里程
  const absoluteActualStopPosition =
    actualStopPosition === null ? null : positionOffset + actualStopPosition;
  // finished 状态时回列车的目标中心（实停绝对位置）
  const finishedStopAbsPosition =
    stopResult?.actualStopPosition !== undefined
      ? positionOffset + stopResult.actualStopPosition
      : null;

  const {
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
  } = useViewWindow({
    currentPosition,
    status,
    absoluteTargetStopPosition,
    absoluteActualStopPosition,
    finishedStopAbsPosition,
  });

  const visibleStations = useMemo(
    () =>
      LINE_MAP.stations.filter(
        (station) =>
          station.positionM >= viewWindow.start &&
          station.positionM <= viewWindow.end,
      ),
    [viewWindow],
  );

  const ticks = useMemo(() => {
    const dynamicInterval =
      viewWindow.end - viewWindow.start <= 220 ? 10 : TICK_INTERVAL_M;
    const firstTick =
      Math.ceil(viewWindow.start / dynamicInterval) * dynamicInterval;
    const values: number[] = [];
    for (
      let tick = firstTick;
      tick <= viewWindow.end;
      tick += dynamicInterval
    ) {
      values.push(tick);
    }
    return values;
  }, [viewWindow]);

  const sectionStations = useMemo(() => {
    const previous = [...LINE_MAP.stations]
      .reverse()
      .find((station) => station.positionM <= currentPosition);
    const next = LINE_MAP.stations.find(
      (station) => station.positionM > currentPosition,
    );
    return { previous, next };
  }, [currentPosition]);

  // 阶段4B：所有坐标都用绝对里程，用于在全线地图上正确定位
  const targetX = xScale(absoluteTargetStopPosition);
  const startX = xScale(absoluteStartPosition);
  const trainX = xScale(currentPosition);
  const stopWindowLeftX = xScale(
    absoluteTargetStopPosition - STOP_WINDOW_TOLERANCE_M,
  );
  const stopWindowRightX = xScale(
    absoluteTargetStopPosition + STOP_WINDOW_TOLERANCE_M,
  );
  const actualStopX =
    absoluteActualStopPosition === null
      ? null
      : xScale(absoluteActualStopPosition);
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
          <button
            type="button"
            onClick={() => zoomAt(0.72, currentPosition)}
            title="放大"
          >
            +
          </button>
          <button
            type="button"
            onClick={() => zoomAt(1.38, currentPosition)}
            title="缩小"
          >
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
        <span>{isFollowing ? "镜头跟随" : "自由查看"}</span>
        <span>位置 {currentPosition.toFixed(1)} m</span>
        <span>速度 {(currentState?.velocity ?? 0).toFixed(2)} m/s</span>
        <span>限速 {speedLimitKmh.toFixed(0)} km/h</span>
        {sectionStations.next && (
          <span>
            下一站{" "}
            {sectionStations.next.displayNameOverride ??
              sectionStations.next.displayName}
          </span>
        )}
        {stopResult && (
          <span>
            停车窗 {describeStopWindowState(stopResult.stopWindowState)}
          </span>
        )}
      </div>

      <svg
        className={`line-run-view__svg ${isDragging ? "is-dragging" : ""}`}
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

        <rect
          className="line-run-view__panel"
          x="16"
          y="18"
          width="1168"
          height="350"
          rx="10"
        />

        <g className="line-run-view__ticks">
          {ticks.map((tick) => {
            const x = xScale(tick);
            return (
              <g key={tick}>
                <line x1={x} y1="76" x2={x} y2="306" />
                <line
                  className="line-run-view__tick-mark"
                  x1={x}
                  y1={AXIS_Y + 18}
                  x2={x}
                  y2={AXIS_Y + 30}
                />
                <text x={x} y={AXIS_Y + 48}>
                  {formatMeters(tick)}
                </text>
              </g>
            );
          })}
        </g>

        <line
          className="line-run-view__axis-shadow"
          x1={MAP_LEFT}
          y1={AXIS_Y}
          x2={MAP_RIGHT}
          y2={AXIS_Y}
        />
        <line
          className="line-run-view__axis line-run-view__axis--base"
          x1={MAP_LEFT}
          y1={AXIS_Y}
          x2={MAP_RIGHT}
          y2={AXIS_Y}
        />
        <line
          className="line-run-view__axis line-run-view__axis--passed"
          x1={xScale(absoluteStartPosition)}
          y1={AXIS_Y}
          x2={trainX}
          y2={AXIS_Y}
        />
        <line
          className="line-run-view__axis line-run-view__axis--target-path"
          x1={xScale(targetPathStart)}
          y1={AXIS_Y}
          x2={xScale(targetPathEnd)}
          y2={AXIS_Y}
        />
        {sectionStations.next && (
          <line
            className="line-run-view__axis line-run-view__axis--current"
            x1={xScale(
              sectionStations.previous?.positionM ?? LINE_MAP.lineStartM,
            )}
            y1={AXIS_Y}
            x2={xScale(sectionStations.next.positionM)}
            y2={AXIS_Y}
          />
        )}

        {status === "finished" && (
          <StopWindowOverlay
            stopState={stopState}
            stopWindowLeftX={stopWindowLeftX}
            stopWindowRightX={stopWindowRightX}
            targetX={targetX}
            actualStopX={actualStopX}
            stopError={stopResult?.stopError}
          />
        )}

        {visibleStations.map((station, index) => {
          const x = xScale(station.positionM);
          const labelY = index % 2 === 0 ? AXIS_Y - 52 : AXIS_Y + 76;
          const stemEndY = index % 2 === 0 ? AXIS_Y - 24 : AXIS_Y + 42;
          const label = station.displayNameOverride ?? station.displayName;
          const isPassed = station.positionM < currentPosition;
          const isUpcoming =
            sectionStations.next?.stationId === station.stationId;
          return (
            <g
              className={`line-run-view__station ${isPassed ? "is-passed" : ""} ${isUpcoming ? "is-upcoming" : ""}`}
              key={station.stationId}
            >
              <line x1={x} y1={AXIS_Y} x2={x} y2={stemEndY} />
              <circle cx={x} cy={AXIS_Y} r={isUpcoming ? 9 : 7} />
              <text x={x} y={labelY}>
                {label}
              </text>
            </g>
          );
        })}

        <g
          className="line-run-view__marker line-run-view__marker--start"
          transform={`translate(${startX} ${AXIS_Y})`}
        >
          <path d="M0,-48 L10,-28 L3,-28 L3,-6 L-3,-6 L-3,-28 L-10,-28 Z" />
          <text x="0" y="-58">
            起点 {formatMeters(absoluteStartPosition)}
          </text>
        </g>

        {/* ── 信号机标记 ── */}
        {signalMarkers?.map((s) => {
          const sx = xScale(s.km * 1000);
          const color =
            s.aspectCode === 1
              ? "#22c55e"
              : s.aspectCode === 2
                ? "#f5f5f5"
                : "#ef4444";
          return (
            <g
              key={`sig-${s.id}`}
              transform={`translate(${sx} ${AXIS_Y - 35})`}
            >
              <circle
                cx="0"
                cy="0"
                r="5"
                fill={color}
                stroke="#1e293b"
                strokeWidth="1"
              />
              <text
                x="0"
                y="16"
                textAnchor="middle"
                fontSize="9"
                fill="#94a3b8"
              >
                {s.name}
              </text>
            </g>
          );
        })}

        {/* ── 道岔标记 ── */}
        {switchMarkers?.map((sw) => {
          const swx = xScale(sw.km * 1000);
          return (
            <g
              key={`sw-${sw.id}`}
              transform={`translate(${swx} ${AXIS_Y - 22})`}
            >
              <rect
                x="-4"
                y="-4"
                width="8"
                height="8"
                fill={sw.state === "NORMAL" ? "#3b82f6" : "#f59e0b"}
                rx="1"
              />
              <text
                x="0"
                y="18"
                textAnchor="middle"
                fontSize="8"
                fill="#64748b"
              >
                {sw.id}
              </text>
            </g>
          );
        })}

        {/* ── EoA 移动授权终点 ── */}
        {eoaMeters != null && eoaMeters > 0 && (
          <g
            className="line-run-view__marker"
            transform={`translate(${xScale(eoaMeters)} ${AXIS_Y})`}
          >
            <line
              x1="0"
              y1="-55"
              x2="0"
              y2="55"
              stroke="#f59e0b"
              strokeWidth="2"
              strokeDasharray="6 3"
              opacity="0.8"
            />
            <text
              x="0"
              y="-62"
              textAnchor="middle"
              fontSize="10"
              fill="#f59e0b"
            >
              EoA {formatMeters(eoaMeters)}
            </text>
          </g>
        )}

        <g
          className="line-run-view__marker line-run-view__marker--target"
          transform={`translate(${targetX} ${AXIS_Y})`}
        >
          <line x1="0" y1="-72" x2="0" y2="86" />
          <path d="M0,-72 C14,-72 22,-62 22,-50 C22,-33 0,-18 0,-18 C0,-18 -22,-33 -22,-50 C-22,-62 -14,-72 0,-72 Z" />
          <circle cx="0" cy="-52" r="7" />
          <text x="0" y="-88">
            目标停车点 {formatMeters(absoluteTargetStopPosition)}
          </text>
        </g>

        <g
          className="line-run-view__train"
          transform={`translate(${trainX} ${AXIS_Y})`}
          filter="url(#lineRunGlow)"
        >
          <circle className="line-run-view__train-ring" cx="0" cy="0" r="24" />
          <path
            className="line-run-view__train-body"
            d="M-28,-12 L12,-12 Q30,-12 36,0 Q30,12 12,12 L-28,12 Q-36,12 -36,4 L-36,-4 Q-36,-12 -28,-12 Z"
          />
          <path
            className="line-run-view__train-nose"
            d="M14,-7 L29,0 L14,7 Z"
          />
          <text x="0" y="-36">
            {currentState?.trainId ?? trainId}
          </text>
        </g>
      </svg>

      {/* ── 方案 B：下一站驾驶辅助 ─────────────────────────────────── */}
      <DrivingAidPanel data={drivingAid} />
    </section>
  );
}

export default memo(LineRunView);
