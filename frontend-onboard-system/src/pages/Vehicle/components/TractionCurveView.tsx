import { useMemo } from 'react';
import type { TrainState } from '../../../types/vehicle';
import './TractionCurveView.css';

export interface TractionCurveViewProps {
  currentState: TrainState | null;
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  /** 当前可用电机数 (默认16, 故障时下降) */
  availableMotors?: number;
  /** 列车当前总质量 kg (默认225000) */
  trainMass?: number;
}

/* ═════════════════════════════════════════════════════
   物理参数 (来源: 列车仿真参数.xlsx)
   ═════════════════════════════════════════════════════ */
const WHEEL_RADIUS_M = 0.46;
const TOTAL_MOTORS = 16;
const GEAR_RATIO = 7.5;
const RPM_TO_KMH = (2 * Math.PI * WHEEL_RADIUS_M * 3.6) / 60;
const DEFAULT_MASS_KG = 225_000;

function motorTorqueTraction(rpm: number): number {
  if (rpm <= 2496.1) return 1042.9;
  if (rpm <= 2912.1) return (271.0 * 9549.3) / rpm;
  return 6.477812984e9 / (rpm * rpm);
}

function speedToRpm(speedKmh: number): number {
  return (speedKmh * GEAR_RATIO) / RPM_TO_KMH;
}

function maxTractiveForce(speedKmh: number, availableMotors: number): number {
  const rpm = speedToRpm(Math.max(0.1, speedKmh));
  const ratio = availableMotors / TOTAL_MOTORS;
  return (motorTorqueTraction(rpm) * TOTAL_MOTORS * ratio * GEAR_RATIO) / WHEEL_RADIUS_M;
}

function maxBrakeForce(speedKmh: number, availableMotors: number): number {
  if (speedKmh < 0.5) return 0;
  const ratio = availableMotors / TOTAL_MOTORS;
  return (977.7 * TOTAL_MOTORS * ratio * GEAR_RATIO) / WHEEL_RADIUS_M;
}

function davisResistance(speedKmh: number, massTon: number): number {
  return 6.4 * massTon + 3120 + 0.14 * massTon * speedKmh + 0.8321 * speedKmh * speedKmh;
}

/* ═════════════════════════════════════════════════════
   SVG 布局
   ═════════════════════════════════════════════════════ */
const SVG_W = 600;
const SVG_H = 420;
const MARGIN = { top: 30, right: 40, bottom: 50, left: 60 };
const PLOT_W = SVG_W - MARGIN.left - MARGIN.right;
const PLOT_H = SVG_H - MARGIN.top - MARGIN.bottom;
const X_MAX = 100;
const Y_MAX = 320;

function xScale(vKmh: number) { return MARGIN.left + (vKmh / X_MAX) * PLOT_W; }
function yScale(forceKN: number) { return MARGIN.top + PLOT_H - (forceKN / Y_MAX) * PLOT_H; }

function buildCurvePath(fn: (v: number) => number, steps: number, vMax: number): string {
  const pts: string[] = [];
  for (let i = 0; i <= steps; i++) {
    const v = (i / steps) * vMax;
    const f = Math.max(0, fn(v)) / 1000;
    pts.push(`${i === 0 ? 'M' : 'L'} ${xScale(v).toFixed(1)} ${yScale(Math.min(f, Y_MAX)).toFixed(1)}`);
  }
  return pts.join(' ');
}

const yTicks = [0, 50, 100, 150, 200, 250, 300];
const xTicks = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100];

/** 空白坐标轴（不含曲线，idle 时用） */
function EmptyAxes() {
  return (
    <>
      <rect className="tcv__panel" x={MARGIN.left - 8} y={MARGIN.top - 8}
        width={PLOT_W + 16} height={PLOT_H + 16} rx="8" />
      {yTicks.map((y) => (
        <line key={`gy-${y}`} className="tcv__grid"
          x1={MARGIN.left} y1={yScale(y)} x2={MARGIN.left + PLOT_W} y2={yScale(y)} />
      ))}
      {xTicks.map((x) => (
        <line key={`gx-${x}`} className="tcv__grid"
          x1={xScale(x)} y1={MARGIN.top} x2={xScale(x)} y2={MARGIN.top + PLOT_H} />
      ))}
      {yTicks.map((y) => (
        <text key={`yt-${y}`} className="tcv__tick tcv__tick--y"
          x={MARGIN.left - 8} y={yScale(y) + 5}>{y}</text>
      ))}
      <text className="tcv__label tcv__label--y"
        x={MARGIN.left - 42} y={MARGIN.top + PLOT_H / 2}
        transform={`rotate(-90, ${MARGIN.left - 42}, ${MARGIN.top + PLOT_H / 2})`}>
        力 (kN)
      </text>
      {xTicks.map((x) => (
        <text key={`xt-${x}`} className="tcv__tick tcv__tick--x"
          x={xScale(x)} y={MARGIN.top + PLOT_H + 20}>{x}</text>
      ))}
      <text className="tcv__label tcv__label--x"
        x={MARGIN.left + PLOT_W / 2} y={MARGIN.top + PLOT_H + 40}>
        速度 (km/h)
      </text>
      <line className="tcv__axis" x1={MARGIN.left} y1={MARGIN.top}
        x2={MARGIN.left} y2={MARGIN.top + PLOT_H} />
      <line className="tcv__axis" x1={MARGIN.left} y1={MARGIN.top + PLOT_H}
        x2={MARGIN.left + PLOT_W} y2={MARGIN.top + PLOT_H} />
    </>
  );
}

function TractionCurveView({
  currentState,
  status,
  availableMotors = TOTAL_MOTORS,
  trainMass = DEFAULT_MASS_KG,
}: TractionCurveViewProps) {
  const isRunning = status === 'playing' || status === 'finished' || status === 'error';
  const isDegraded = availableMotors < TOTAL_MOTORS;
  const motorRatio = availableMotors / TOTAL_MOTORS;
  const massTon = trainMass / 1000;

  // ── 运行时数据（running 时才用） ──
  const vMs = currentState?.velocity ?? 0;
  const vKmh = vMs * 3.6;
  const accel = currentState?.acceleration ?? 0;
  const phase = currentState?.phase ?? 'stopped';

  const currentForceKN = useMemo(() => {
    if (!isRunning) return 0;
    return (trainMass * accel + davisResistance(vKmh, massTon)) / 1000;
  }, [isRunning, trainMass, accel, vKmh, massTon]);

  const maxTracKN = useMemo(() => {
    if (!isRunning) return 0;
    return maxTractiveForce(vKmh, availableMotors) / 1000;
  }, [isRunning, vKmh, availableMotors]);

  const maxBrkKN = useMemo(() => {
    if (!isRunning) return 0;
    return maxBrakeForce(vKmh, availableMotors) / 1000;
  }, [isRunning, vKmh, availableMotors]);

  const resistKN = useMemo(() => {
    if (!isRunning) return 0;
    return davisResistance(vKmh, massTon) / 1000;
  }, [isRunning, vKmh, massTon]);

  const tractionPath = useMemo(
    () => buildCurvePath((v) => maxTractiveForce(v, availableMotors), 200, X_MAX),
    [availableMotors],
  );
  const brakePath = useMemo(
    () => buildCurvePath((v) => maxBrakeForce(v, availableMotors), 200, X_MAX),
    [availableMotors],
  );
  const resistPath = useMemo(
    () => buildCurvePath((v) => davisResistance(v, massTon), 200, X_MAX),
    [massTon],
  );

  const dotColor = phase === 'traction' ? '#38bdf8'
    : phase === 'braking' ? '#fb7185'
    : phase === 'coast' ? '#facc15'
    : '#94a3b8';

  const dotCY = yScale(clampVal(currentForceKN, -10, Y_MAX + 10));
  const dotCX = xScale(clampVal(vKmh, 0, X_MAX));

  return (
    <section className="traction-curve-view" aria-label="牵引特性曲线">
      <div className="tcv__header">
        <div>
          <div className="tcv__eyebrow">Traction envelope</div>
          <h3>牵引特性曲线</h3>
        </div>
        {isDegraded && (
          <span className="tcv__degraded-badge">
            电机 {availableMotors}/{TOTAL_MOTORS} · {Math.round(motorRatio * 100)}%
          </span>
        )}
      </div>

      {/* ── 空闲 / 加载中：空白图表 ── */}
      {!isRunning && (
        <div className="tcv__idle">
          <svg className="tcv__svg" viewBox={`0 0 ${SVG_W} ${SVG_H}`} role="img">
            <defs>
              <linearGradient id="tcvBg" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#0f172a" />
                <stop offset="100%" stopColor="#020617" />
              </linearGradient>
            </defs>
            <EmptyAxes />
            <text className="tcv__idle-text"
              x={MARGIN.left + PLOT_W / 2}
              y={MARGIN.top + PLOT_H / 2}>
              {status === 'loading' ? '正在准备仿真...' : '等待列车运行...'}
            </text>
          </svg>
          <div className="tcv__readout tcv__readout--idle">
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">状态</span>
              <span className="tcv__ro-value tcv__ro-value--muted">
                {status === 'loading' ? '准备中' : '未运行'}
              </span>
            </div>
          </div>
        </div>
      )}

      {/* ── 运行中/已结束：完整图表 ── */}
      {isRunning && (
        <>
          {/* 图例 — 首次出现时淡入 */}
          <div className="tcv__legend tcv__legend--enter">
            <span className="tcv__legend-item tcv__legend--traction">
              最大牵引力{isDegraded ? ` (${Math.round(motorRatio * 100)}%)` : ''}
            </span>
            <span className="tcv__legend-item tcv__legend--brake">
              最大电制动力{isDegraded ? ` (${Math.round(motorRatio * 100)}%)` : ''}
            </span>
            <span className="tcv__legend-item tcv__legend--resist">基本运行阻力</span>
          </div>

          <svg className="tcv__svg" viewBox={`0 0 ${SVG_W} ${SVG_H}`} role="img">
            <defs>
              <linearGradient id="tcvBg" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#0f172a" />
                <stop offset="100%" stopColor="#020617" />
              </linearGradient>
            </defs>
            <EmptyAxes />

            {/* 包络曲线 */}
            <path className="tcv__curve tcv__curve--traction"
              d={tractionPath}
              style={isDegraded ? { strokeDasharray: '12 6', opacity: 0.7 } : undefined} />
            <path className="tcv__curve tcv__curve--brake"
              d={brakePath}
              style={isDegraded ? { strokeDasharray: '12 6', opacity: 0.7 } : undefined} />
            <path className="tcv__curve tcv__curve--resist" d={resistPath} />

            {/* 当前运行点 */}
            {currentState && (
              <g className="tcv__operating-point">
                <line className="tcv__op-proj" x1={dotCX} y1={dotCY}
                  x2={dotCX} y2={MARGIN.top + PLOT_H} />
                <line className="tcv__op-proj" x1={MARGIN.left} y1={dotCY}
                  x2={dotCX} y2={dotCY} />
                <text className="tcv__op-label tcv__op-label--v"
                  x={dotCX} y={MARGIN.top + PLOT_H + 32}>
                  {vKmh.toFixed(1)} km/h
                </text>
                <text className="tcv__op-label tcv__op-label--f"
                  x={MARGIN.left - 10} y={dotCY - 8} textAnchor="end">
                  {currentForceKN.toFixed(1)} kN
                </text>
                <circle className="tcv__op-pulse" cx={dotCX} cy={dotCY} r="16" />
                <circle className="tcv__op-dot" cx={dotCX} cy={dotCY} r="7"
                  style={{ fill: dotColor }} />
              </g>
            )}
          </svg>

          {/* 底部数值面板 */}
          <div className="tcv__readout">
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">当前速度</span>
              <span className="tcv__ro-value">{vKmh.toFixed(1)}<small>km/h</small></span>
            </div>
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">当前力</span>
              <span className="tcv__ro-value" style={{ color: dotColor }}>
                {currentForceKN.toFixed(1)}<small>kN</small>
              </span>
            </div>
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">可用牵引力</span>
              <span className={`tcv__ro-value tcv__ro-value--trac${isDegraded ? ' tcv__ro-value--degraded' : ''}`}>
                {maxTracKN.toFixed(0)}<small>kN</small>
              </span>
            </div>
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">可用制动力</span>
              <span className={`tcv__ro-value tcv__ro-value--brk${isDegraded ? ' tcv__ro-value--degraded' : ''}`}>
                {maxBrkKN.toFixed(0)}<small>kN</small>
              </span>
            </div>
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">基本阻力</span>
              <span className="tcv__ro-value tcv__ro-value--resist">
                {resistKN.toFixed(1)}<small>kN</small>
              </span>
            </div>
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">运行阶段</span>
              <span className="tcv__ro-value" style={{ color: dotColor }}>
                {phase === 'traction' ? '牵引' : phase === 'braking' ? '制动'
                  : phase === 'coast' ? '惰行' : phase === 'stopped' ? '停车'
                  : phase === 'dwell' ? '站停' : phase}
              </span>
            </div>
          </div>
        </>
      )}
    </section>
  );
}

function clampVal(v: number, lo: number, hi: number): number {
  return Math.min(Math.max(v, lo), hi);
}

export default TractionCurveView;
