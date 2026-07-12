import { useMemo } from 'react';
import type { StationStop, TrainState } from '../../../types/vehicle';
import { STATIONS } from '../data/lineMap';
import './TimetableView.css';

export interface TimetableViewProps {
  currentState: TrainState | null;
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  stationStops?: StationStop[];
  fromStationId: number;
  toStationId: number;
}

interface TimetableRow {
  stationId: number;
  name: string;
  km: number;
  isInScope: boolean;
  isPassed: boolean;
  isCurrent: boolean;
  actualArrival?: number;   // seconds
  stopError?: number;
  inWindow?: boolean;
}

/** 简单时刻表模型: 站间旅行时间 = 距离 / 平均速度 + 停站时间 */
function estimateSchedule(
  stations: typeof STATIONS,
  fromId: number,
  toId: number,
  avgSpeedMs: number,
  dwellS: number,
): Map<number, number> {
  const schedule = new Map<number, number>();
  let cumTime = 0;
  let started = false;
  for (const s of stations) {
    if (s.stationId === fromId) {
      schedule.set(s.stationId, 0);
      started = true;
      continue;
    }
    if (!started) continue;
    const prev = stations[stations.indexOf(s) - 1];
    if (!prev) continue;
    const distM = (s.km - prev.km) * 1000;
    cumTime += distM / avgSpeedMs + dwellS;
    schedule.set(s.stationId, cumTime);
    if (s.stationId === toId) break;
  }
  return schedule;
}

/** 格式化秒数为 mm:ss */
function formatTime(totalSeconds: number): string {
  if (totalSeconds < 0) return '--:--';
  const m = Math.floor(totalSeconds / 60);
  const s = Math.floor(totalSeconds % 60);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

function TimetableView({
  currentState,
  status,
  stationStops = [],
  fromStationId,
  toStationId,
}: TimetableViewProps) {
  const currentTime = currentState?.time ?? 0;
  const currentAbsPos = currentState?.absolutePosition;

  // 推算时刻表
  const schedule = useMemo(
    () => estimateSchedule(STATIONS, fromStationId, toStationId, 15.0, 30.0),
    [fromStationId, toStationId],
  );

  // 已到站的实际时间
  const actualMap = useMemo(() => {
    const map = new Map<number, { arrivalTime: number; stopError: number; inWindow: boolean }>();
    for (const stop of stationStops) {
      map.set(stop.stationId, {
        arrivalTime: stop.arrivalTime,
        stopError: stop.stopError,
        inWindow: stop.inWindow,
      });
    }
    return map;
  }, [stationStops]);

  // 构建表格行
  const rows: TimetableRow[] = useMemo(() => {
    const r: TimetableRow[] = [];
    let lastPassed = false;
    for (const s of STATIONS) {
      const inScope = s.stationId >= fromStationId && s.stationId <= toStationId;
      const actual = actualMap.get(s.stationId);
      const passed = actual !== undefined;
      const isCurrent = !lastPassed && !passed && inScope;
      if (passed) lastPassed = true;
      if (!inScope) continue;
      r.push({
        stationId: s.stationId,
        name: s.displayName,
        km: s.km,
        isInScope: inScope,
        isPassed: passed,
        isCurrent: isCurrent,
        actualArrival: actual?.arrivalTime,
        stopError: actual?.stopError,
        inWindow: actual?.inWindow,
      });
    }
    return r;
  }, [fromStationId, toStationId, actualMap]);

  const isIdle = status === 'idle' || status === 'loading';

  return (
    <section className="timetable-view" aria-label="时刻表">
      <div className="tt__header">
        <div>
          <div className="tt__eyebrow">Timetable</div>
          <h3>时刻表</h3>
        </div>
      </div>

      {isIdle && (
        <div className="tt__idle">
          <p>等待列车运行...</p>
        </div>
      )}

      {!isIdle && (
        <>
          <div className="tt__table-wrap">
            <table className="tt__table">
              <thead>
                <tr>
                  <th>站名</th>
                  <th>里程</th>
                  <th className="tt__col-num">计划到</th>
                  <th className="tt__col-num">实际到</th>
                  <th className="tt__col-num">误差</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const schedT = schedule.get(row.stationId) ?? 0;
                  let statusText = '—';
                  let rowClass = '';
                  if (row.isPassed) {
                    statusText = row.inWindow ? '准点' : row.stopError! > 0 ? '冲标' : '欠标';
                    rowClass = row.inWindow ? 'tt--ontime' : 'tt--late';
                  } else if (row.isCurrent) {
                    statusText = '← 当前';
                    rowClass = 'tt--current';
                  } else {
                    statusText = '待到达';
                  }
                  return (
                    <tr key={row.stationId} className={rowClass}>
                      <td className="tt__name">{row.name}</td>
                      <td className="tt__km">{row.km.toFixed(2)}km</td>
                      <td className="tt__num">{formatTime(schedT)}</td>
                      <td className="tt__num">
                        {row.actualArrival !== undefined ? formatTime(row.actualArrival) : '--:--'}
                      </td>
                      <td className={`tt__num ${row.stopError !== undefined ? (Math.abs(row.stopError) <= 0.5 ? 'tt--green' : 'tt--red') : ''}`}>
                        {row.stopError !== undefined
                          ? `${row.stopError >= 0 ? '+' : ''}${row.stopError.toFixed(2)}m`
                          : '—'}
                      </td>
                      <td className={`tt__status ${rowClass}`}>{statusText}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* 摘要行 */}
          <div className="tt__summary">
            <span>当前仿真时间: {formatTime(currentTime)}</span>
            <span>已到站: {actualMap.size}</span>
            <span>剩余站: {rows.filter((r) => !r.isPassed).length}</span>
          </div>
        </>
      )}
    </section>
  );
}

export default TimetableView;
