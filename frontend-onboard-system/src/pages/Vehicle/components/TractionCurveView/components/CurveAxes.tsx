import { memo } from 'react';
import { MARGIN, PLOT_W, PLOT_H, xScale, yScale } from '../utils/pathBuilder';

interface CurveAxesProps {
  tMin: number;
  tMax: number;
  yTicks: number[];
  xTickCount: number;
}

function CurveAxes({ tMin, tMax, yTicks, xTickCount }: CurveAxesProps) {
  const xStep = (tMax - tMin) / (xTickCount || 1);
  return (
    <>
      <rect className="tcv__panel" x={MARGIN.left - 8} y={MARGIN.top - 8}
        width={PLOT_W + 16} height={PLOT_H + 16} rx="8" />
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
      <line className="tcv__zero-line"
        x1={MARGIN.left} y1={yScale(0)} x2={MARGIN.left + PLOT_W} y2={yScale(0)} />
      <line className="tcv__axis" x1={MARGIN.left} y1={MARGIN.top}
        x2={MARGIN.left} y2={MARGIN.top + PLOT_H} />
      <line className="tcv__axis" x1={MARGIN.left} y1={MARGIN.top + PLOT_H}
        x2={MARGIN.left + PLOT_W} y2={MARGIN.top + PLOT_H} />
    </>
  );
}

export default memo(CurveAxes);
