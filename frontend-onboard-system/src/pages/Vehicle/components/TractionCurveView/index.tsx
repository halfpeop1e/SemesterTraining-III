import { memo, useMemo } from 'react';
import type { TrainState } from '../../../../types/vehicle';
import { SVG_W, SVG_H, MARGIN, PLOT_H, xScale } from './utils/pathBuilder';
import { useForceHistory } from './hooks/useForceHistory';
import { useCurveViewWindow } from './hooks/useCurveViewWindow';
import CurveAxes from './components/CurveAxes';
import ForceCurves from './components/ForceCurves';
import OperatingPoint from './components/OperatingPoint';
import ForceReadout from './components/ForceReadout';
import IdleView from './components/IdleView';
import '../TractionCurveView.css';

export interface TractionCurveViewProps {
  currentState: TrainState | null;
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  availableMotors?: number;
  trainMass?: number;
}

const TOTAL_MOTORS = 16;
const IDLE_WINDOW_S = 60;
const X_TICK_COUNT = 6;

function TractionCurveView({
  currentState,
  status,
  availableMotors = TOTAL_MOTORS,
  trainMass,
}: TractionCurveViewProps) {
  const isDegraded = availableMotors < TOTAL_MOTORS;
  const motorRatio = availableMotors / TOTAL_MOTORS;

  const { historyRef, currentPoint, isRunning } = useForceHistory({
    currentState,
    status,
    trainMass,
  });

  const {
    time: curTime,
    tractionKN,
    brakeKN,
    resistKN,
    netKN,
    vKmh,
    phase,
  } = currentPoint;

  const {
    tMin, tMax, autoFollow,
    handlePointerDown, handlePointerMove, handlePointerUp,
    handleWheel, returnToLatest,
  } = useCurveViewWindow({ curTime, isRunning });

  const yTicks = useMemo(() => {
    const ticks: number[] = [];
    for (let y = -300; y <= 300; y += 100) ticks.push(y);
    return ticks;
  }, []);

  const isInView = curTime >= tMin && curTime <= tMax;

  return (
    <section className="traction-curve-view" aria-label="力-时间实时曲线">
      <div className="tcv__header">
        <div>
          <div className="tcv__eyebrow">Force · Time</div>
          <h3>力-时间曲线</h3>
        </div>
        <div className="tcv__header-right">
          {isDegraded && (
            <span className="tcv__degraded-badge">
              电机 {availableMotors}/{TOTAL_MOTORS} · {Math.round(motorRatio * 100)}%
            </span>
          )}
          {isRunning && !autoFollow && (
            <button type="button" className="tcv__follow-btn" onClick={returnToLatest}>
              回到最新
            </button>
          )}
        </div>
      </div>

      {!isRunning && (
        <IdleView
          status={status}
          yTicks={yTicks}
          xTickCount={X_TICK_COUNT}
          idleWindowS={IDLE_WINDOW_S}
        />
      )}

      {isRunning && (
        <>
          <div className="tcv__legend tcv__legend--enter">
            <span className="tcv__legend-item tcv__legend--traction">牵引力</span>
            <span className="tcv__legend-item tcv__legend--brake">电制动力</span>
            <span className="tcv__legend-item tcv__legend--resist">基本运行阻力</span>
            <span className="tcv__legend-item tcv__legend--net">净加速力</span>
            <span className="tcv__legend-hint">拖拽平移 · 滚轮缩放</span>
          </div>

          <svg
            className="tcv__svg tcv__svg--interactive"
            viewBox={`0 0 ${SVG_W} ${SVG_H}`}
            role="img"
            onWheel={handleWheel}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            onPointerCancel={handlePointerUp}
          >
            <defs>
              <linearGradient id="tcvBg" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#0f172a" />
                <stop offset="100%" stopColor="#020617" />
              </linearGradient>
            </defs>

            <CurveAxes tMin={tMin} tMax={tMax} yTicks={yTicks} xTickCount={X_TICK_COUNT} />
            <ForceCurves pts={historyRef.current} tMin={tMin} tMax={tMax} />

            {isInView && (
              <line className="tcv__now-line"
                x1={xScale(curTime, tMin, tMax)}
                y1={MARGIN.top}
                x2={xScale(curTime, tMin, tMax)}
                y2={MARGIN.top + PLOT_H} />
            )}

            <OperatingPoint
              curTime={curTime}
              tractionKN={tractionKN}
              brakeKN={brakeKN}
              phase={phase}
              tMin={tMin}
              tMax={tMax}
              isVisible={isInView && !!currentState}
            />
          </svg>

          <ForceReadout
            time={curTime}
            vKmh={vKmh}
            tractionKN={tractionKN}
            brakeKN={brakeKN}
            resistKN={resistKN}
            netKN={netKN}
            phase={phase}
          />
        </>
      )}
    </section>
  );
}

export default memo(TractionCurveView);
