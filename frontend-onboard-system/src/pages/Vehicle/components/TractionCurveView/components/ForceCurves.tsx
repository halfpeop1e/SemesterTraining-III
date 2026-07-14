import { memo } from 'react';
import { Y_MIN, Y_MAX, buildTimePath } from '../utils/pathBuilder';
import type { ForcePoint } from '../utils/pathBuilder';

interface ForceCurvesProps {
  pts: ForcePoint[];
  tMin: number;
  tMax: number;
}

function ForceCurves({ pts, tMin, tMax }: ForceCurvesProps) {
  const pathTraction = buildTimePath(pts, (p) => p.tractionKN, tMin, tMax, Y_MIN, Y_MAX);
  const pathBrake = buildTimePath(pts, (p) => -p.brakeKN, tMin, tMax, Y_MIN, Y_MAX);
  const pathResist = buildTimePath(pts, (p) => -p.resistKN, tMin, tMax, Y_MIN, Y_MAX);
  const pathNet = buildTimePath(pts, (p) => p.netKN, tMin, tMax, Y_MIN, Y_MAX);

  return (
    <>
      {pathResist && <path className="tcv__curve tcv__curve--resist" d={pathResist} />}
      {pathNet && <path className="tcv__curve tcv__curve--net" d={pathNet} />}
      {pathBrake && <path className="tcv__curve tcv__curve--brake" d={pathBrake} />}
      {pathTraction && <path className="tcv__curve tcv__curve--traction" d={pathTraction} />}
    </>
  );
}

export default memo(ForceCurves);
