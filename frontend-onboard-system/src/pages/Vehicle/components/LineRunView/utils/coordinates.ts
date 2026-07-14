import { LINE_MAP } from '../../../data/lineMap';
import type { StationStop, StopResult } from '../../../../../types/vehicle';

// ── SVG 布局常量 ──
export const SVG_WIDTH = 1200;
export const SVG_HEIGHT = 390;
export const AXIS_Y = 190;
export const MAP_LEFT = 80;
export const MAP_RIGHT = 1120;
export const MAP_WIDTH = MAP_RIGHT - MAP_LEFT;
export const MIN_WINDOW_M = 60;
export const FOLLOW_MIN_WINDOW_M = 1800;
export const FINISHED_WINDOW_M = 80;
export const TICK_INTERVAL_M = 500;
export const STOP_WINDOW_TOLERANCE_M = 0.5;

// ── ViewWindow 类型 ──
export interface ViewWindow {
  start: number;
  end: number;
}

// ── 工具函数 ──

export function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

export function formatMeters(value: number) {
  if (Math.abs(value) >= 1000) {
    return `${(value / 1000).toFixed(1)}km`;
  }
  return `${Math.round(value)}m`;
}

export function normalizeWindow(window: ViewWindow): ViewWindow {
  const lineStart = LINE_MAP.lineStartM;
  const lineEnd = LINE_MAP.lineEndM;
  const lineLength = lineEnd - lineStart;
  let width = window.end - window.start;

  if (width < MIN_WINDOW_M) {
    width = MIN_WINDOW_M;
  }
  if (width > lineLength) {
    return { start: lineStart, end: lineEnd };
  }

  let start = window.start;
  let end = start + width;

  if (start < lineStart) {
    start = lineStart;
    end = start + width;
  }
  if (end > lineEnd) {
    end = lineEnd;
    start = end - width;
  }

  return { start, end };
}

export function expandWindowAround(center: number, width: number): ViewWindow {
  return normalizeWindow({ start: center - width / 2, end: center + width / 2 });
}

export function buildFollowWindow(position: number): ViewWindow {
  const stations = LINE_MAP.stations;
  const nextStationIndex = stations.findIndex((station) => station.positionM >= position);

  if (nextStationIndex === -1) {
    return normalizeWindow({
      start: stations[Math.max(stations.length - 3, 0)].positionM - 300,
      end: LINE_MAP.lineEndM,
    });
  }

  const startIndex = Math.max(nextStationIndex - 2, 0);
  const endIndex = Math.min(nextStationIndex + 1, stations.length - 1);
  const start = Math.min(LINE_MAP.lineStartM, stations[startIndex].positionM) - 160;
  const end = stations[endIndex].positionM + 320;
  const natural = normalizeWindow({ start, end });
  const width = Math.max(natural.end - natural.start, FOLLOW_MIN_WINDOW_M);

  return expandWindowAround(position, width);
}

export function buildFinishedWindow(targetStopPosition: number, actualStopPosition: number | null): ViewWindow {
  const center = actualStopPosition === null ? targetStopPosition : (targetStopPosition + actualStopPosition) / 2;
  return expandWindowAround(center, FINISHED_WINDOW_M);
}

export function normalizeStopWindowState(stopWindowState: string | undefined) {
  return (stopWindowState ?? 'not_accurate').toLowerCase();
}

export function describeStopWindowState(stopWindowState: string | undefined) {
  const normalized = normalizeStopWindowState(stopWindowState);
  if (normalized === 'in_window') {
    return '停准窗内';
  }
  if (normalized === 'overshoot') {
    return '冲标';
  }
  if (normalized === 'undershoot') {
    return '欠标';
  }
  return '未停准';
}

// ── 驾驶辅助计算 ──

export function computeDrivingAid(
  currentPosition: number,
  currentVelocity: number,
  speedLimit: number,
  stationStops: StationStop[] | undefined,
  targetStopPosition: number,
  positionOffset: number,
  stopResult: StopResult | null,
) {
  let nextStopName = '终点';
  let nextStopAbsM = positionOffset + targetStopPosition;

  if (stationStops && stationStops.length > 0) {
    for (const stop of stationStops) {
      const absTarget = positionOffset + stop.targetPosition;
      if (absTarget >= currentPosition - 0.5) {
        nextStopName = stop.stationName;
        nextStopAbsM = absTarget;
        break;
      }
    }
  }

  const distanceM = Math.max(0, nextStopAbsM - currentPosition);

  const SIM_BRAKE_DECEL = 1.1;
  const brakingDistanceM = currentVelocity > 0 ? (currentVelocity * currentVelocity) / (2 * SIM_BRAKE_DECEL) : 0;
  const brakeMarginM = distanceM - brakingDistanceM;

  const etaS = currentVelocity > 0.1 ? distanceM / currentVelocity : null;

  let lastStopError: number | null = null;
  if (stationStops) {
    const passed = stationStops.filter((s) => positionOffset + s.targetPosition < currentPosition - 0.5 && s.actualPosition > 0);
    if (passed.length > 0) lastStopError = passed[passed.length - 1].stopError;
  } else if (stopResult) {
    lastStopError = stopResult.stopError;
  }

  return {
    nextStopName,
    distanceM,
    brakingDistanceM,
    brakeMarginM,
    etaS,
    speedLimitKmh: speedLimit * 3.6,
    lastStopError,
  };
}

export function formatEta(etaS: number | null): string {
  if (etaS === null || etaS > 9999) return '—';
  if (etaS < 60) return `${Math.round(etaS)}s`;
  return `${Math.floor(etaS / 60)}m${Math.round(etaS % 60)}s`;
}
