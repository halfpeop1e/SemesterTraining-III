import { memo } from 'react';
import { MARGIN, PLOT_H, Y_MIN, Y_MAX, xScale, yScale, clampVal } from '../utils/pathBuilder';

interface OperatingPointProps {
  curTime: number;
  tractionKN: number;
  brakeKN: number;
  phase: string;
  tMin: number;
  tMax: number;
  isVisible: boolean;
}

function OperatingPoint({ curTime, tractionKN, brakeKN, phase, tMin, tMax, isVisible }: OperatingPointProps) {
  if (!isVisible || (tractionKN <= 0.1 && brakeKN <= 0.1)) return null;

  const dotX = xScale(curTime, tMin, tMax);
  const dotYTraction = yScale(clampVal(tractionKN, Y_MIN, Y_MAX));
  const dotYBrake = yScale(clampVal(-brakeKN, Y_MIN, Y_MAX));

  const dotColor = phase === 'traction' ? '#38bdf8'
    : phase === 'braking' ? '#fb7185'
    : phase === 'coast' ? '#facc15'
    : '#94a3b8';

  return (
    <>
      {tractionKN > 0.1 && (
        <g className="tcv__operating-point">
          <circle className="tcv__op-pulse" cx={dotX} cy={dotYTraction} r="12" />
          <circle className="tcv__op-dot" cx={dotX} cy={dotYTraction} r="5"
            style={{ fill: '#38bdf8' }} />
        </g>
      )}
      {brakeKN > 0.1 && (
        <g className="tcv__operating-point">
          <circle className="tcv__op-pulse" cx={dotX} cy={dotYBrake} r="12" />
          <circle className="tcv__op-dot" cx={dotX} cy={dotYBrake} r="5"
            style={{ fill: '#fb7185' }} />
        </g>
      )}
    </>
  );
}

export default memo(OperatingPoint);
