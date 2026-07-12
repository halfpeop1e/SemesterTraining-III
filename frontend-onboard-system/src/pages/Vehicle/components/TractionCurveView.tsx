import { useCallback, useEffect, useRef, useState } from 'react';
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
const TOTAL_MOTORS = 16;
const DEFAULT_MASS_KG = 225_000;

function davisResistance(speedKmh: number, massTon: number): number {
  return 6.4 * massTon + 3120 + 0.14 * massTon * speedKmh + 0.8321 * speedKmh * speedKmh;
}

/* ═════════════════════════════════════════════════════
   SVG 布局
   ═════════════════════════════════════════════════════ */
const SVG_W = 600;
const SVG_H = 420;
const MARGIN = { top: 30, right: 40, bottom: 55, left: 70 };
const PLOT_W = SVG_W - MARGIN.left - MARGIN.right;
const PLOT_H = SVG_H - MARGIN.top - MARGIN.bottom;

/** Y 轴力范围 kN */
const Y_MIN = -350;
const Y_MAX = 350;

/** 缩放范围 */
const ZOOM_MIN_S = 10;
const ZOOM_MAX_S = 300;
const ZOOM_DEFAULT_S = 60;

function xScale(t: number, tMin: number, tMax: number) {
  const ratio = (t - tMin) / (tMax - tMin || 1);
  return MARGIN.left + ratio * PLOT_W;
}
function yScale(forceKN: number) {
  return MARGIN.top + PLOT_H - ((forceKN - Y_MIN) / (Y_MAX - Y_MIN)) * PLOT_H;
}

/* ═════════════════════════════════════════════════════
   力-时间数据点
   ═════════════════════════════════════════════════════ */
interface ForcePoint {
  time: number;
  tractionKN: number;
  brakeKN: number;
  resistKN: number;
}

/** 空白坐标轴 */
function EmptyAxes({
  tMin, tMax, yTicks, xTickCount,
}: {
  tMin: number; tMax: number;
  yTicks: number[]; xTickCount: number;
}) {
  const xStep = (tMax - tMin) / (xTickCount || 1);
  return (
    <>
      <rect className="tcv__panel" x={MARGIN.left - 8} y={MARGIN.top - 8}
        width={PLOT_W + 16} height={PLOT_H + 16} rx="8" />
      {/* Y 轴网格和刻度 */}
      {yTicks.map((y) => (
        <line key={`gy-${y}`} className="tcv__grid"
          x1={MARGIN.left} y1={yScale(y)} x2={MARGIN.left + PLOT_W} y2={yScale(y)} />
      ))}
      {yTicks.map((y) => (
        <text key={`yt-${y}`} className="tcv__tick tcv__tick--y"
          x={MARGIN.left - 8} y={yScale(y) + 5}>{y}</text>
      ))}
      <text className="tcv__label tcv__label--y"
        x={MARGIN.left - 52} y={MARGIN.top + PLOT_H / 2}
        transform={`rotate(-90, ${MARGIN.left - 52}, ${MARGIN.top + PLOT_H / 2})`}>
        力 (kN)
      </text>
      {/* X 轴网格和刻度 */}
      {Array.from({ length: xTickCount + 1 }, (_, i) => {
        const t = tMin + i * xStep;
        return (
          <line key={`gx-${i}`} className="tcv__grid"
            x1={xScale(t, tMin, tMax)} y1={MARGIN.top}
            x2={xScale(t, tMin, tMax)} y2={MARGIN.top + PLOT_H} />
        );
      })}
      {Array.from({ length: xTickCount + 1 }, (_, i) => {
        const t = tMin + i * xStep;
        return (
          <text key={`xt-${i}`} className="tcv__tick tcv__tick--x"
            x={xScale(t, tMin, tMax)} y={MARGIN.top + PLOT_H + 22}>
            {t.toFixed(0)}
          </text>
        );
      })}
      <text className="tcv__label tcv__label--x"
        x={MARGIN.left + PLOT_W / 2} y={MARGIN.top + PLOT_H + 45}>
        时间 (s)
      </text>
      {/* 零力参考线 */}
      <line className="tcv__zero-line"
        x1={MARGIN.left} y1={yScale(0)} x2={MARGIN.left + PLOT_W} y2={yScale(0)} />
      {/* 主轴 */}
      <line className="tcv__axis" x1={MARGIN.left} y1={MARGIN.top}
        x2={MARGIN.left} y2={MARGIN.top + PLOT_H} />
      <line className="tcv__axis" x1={MARGIN.left} y1={MARGIN.top + PLOT_H}
        x2={MARGIN.left + PLOT_W} y2={MARGIN.top + PLOT_H} />
    </>
  );
}

/** 将力序列数据点转为 SVG path d */
function buildTimePath(
  pts: ForcePoint[],
  getVal: (p: ForcePoint) => number,
  tMin: number,
  tMax: number,
  yClampLo: number,
  yClampHi: number,
): string {
  if (pts.length === 0) return '';
  const visible: string[] = [];
  for (const p of pts) {
    if (p.time < tMin || p.time > tMax) continue;
    const x = xScale(p.time, tMin, tMax).toFixed(1);
    const y = yScale(clampVal(getVal(p), yClampLo, yClampHi)).toFixed(1);
    visible.push(`${visible.length === 0 ? 'M' : 'L'} ${x} ${y}`);
  }
  return visible.join(' ');
}

function clampVal(v: number, lo: number, hi: number): number {
  return Math.min(Math.max(v, lo), hi);
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

  // ── 力-时间历史数据 ──
  const historyRef = useRef<ForcePoint[]>([]);
  const prevStatusRef = useRef(status);

  if (prevStatusRef.current !== status && status === 'playing') {
    historyRef.current = [{ time: 0, tractionKN: 0, brakeKN: 0, resistKN: 0 }];
  }
  prevStatusRef.current = status;

  // ── 当前值 ──
  const curTime = currentState?.time ?? 0;
  const vKmh = (currentState?.velocity ?? 0) * 3.6;
  const phase = currentState?.phase ?? 'stopped';

  const tractionKN = (currentState?.tractionForce ?? 0) / 1000;
  const brakeKN = (currentState?.brakeForce ?? 0) / 1000;
  const resistKN = davisResistance(vKmh, massTon) / 1000;

  // 累积数据点
  useEffect(() => {
    if (!isRunning || !currentState) return;
    const pts = historyRef.current;
    const last = pts[pts.length - 1];
    if (!last
      || Math.abs(last.time - curTime) > 0.1
      || Math.abs(last.tractionKN - tractionKN) > 1
      || Math.abs(last.brakeKN - brakeKN) > 1
      || Math.abs(last.resistKN - resistKN) > 0.5
    ) {
      pts.push({ time: curTime, tractionKN, brakeKN, resistKN });
      const cutoff = curTime - ZOOM_MAX_S * 2;
      while (pts.length > 0 && pts[0].time < cutoff) pts.shift();
    }
  }, [isRunning, currentState, curTime, tractionKN, brakeKN, resistKN]);

  // ── 视图控制：缩放 + 拖拽平移 ──
  const [windowDuration, setWindowDuration] = useState(ZOOM_DEFAULT_S);
  // viewRight = 当前视图右边界对应的时间（自动跟随时 = curTime）
  const [viewRight, setViewRight] = useState(ZOOM_DEFAULT_S);
  const [autoFollow, setAutoFollow] = useState(true);

  // 自动跟随
  const prevCurTimeRef = useRef(curTime);
  useEffect(() => {
    if (autoFollow && isRunning) {
      setViewRight(Math.max(curTime, windowDuration));
    }
    prevCurTimeRef.current = curTime;
  }, [curTime, autoFollow, isRunning, windowDuration]);

  // ── 拖拽状态 ──
  const dragRef = useRef<{ startX: number; startViewRight: number } | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    dragRef.current = { startX: e.clientX, startViewRight: viewRight };
    setAutoFollow(false);
    e.preventDefault();
  }, [viewRight]);

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      const drag = dragRef.current;
      if (!drag) return;
      const dx = e.clientX - drag.startX;
      const dt = -(dx / PLOT_W) * windowDuration;
      const newRight = Math.max(windowDuration, drag.startViewRight + dt);
      setViewRight(newRight);
    };
    const handleMouseUp = () => { dragRef.current = null; };
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, [windowDuration]);

  // ── 滚轮缩放 ──
  const handleWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault();
    const delta = e.deltaY > 0 ? 1.3 : 1 / 1.3;
    setWindowDuration((prev) => clampVal(prev * delta, ZOOM_MIN_S, ZOOM_MAX_S));
    setAutoFollow(false);
  }, []);

  // ── 确定可视窗口 ──
  const tMax = Math.max(viewRight, windowDuration);
  const tMin = Math.max(0, tMax - windowDuration);

  // ── Y 轴刻度 ──
  const yTicks: number[] = [];
  for (let y = -300; y <= 300; y += 100) yTicks.push(y);
  const xTickCount = 6;

  // ── 构建各力序列 path ──
  const pts = historyRef.current;
  const pathTraction = buildTimePath(pts, (p) => p.tractionKN, tMin, tMax, Y_MIN, Y_MAX);
  const pathBrake = buildTimePath(pts, (p) => -p.brakeKN, tMin, tMax, Y_MIN, Y_MAX);
  const pathResist = buildTimePath(pts, (p) => -p.resistKN, tMin, tMax, Y_MIN, Y_MAX);

  // 当前点坐标
  const dotX = xScale(curTime, tMin, tMax);
  const dotYTraction = yScale(clampVal(tractionKN, Y_MIN, Y_MAX));
  const dotYBrake = yScale(clampVal(-brakeKN, Y_MIN, Y_MAX));

  const dotColor = phase === 'traction' ? '#38bdf8'
    : phase === 'braking' ? '#fb7185'
    : phase === 'coast' ? '#facc15'
    : '#94a3b8';

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
            <button
              type="button"
              className="tcv__follow-btn"
              onClick={() => { setAutoFollow(true); setViewRight(curTime); }}
            >
              回到最新
            </button>
          )}
        </div>
      </div>

      {/* ── 空闲 / 加载中 ── */}
      {!isRunning && (
        <div className="tcv__idle">
          <svg className="tcv__svg" viewBox={`0 0 ${SVG_W} ${SVG_H}`} role="img">
            <defs>
              <linearGradient id="tcvBg" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#0f172a" />
                <stop offset="100%" stopColor="#020617" />
              </linearGradient>
            </defs>
            <EmptyAxes tMin={0} tMax={ZOOM_DEFAULT_S} yTicks={yTicks} xTickCount={xTickCount} />
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

      {/* ── 运行中 ── */}
      {isRunning && (
        <>
          {/* 图例 + 缩放提示 */}
          <div className="tcv__legend tcv__legend--enter">
            <span className="tcv__legend-item tcv__legend--traction">牵引力</span>
            <span className="tcv__legend-item tcv__legend--brake">电制动力</span>
            <span className="tcv__legend-item tcv__legend--resist">基本运行阻力</span>
            <span className="tcv__legend-hint">拖拽平移 · 滚轮缩放</span>
          </div>

          <svg
            ref={svgRef}
            className="tcv__svg tcv__svg--interactive"
            viewBox={`0 0 ${SVG_W} ${SVG_H}`}
            role="img"
            onMouseDown={handleMouseDown}
            onWheel={handleWheel}
          >
            <defs>
              <linearGradient id="tcvBg" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="#0f172a" />
                <stop offset="100%" stopColor="#020617" />
              </linearGradient>
            </defs>
            <EmptyAxes tMin={tMin} tMax={tMax} yTicks={yTicks} xTickCount={xTickCount} />

            {/* 力序列曲线 */}
            {pathTraction && <path className="tcv__curve tcv__curve--traction" d={pathTraction} />}
            {pathBrake && <path className="tcv__curve tcv__curve--brake" d={pathBrake} />}
            {pathResist && <path className="tcv__curve tcv__curve--resist" d={pathResist} />}

            {/* 当前时刻竖线 */}
            {curTime >= tMin && curTime <= tMax && (
              <line className="tcv__now-line"
                x1={dotX} y1={MARGIN.top}
                x2={dotX} y2={MARGIN.top + PLOT_H} />
            )}

            {/* 当前牵引力点 */}
            {tractionKN > 0.1 && currentState && curTime >= tMin && curTime <= tMax && (
              <g className="tcv__operating-point">
                <circle className="tcv__op-pulse"
                  cx={dotX} cy={dotYTraction} r="12" />
                <circle className="tcv__op-dot"
                  cx={dotX} cy={dotYTraction} r="5"
                  style={{ fill: '#38bdf8' }} />
              </g>
            )}
            {/* 当前制动力点 */}
            {brakeKN > 0.1 && currentState && curTime >= tMin && curTime <= tMax && (
              <g className="tcv__operating-point">
                <circle className="tcv__op-pulse"
                  cx={dotX} cy={dotYBrake} r="12" />
                <circle className="tcv__op-dot"
                  cx={dotX} cy={dotYBrake} r="5"
                  style={{ fill: '#fb7185' }} />
              </g>
            )}
          </svg>

          {/* 底部数值面板 */}
          <div className="tcv__readout">
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">仿真时间</span>
              <span className="tcv__ro-value">{curTime.toFixed(1)}<small>s</small></span>
            </div>
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">当前速度</span>
              <span className="tcv__ro-value">{vKmh.toFixed(1)}<small>km/h</small></span>
            </div>
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">牵引力</span>
              <span className="tcv__ro-value tcv__ro-value--trac">
                {tractionKN.toFixed(1)}<small>kN</small>
              </span>
            </div>
            <div className="tcv__readout-item">
              <span className="tcv__ro-label">电制动力</span>
              <span className="tcv__ro-value tcv__ro-value--brk">
                {brakeKN.toFixed(1)}<small>kN</small>
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

export default TractionCurveView;
