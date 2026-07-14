import { memo } from 'react';
import { formatEta } from '../utils/coordinates';

export interface DrivingAidData {
  nextStopName: string;
  distanceM: number;
  brakingDistanceM: number;
  brakeMarginM: number;
  etaS: number | null;
  speedLimitKmh: number;
  lastStopError: number | null;
}

function DrivingAidPanel({ data }: { data: DrivingAidData }) {
  return (
    <div className="line-run-view__driving-aid" aria-label="下一站驾驶辅助">
      <div className="lrv-aid__title">下一站驾驶辅助</div>
      <div className="lrv-aid__grid">
        <div className="lrv-aid__item">
          <span className="lrv-aid__label">下一站</span>
          <span className="lrv-aid__value lrv-aid__value--name">{data.nextStopName}</span>
        </div>
        <div className="lrv-aid__item">
          <span className="lrv-aid__label">距下一站</span>
          <span className="lrv-aid__value">{data.distanceM.toFixed(0)}<small>m</small></span>
        </div>
        <div className="lrv-aid__item">
          <span className="lrv-aid__label">推荐制动距离</span>
          <span className="lrv-aid__value">{data.brakingDistanceM.toFixed(0)}<small>m</small></span>
        </div>
        <div className="lrv-aid__item">
          <span className="lrv-aid__label">制动余量</span>
          <span className={`lrv-aid__value ${data.brakeMarginM < 20 ? 'lrv-aid__value--warn' : 'lrv-aid__value--ok'}`}>
            {data.brakeMarginM.toFixed(0)}<small>m</small>
          </span>
        </div>
        <div className="lrv-aid__item">
          <span className="lrv-aid__label">预计剩余时间</span>
          <span className="lrv-aid__value">{formatEta(data.etaS)}</span>
        </div>
        <div className="lrv-aid__item">
          <span className="lrv-aid__label">当前限速</span>
          <span className="lrv-aid__value">{data.speedLimitKmh.toFixed(0)}<small>km/h</small></span>
        </div>
        <div className="lrv-aid__item lrv-aid__item--full">
          <span className="lrv-aid__label">上次到站误差</span>
          <span className={`lrv-aid__value ${
            data.lastStopError === null ? '' :
            Math.abs(data.lastStopError) <= 0.5 ? 'lrv-aid__value--ok' : 'lrv-aid__value--warn'
          }`}>
            {data.lastStopError === null
              ? '—'
              : `${data.lastStopError >= 0 ? '+' : ''}${data.lastStopError.toFixed(2)}`
            }<small>{data.lastStopError !== null ? 'm' : ''}</small>
          </span>
        </div>
      </div>
    </div>
  );
}

export default memo(DrivingAidPanel);
