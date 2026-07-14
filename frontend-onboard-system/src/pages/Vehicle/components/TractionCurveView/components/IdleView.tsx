import { memo } from 'react';
import { SVG_W, SVG_H, MARGIN, PLOT_W, PLOT_H } from '../utils/pathBuilder';
import CurveAxes from './CurveAxes';

interface IdleViewProps {
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  yTicks: number[];
  xTickCount: number;
  idleWindowS: number;
}

function IdleView({ status, yTicks, xTickCount, idleWindowS }: IdleViewProps) {
  return (
    <div className="tcv__idle">
      <svg className="tcv__svg" viewBox={`0 0 ${SVG_W} ${SVG_H}`} role="img">
        <defs>
          <linearGradient id="tcvBg" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="#0f172a" />
            <stop offset="100%" stopColor="#020617" />
          </linearGradient>
        </defs>
        <CurveAxes tMin={0} tMax={idleWindowS} yTicks={yTicks} xTickCount={xTickCount} />
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
  );
}

export default memo(IdleView);
