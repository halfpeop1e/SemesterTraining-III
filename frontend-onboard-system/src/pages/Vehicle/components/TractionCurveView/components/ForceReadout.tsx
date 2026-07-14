import { memo } from 'react';

interface ForceReadoutProps {
  time: number;
  vKmh: number;
  tractionKN: number;
  brakeKN: number;
  resistKN: number;
  netKN: number;
  phase: string;
}

function ForceReadout({
  time, vKmh, tractionKN, brakeKN, resistKN, netKN, phase,
}: ForceReadoutProps) {
  const dotColor = phase === 'traction' ? '#38bdf8'
    : phase === 'braking' ? '#fb7185'
    : phase === 'coast' ? '#facc15'
    : '#94a3b8';

  return (
    <div className="tcv__readout">
      <div className="tcv__readout-item">
        <span className="tcv__ro-label">仿真时间</span>
        <span className="tcv__ro-value">{time.toFixed(1)}<small>s</small></span>
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
        <span className="tcv__ro-label">净加速力</span>
        <span className="tcv__ro-value tcv__ro-value--net">
          {netKN.toFixed(1)}<small>kN</small>
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
  );
}

export default memo(ForceReadout);
