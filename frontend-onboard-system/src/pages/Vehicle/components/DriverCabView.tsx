import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import type { SidingStatus, StationStop, StopResult, TrainState } from '../../../types/vehicle';
import { STATIONS } from '../data/lineMap';
import ThreeRailwayView from './ThreeRailwayView';
import './DriverCabView.css';

export interface DriverCabViewProps {
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  currentState: TrainState | null;
  startPosition: number;
  /**
   * 全程固定总目标距离（终点站计划里程），用于 summary/路线信息。
   * 驾驶台"目标距离"仪表盘改为显示下一站距离，不再用此值。
   */
  targetStopPosition: number;
  speedLimit: number;
  stopResult: StopResult | null;
  safetyEventCount?: number;
  isPaused?: boolean;
  /**
   * 多站连续仿真各站停车记录。播放时用来计算"下一站距离"。
   * 单区间仿真时可不传（fallback 到 targetStopPosition）。
   */
  stationStops?: StationStop[];
  /** Supplied by the shared dispatch model for downstream 3D/route extensions. */
  sidingStatuses?: SidingStatus[];
  /** PLC 实际司机台方向手柄状态；未收到帧时保持 undefined。 */
  driverCabDirection?: 'FORWARD' | 'ZERO' | 'REVERSE';
  // ---- 驾驶员控制回调（全部可选，不传时保持纯本地 UI 状态）----
  /** 当前有效驾驶模式（由父组件同步，仅用于显示；不在 render 中调 setState）。 */
  externalDriveMode?: 'ato' | 'manual' | 'emergency' | null;
  /** 司机申请人工接管（ATO → MANUAL）。父组件负责决定是否切换并调用后端。 */
  onRequestManual?: () => void;
  /** 牵引手柄：level 1-7，levelPercent 0~100。父组件决定是否调用后端续算。 */
  onTractionLevel?: (level: number, levelPercent: number) => void;
  /** 制动手柄：level 1-7，targetDecel m/s²，levelPercent 0~100。父组件决定是否调用后端续算。 */
  onBrakeLevel?: (level: number, targetDecel: number, levelPercent: number) => void;
  /** 回零/惰行（手柄归中、牵引0级、制动0级）。父组件发送 coast 指令。 */
  onCoast?: () => void;
  /** 紧急制动按钮。 */
  onEmergencyBrake?: () => void;
  /** 恢复 ATO 自动驾驶（MANUAL → ATO）。父组件调用后端 /control resume_ato。 */
  onRequestAto?: () => void;
  /** EB 停稳后复位到人工模式（EMERGENCY → MANUAL）。父组件调用后端 /control reset_emergency。 */
  onResetEmergency?: () => void;
  /** EMERGENCY 停稳后可复位（drivingMode==='emergency' && summary.nextMode==='manual'）。 */
  canResetEmergency?: boolean;
  /** 受控重置 token，变化时清空牵引/制动级位 UI（resume_ato/reset_emergency 成功后递增）。 */
  handleResetToken?: number;
  /** Physical laboratory desk controls commands while this is true. */
  controlLocked?: boolean;
  controlLockMessage?: string;
}

interface ProjectionPoint {
  x: number;
  y: number;
  scale: number;
}

interface OperatingMode {
  label: string;
  note: string;
  tone: 'idle' | 'ready' | 'active' | 'paused' | 'done' | 'fault';
}

const PHASE_LABELS: Record<string, string> = {
  TRACTION: '牵引',
  COAST: '惰行',
  BRAKING: '制动',
  STOPPED: '停车',
};

const PHASE_SEQUENCE = [
  { key: 'traction', label: '牵引' },
  { key: 'coast', label: '惰行' },
  { key: 'braking', label: '制动' },
  { key: 'stopped', label: '停车' },
];

const STOP_WINDOW_STATE_LABELS: Record<string, string> = {
  in_window: '窗内 / 精度优秀',
  overshoot: '冲标 / 注意',
  undershoot: '欠标 / 注意',
  not_accurate: '未停准 / 故障',
};

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function normalizePhase(phase: string | undefined) {
  return (phase ?? '').toLowerCase();
}

function describePhase(phase: string | undefined) {
  const normalized = normalizePhase(phase);
  return PHASE_LABELS[normalized.toUpperCase()] ?? (phase || '待机');
}

function normalizeStopWindowState(stopWindowState: string | undefined) {
  return (stopWindowState ?? 'not_accurate').toLowerCase();
}

function describeStopWindowState(stopWindowState: string | undefined) {
  const normalized = normalizeStopWindowState(stopWindowState);
  return STOP_WINDOW_STATE_LABELS[normalized] ?? stopWindowState ?? '未停准 / 故障';
}

function formatDistance(value: number) {
  if (Math.abs(value) < 0.05) {
    return '0.0';
  }
  return value.toFixed(1);
}

function mpsToKmh(value: number) {
  return value * 3.6;
}

function getOperatingMode(
  status: DriverCabViewProps['status'],
  isPaused: boolean,
  externalDriveMode?: DriverCabViewProps['externalDriveMode'],
): OperatingMode {
  if (status === 'idle') {
    return { label: '待机', note: '等待仿真任务', tone: 'idle' };
  }
  if (status === 'loading') {
    return { label: '系统准备', note: '后端正在计算仿真结果', tone: 'ready' };
  }
  if (status === 'playing' && externalDriveMode === 'emergency') {
    return { label: '紧急模式', note: '紧急制动状态', tone: 'fault' };
  }
  if (status === 'playing' && isPaused) {
    return {
      label: externalDriveMode === 'ato' ? 'ATO 暂停' : '人工暂停',
      note: '播放暂停，当前帧保持',
      tone: 'paused',
    };
  }
  if (status === 'playing') {
    return externalDriveMode === 'ato'
      ? { label: 'ATO 自动驾驶', note: '按后端 ATO 状态显示', tone: 'active' }
      : { label: '人工驾驶', note: '按后端 MANUAL 状态显示', tone: 'active' };
  }
  if (status === 'finished') {
    return { label: '运行完成', note: '仿真结果已到末帧', tone: 'done' };
  }
  return { label: '系统故障', note: '接口或页面状态异常', tone: 'fault' };
}

function fillRoundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number,
) {
  const r = Math.min(radius, width / 2, height / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.lineTo(x + width - r, y);
  ctx.quadraticCurveTo(x + width, y, x + width, y + r);
  ctx.lineTo(x + width, y + height - r);
  ctx.quadraticCurveTo(x + width, y + height, x + width - r, y + height);
  ctx.lineTo(x + r, y + height);
  ctx.quadraticCurveTo(x, y + height, x, y + height - r);
  ctx.lineTo(x, y + r);
  ctx.quadraticCurveTo(x, y, x + r, y);
  ctx.closePath();
  ctx.fill();
}

function buildProjection(width: number, height: number) {
  const horizonY = height * 0.39;
  const groundY = height * 0.95;
  const vanishingX = width * 0.5;

  return (lateral: number, zMeters: number): ProjectionPoint => {
    const z = Math.max(0, zMeters);
    const scale = 1 / (1 + z / 92);
    return {
      x: vanishingX + lateral * width * 0.42 * scale,
      y: horizonY + (groundY - horizonY) * scale,
      scale,
    };
  };
}

function drawPolygon(ctx: CanvasRenderingContext2D, points: ProjectionPoint[]) {
  if (points.length === 0) {
    return;
  }
  ctx.beginPath();
  ctx.moveTo(points[0].x, points[0].y);
  for (let i = 1; i < points.length; i += 1) {
    ctx.lineTo(points[i].x, points[i].y);
  }
  ctx.closePath();
  ctx.fill();
}

function drawTunnel(ctx: CanvasRenderingContext2D, width: number, height: number, now: number) {
  const sky = ctx.createLinearGradient(0, 0, 0, height);
  sky.addColorStop(0, '#06090d');
  sky.addColorStop(0.46, '#132123');
  sky.addColorStop(1, '#050608');
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, width, height);

  const glow = ctx.createRadialGradient(width * 0.5, height * 0.37, 12, width * 0.5, height * 0.39, width * 0.5);
  glow.addColorStop(0, 'rgba(140, 220, 210, 0.22)');
  glow.addColorStop(0.34, 'rgba(55, 116, 104, 0.18)');
  glow.addColorStop(1, 'rgba(4, 8, 12, 0)');
  ctx.fillStyle = glow;
  ctx.fillRect(0, 0, width, height);

  const scanOffset = (now / 34) % 84;
  ctx.strokeStyle = 'rgba(184, 195, 190, 0.12)';
  ctx.lineWidth = 1;
  for (let x = -80 + scanOffset; x < width + 80; x += 84) {
    ctx.beginPath();
    ctx.moveTo(width * 0.5, height * 0.38);
    ctx.lineTo(x, height);
    ctx.stroke();
  }
}

function drawTrackSurface(ctx: CanvasRenderingContext2D, width: number, height: number) {
  const project = buildProjection(width, height);
  const floorGradient = ctx.createLinearGradient(0, height * 0.38, 0, height);
  floorGradient.addColorStop(0, '#0e1a1b');
  floorGradient.addColorStop(0.54, '#172322');
  floorGradient.addColorStop(1, '#0b0d0f');
  ctx.fillStyle = floorGradient;
  drawPolygon(ctx, [project(-1.2, 720), project(1.2, 720), project(1.55, 0), project(-1.55, 0)]);

  ctx.fillStyle = 'rgba(104, 118, 112, 0.2)';
  drawPolygon(ctx, [project(-0.72, 720), project(0.72, 720), project(1.0, 0), project(-1.0, 0)]);
}

function drawTunnelLights(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  position: number,
  velocity: number,
) {
  const project = buildProjection(width, height);
  const spacing = 60;
  const start = Math.floor(position / spacing) * spacing + spacing;

  for (let world = start; world < position + 780; world += spacing) {
    const z = world - position;
    const left = project(-1.45, z);
    const right = project(1.45, z);
    const alpha = clamp(left.scale * 1.4, 0.06, 0.6);
    const radius = clamp(left.scale * 13, 2, 10);
    ctx.fillStyle = `rgba(151, 232, 214, ${alpha})`;
    ctx.beginPath();
    ctx.arc(left.x, left.y - 28 * left.scale, radius, 0, Math.PI * 2);
    ctx.arc(right.x, right.y - 28 * right.scale, radius, 0, Math.PI * 2);
    ctx.fill();
  }

  if (velocity > 0.2) {
    const streakAlpha = clamp(velocity / 26, 0.08, 0.34);
    ctx.strokeStyle = `rgba(157, 233, 218, ${streakAlpha})`;
    ctx.lineWidth = 2;
    for (let i = 0; i < 7; i += 1) {
      const y = height * (0.5 + i * 0.07);
      ctx.beginPath();
      ctx.moveTo(width * 0.08, y);
      ctx.lineTo(width * 0.3, y - 12);
      ctx.moveTo(width * 0.92, y);
      ctx.lineTo(width * 0.7, y - 12);
      ctx.stroke();
    }
  }
}

function drawSleepers(ctx: CanvasRenderingContext2D, width: number, height: number, position: number) {
  const project = buildProjection(width, height);
  const spacing = 18;
  const start = Math.floor(position / spacing) * spacing + spacing;

  for (let world = start; world < position + 680; world += spacing) {
    const z = world - position;
    const left = project(-0.58, z);
    const right = project(0.58, z);
    const alpha = clamp(left.scale * 1.1, 0.08, 0.68);
    ctx.strokeStyle = `rgba(132, 111, 82, ${alpha})`;
    ctx.lineWidth = clamp(left.scale * 8, 1, 8);
    ctx.beginPath();
    ctx.moveTo(left.x, left.y);
    ctx.lineTo(right.x, right.y);
    ctx.stroke();
  }
}

function drawRails(ctx: CanvasRenderingContext2D, width: number, height: number) {
  const project = buildProjection(width, height);

  [-0.28, 0.28].forEach((lateral) => {
    const far = project(lateral, 720);
    const near = project(lateral, 0);

    ctx.strokeStyle = 'rgba(2, 6, 9, 0.84)';
    ctx.lineWidth = 12;
    ctx.beginPath();
    ctx.moveTo(far.x, far.y);
    ctx.lineTo(near.x, near.y);
    ctx.stroke();

    const railGradient = ctx.createLinearGradient(far.x, far.y, near.x, near.y);
    railGradient.addColorStop(0, '#53645f');
    railGradient.addColorStop(0.64, '#b9c3bf');
    railGradient.addColorStop(1, '#e1e7e4');
    ctx.strokeStyle = railGradient;
    ctx.lineWidth = 5;
    ctx.beginPath();
    ctx.moveTo(far.x, far.y);
    ctx.lineTo(near.x, near.y);
    ctx.stroke();
  });
}

function drawPlatformSide(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  zFront: number,
  zBack: number,
  side: 'left' | 'right',
) {
  const project = buildProjection(width, height);
  const inner = side === 'left' ? -0.58 : 0.58;
  const outer = side === 'left' ? -1.38 : 1.38;
  ctx.fillStyle = side === 'left' ? 'rgba(78, 88, 86, 0.9)' : 'rgba(88, 96, 91, 0.9)';
  drawPolygon(ctx, [
    project(inner, zFront),
    project(outer, zFront),
    project(outer, zBack),
    project(inner, zBack),
  ]);

  const front = project(inner, zFront);
  const back = project(inner, zBack);
  ctx.strokeStyle = 'rgba(250, 204, 21, 0.82)';
  ctx.lineWidth = clamp(front.scale * 6, 1, 5);
  ctx.beginPath();
  ctx.moveTo(front.x, front.y);
  ctx.lineTo(back.x, back.y);
  ctx.stroke();
}

function drawStationAndStopTarget(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  distanceToTarget: number,
  status: DriverCabViewProps['status'],
  stopResult: StopResult | null,
  phase: string | undefined,
  nextStopName: string | undefined,
) {
  const normalizedPhase = (phase ?? '').toLowerCase();
  const isStopped = normalizedPhase === 'stopped' || normalizedPhase === 'dwell';
  const isNearStop = Math.abs(distanceToTarget) <= 3;

  // 停稳 / 极近停车点：固定显示站台牌 + 停车牌（不透视、不随 scale 缩小、不跑远消失）
  if (isStopped || isNearStop) {
    const panelW = 188;
    const panelH = 96;
    const panelX = Math.max(12, width - panelW - 16);
    const panelY = 58;
    ctx.fillStyle = 'rgba(5, 10, 11, 0.86)';
    fillRoundRect(ctx, panelX, panelY, panelW, panelH, 8);

    // 站名（当前/下一站）
    const stationLabel = nextStopName ?? '目标站';
    ctx.fillStyle = '#fde68a';
    ctx.font = '700 14px "Microsoft YaHei", sans-serif';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';
    ctx.fillText(`◎ ${stationLabel}`, panelX + 12, panelY + 10);

    // 固定 STOP 牌（不透视）
    const stopW = 58;
    const stopH = 26;
    const stopX = panelX + 12;
    const stopY = panelY + 36;
    ctx.fillStyle = 'rgba(185, 28, 28, 0.96)';
    fillRoundRect(ctx, stopX, stopY, stopW, stopH, 4);
    ctx.fillStyle = '#fff7ed';
    ctx.font = '800 13px "Microsoft YaHei", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('STOP', stopX + stopW / 2, stopY + stopH / 2);

    // 停准误差 / 停车窗判定（in_window / overshoot / undershoot）
    const err = stopResult?.stopError;
    const winState = normalizeStopWindowState(stopResult?.stopWindowState);
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';
    ctx.fillStyle = '#dce8e6';
    ctx.font = '12px "Microsoft YaHei", sans-serif';
    if (err !== undefined && err !== null) {
      ctx.fillText(`误差 ${err.toFixed(2)} m`, stopX + stopW + 10, stopY + 2);
    } else {
      ctx.fillText(`距停 ${formatDistance(Math.abs(distanceToTarget))} m`, stopX + stopW + 10, stopY + 2);
    }
    const winLabel = stopResult
      ? describeStopWindowState(stopResult.stopWindowState)
      : (Math.abs(distanceToTarget) <= 0.5 ? '停准窗内' : '接近停车窗');
    ctx.fillStyle = winState === 'in_window'
      ? '#86efac'
      : (winState === 'overshoot' || winState === 'undershoot' ? '#fde68a' : '#aebdc4');
    ctx.fillText(winLabel, stopX + stopW + 10, stopY + 18);
    void status;
    void height;
    return;
  }

  // 接近站台但未停稳（|distanceToTarget|>3m）：保持现有透视绘制（不变）
  if (distanceToTarget > 340 || distanceToTarget < -80) {
    return;
  }

  const project = buildProjection(width, height);
  // VISUAL/SIM: platform length is assumed for HMI visualization only.
  // Platform = 120 m total; stop marker is 100 m from entry end (near/camera side)
  // and 20 m from the far end. After stopping, most platform is behind the camera.
  const stationFront = clamp(distanceToTarget - 100, 4, 320); // entry (near/camera) end
  const stationBack = clamp(distanceToTarget + 20, stationFront + 28, 420); // far end
  drawPlatformSide(ctx, width, height, stationFront, stationBack, 'left');
  drawPlatformSide(ctx, width, height, stationFront, stationBack, 'right');

  const stopZ = clamp(distanceToTarget, 5, 320);
  const left = project(-0.48, stopZ);
  const right = project(0.48, stopZ);
  ctx.strokeStyle = 'rgba(248, 113, 113, 0.95)';
  ctx.lineWidth = clamp(left.scale * 6, 1, 6);
  ctx.setLineDash([10 * left.scale, 7 * left.scale]);
  ctx.beginPath();
  ctx.moveTo(left.x, left.y);
  ctx.lineTo(right.x, right.y);
  ctx.stroke();
  ctx.setLineDash([]);

  const sign = project(0.78, stopZ + 8);
  const signWidth = clamp(sign.scale * 138, 28, 104);
  const signHeight = clamp(sign.scale * 44, 16, 34);
  ctx.fillStyle = 'rgba(185, 28, 28, 0.94)';
  fillRoundRect(ctx, sign.x - signWidth / 2, sign.y - signHeight - 22 * sign.scale, signWidth, signHeight, 5);
  ctx.fillStyle = '#fff7ed';
  ctx.font = `${clamp(sign.scale * 18, 8, 14)}px "Microsoft YaHei", sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('STOP', sign.x, sign.y - signHeight / 2 - 22 * sign.scale);

  if (status === 'finished' && stopResult) {
    const panelX = width - 226;
    const panelY = 18;
    ctx.fillStyle = 'rgba(5, 10, 11, 0.78)';
    fillRoundRect(ctx, panelX, panelY, 206, 78, 7);
    ctx.fillStyle = stopResult.success ? '#86efac' : '#fdba74';
    ctx.font = '700 13px "Microsoft YaHei", sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText(`停车窗：${describeStopWindowState(stopResult.stopWindowState)}`, panelX + 14, panelY + 24);
    ctx.fillStyle = '#dce8e6';
    ctx.font = '12px "Microsoft YaHei", sans-serif';
    ctx.fillText(`误差 ${stopResult.stopError.toFixed(2)} m`, panelX + 14, panelY + 48);
  }
}

/**
 * 计算下一站距离：返回 stationStops 中第一个 targetPosition >= currentPosition 的站点。
 * 若无 stationStops（单区间）则 fallback 到 targetStopPosition。
 * 若已过所有站则返回 0。
 */
function computeDistanceToNextStop(
  currentPosition: number,
  targetStopPosition: number,
  stationStops: DriverCabViewProps['stationStops'],
): { distance: number; nextStopName?: string } {
  if (!stationStops || stationStops.length === 0) {
    // 单区间 fallback
    return { distance: Math.max(0, targetStopPosition - currentPosition) };
  }
  for (const stop of stationStops) {
    if (stop.targetPosition >= currentPosition - 0.5) {
      const d = Math.max(0, stop.targetPosition - currentPosition);
      return { distance: d, nextStopName: stop.stationName };
    }
  }
  // 已过所有站
  return { distance: 0, nextStopName: stationStops[stationStops.length - 1].stationName };
}

function drawCanvasHud(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  props: DriverCabViewProps,
) {
  const velocity = props.currentState?.velocity ?? 0;
  const speedKmh = mpsToKmh(velocity);
  const limitKmh = mpsToKmh(props.speedLimit);
  const currentPosition = props.currentState?.position ?? props.startPosition;
  // 驾驶台目标距离 = 下一站距离（目标二）
  const { distance: distanceToTarget } = computeDistanceToNextStop(
    currentPosition, props.targetStopPosition, props.stationStops,
  );
  const phase = normalizePhase(props.currentState?.phase);
  const mode = getOperatingMode(props.status, Boolean(props.isPaused), props.externalDriveMode);
  const inbound = distanceToTarget >= 0 && distanceToTarget < 200;
  const braking = phase === 'braking';

  ctx.fillStyle = 'rgba(4, 9, 10, 0.74)';
  fillRoundRect(ctx, 18, 18, 238, 76, 7);
  ctx.fillStyle = props.status === 'error' ? '#fecdd3' : props.isPaused ? '#fde68a' : '#b7f7df';
  ctx.font = '700 13px "Microsoft YaHei", sans-serif';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  ctx.fillText(mode.label, 34, 42);
  ctx.fillStyle = '#d8e5e4';
  ctx.font = '12px "Microsoft YaHei", sans-serif';
  ctx.fillText(`阶段 ${describePhase(props.currentState?.phase)}`, 34, 66);

  const metricsWidth = 278;
  ctx.fillStyle = 'rgba(4, 9, 10, 0.72)';
  fillRoundRect(ctx, width - metricsWidth - 18, height - 106, metricsWidth, 88, 7);
  ctx.fillStyle = '#f8fafc';
  ctx.font = '800 16px Consolas, "SFMono-Regular", monospace'; // HMI overlay: was 22px, reduced for realism
  ctx.textAlign = 'left';
  ctx.fillText(`${speedKmh.toFixed(0)} km/h`, width - metricsWidth, height - 76);
  ctx.fillStyle = '#a8bac1';
  ctx.font = '11px "Microsoft YaHei", sans-serif';
  ctx.fillText(`限速 ${limitKmh.toFixed(0)} km/h · ${props.speedLimit.toFixed(2)} m/s`, width - metricsWidth, height - 56);
  ctx.fillText(`目标距离 ${formatDistance(distanceToTarget)} m`, width - metricsWidth, height - 38);

  const chipY = height - 40;
  let chipX = 18;
  const stopped = phase === 'stopped' || phase === 'dwell';
  [
    stopped ? '停稳' : null,
    inbound ? '入站 < 200m' : null,
    braking ? '制动提示' : null,
    props.isPaused ? '播放暂停' : null,
  ].forEach((chip) => {
    if (!chip) {
      return;
    }
    const chipWidth = chip.length * 13 + 22;
    ctx.fillStyle = chip === '制动提示'
      ? 'rgba(185, 28, 28, 0.82)'
      : chip === '停稳'
        ? 'rgba(6, 78, 59, 0.85)'
        : 'rgba(180, 83, 9, 0.82)';
    fillRoundRect(ctx, chipX, chipY, chipWidth, 26, 6);
    ctx.fillStyle = '#fff7ed';
    ctx.font = '700 12px "Microsoft YaHei", sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText(chip, chipX + 11, chipY + 14);
    chipX += chipWidth + 8;
  });
}

// 站台信息 — 在 canvas 内绘制，不用 DOM 覆盖层
function drawStationHud(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  currentPosition: number,
) {
  const ARRIVAL = 30;
  let passedName = '起点';
  let nextName = '终点';
  let arriving = false;

  for (let i = 0; i < STATIONS.length; i += 1) {
    const stPos = STATIONS[i].positionM;
    const name = STATIONS[i].displayNameOverride ?? STATIONS[i].displayName;
    if (Math.abs(currentPosition - stPos) <= ARRIVAL) {
      arriving = true;
      nextName = name;
      break;
    }
    if (currentPosition >= stPos) {
      passedName = name;
      if (i + 1 < STATIONS.length) {
        const n = STATIONS[i + 1];
        nextName = n.displayNameOverride ?? n.displayName;
      }
    } else {
      if (nextName === '终点') {
        nextName = name;
      }
      break;
    }
  }

  const text = arriving
    ? `◎  即将到站  ${nextName}`
    : `◀ 已过  ${passedName}     下一站  ${nextName} ▶`;

  ctx.save();
  ctx.font = '700 13px "Microsoft YaHei", sans-serif';
  const tw = ctx.measureText(text).width;
  const pw = tw + 28;
  const ph = 28;
  const px = (width - pw) / 2;
  const py = height - ph - 10;

  ctx.fillStyle = arriving ? 'rgba(60, 50, 5, 0.82)' : 'rgba(5, 10, 12, 0.76)';
  fillRoundRect(ctx, px, py, pw, ph, 14);

  ctx.fillStyle = arriving ? '#fde68a' : '#d5e8ee';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(text, width / 2, py + ph / 2);
  ctx.restore();
}

function drawCabViewCanvas(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  props: DriverCabViewProps,
  now: number,
) {
  const currentPosition = props.currentState?.position ?? props.startPosition;
  const velocity = props.currentState?.velocity ?? 0;

  // HUD-only mode: canvas is transparent so Three.js shows through.
  // Only draw overlays (station info and HMI metrics). Physical station geometry is rendered by ThreeRailwayView.
  // The tunnel / track surface / lights are rendered by ThreeRailwayView.
  ctx.clearRect(0, 0, width, height);

  // Subtle vignette so HUD text is readable against 3D background
  const vignette = ctx.createRadialGradient(width * 0.5, height * 0.5, height * 0.3, width * 0.5, height * 0.5, width * 0.62);
  vignette.addColorStop(0, 'rgba(0, 0, 0, 0)');
  vignette.addColorStop(1, 'rgba(0, 0, 0, 0.38)');
  ctx.fillStyle = vignette;
  ctx.fillRect(0, 0, width, height);

  // Frame border
  ctx.strokeStyle = 'rgba(206, 218, 216, 0.14)';
  ctx.lineWidth = 2;
  ctx.strokeRect(10, 10, width - 20, height - 20);

  // Station approach HUD
  drawStationHud(ctx, width, height, currentPosition);
  // Speed / phase / distance HUD chips
  drawCanvasHud(ctx, width, height, props);

  // 停稳 / 极近停车点：固定站台牌 + 停车牌（不透视），叠在最上层
  const phase = normalizePhase(props.currentState?.phase);
  const { distance: distanceToTarget, nextStopName } = computeDistanceToNextStop(
    currentPosition, props.targetStopPosition, props.stationStops,
  );
  const isStopped = phase === 'stopped' || phase === 'dwell';
  const isNearStop = Math.abs(distanceToTarget) <= 3;
  if (isStopped || isNearStop) {
    drawStationAndStopTarget(
      ctx, width, height, distanceToTarget, props.status, props.stopResult,
      props.currentState?.phase, nextStopName,
    );
  }

  void velocity; // used by ThreeRailwayView, not needed here
  void now;      // was used by drawTunnel scan lines, not needed in HUD-only mode
}

type DriveMode = 'ato' | 'manual';

/** Whether Three.js is available (dynamic import succeeds) */
let threeAvailable: boolean | null = null;

function DriverCabView({
  status,
  currentState,
  startPosition,
  targetStopPosition,
  speedLimit,
  stopResult,
  safetyEventCount = 0,
  isPaused = false,
  stationStops,
  driverCabDirection,
  externalDriveMode,
  onRequestManual,
  onTractionLevel,
  onBrakeLevel,
  onCoast,
  onEmergencyBrake,
  onRequestAto,
  onResetEmergency,
  canResetEmergency = false,
  handleResetToken,
  controlLocked = false,
  controlLockMessage,
}: DriverCabViewProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  // 驾驶员控制面板 UI 状态
  const [driveMode, setDriveMode] = useState<DriveMode>('manual');
  const [tractionLevelUI, setTractionLevelUI] = useState(0);
  const [brakeLevelUI, setBrakeLevelUI] = useState(0);
  const [emergencyActive, setEmergencyActive] = useState(false);
  const [manualRequestState, setManualRequestState] = useState<'idle' | 'pending'>('idle');
  const [modeSwitching, setModeSwitching] = useState(false);
  const [modeSwitchHint, setModeSwitchHint] = useState<string | null>(null);
  const beginModeSwitchCooldown = useCallback((targetMode: string) => {
    setModeSwitching(true);
    setModeSwitchHint(`正在切换至${targetMode === 'ato' ? 'ATO' : '人工'}模式…`);
    setTimeout(() => {
      setModeSwitching(false);
      setModeSwitchHint(`已切换至${targetMode === 'ato' ? 'ATO' : '人工'}模式`);
      setTimeout(() => setModeSwitchHint(null), 2000);
    }, 1500);
  }, []);
  const isEmergencyMode = externalDriveMode === 'emergency';
  const isModeControlled = externalDriveMode === 'ato' || externalDriveMode === 'manual' || isEmergencyMode;
  const effectiveDriveMode: DriveMode =
    externalDriveMode === 'ato' || externalDriveMode === 'manual' ? externalDriveMode : driveMode;
  const handlesDisabled = controlLocked || isEmergencyMode || effectiveDriveMode !== 'manual';

  // 同步外部模式：用 useEffect，不在 render 期间 setState
  useEffect(() => {
    if (externalDriveMode === 'manual') {
      if (driveMode !== 'manual') setDriveMode('manual');
      setEmergencyActive(false);
      setManualRequestState('idle');
      setModeSwitchHint((prev) => (prev && prev.includes('人工') ? prev : '已切换至人工'));
    } else if (externalDriveMode === 'ato') {
      if (driveMode !== 'ato') setDriveMode('ato');
      setEmergencyActive(false);
      // Keep "等待批准" while still ATO after a manual request; only clear when
      // mode actually changes away from pending (manual/emergency) or on explicit reset.
      if (manualRequestState !== 'pending') {
        setManualRequestState('idle');
      }
    } else if (externalDriveMode === 'emergency') {
      setEmergencyActive(true);
      setManualRequestState('idle');
    }
  }, [externalDriveMode]); // eslint-disable-line react-hooks/exhaustive-deps

  // 受控重置 token：变化时清空牵引/制动级位 UI（resume_ato/reset_emergency 成功后由父组件递增）
  const prevResetTokenRef = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (handleResetToken === undefined) return;
    if (prevResetTokenRef.current === undefined) {
      prevResetTokenRef.current = handleResetToken;
      return;
    }
    if (prevResetTokenRef.current !== handleResetToken) {
      prevResetTokenRef.current = handleResetToken;
      setTractionLevelUI(0);
      setBrakeLevelUI(0);
    }
  }, [handleResetToken]);

  // 用于 Canvas 渲染的 props 快照（含 stationStops，驾驶台 HUD 用于计算下一站距离）
  const propsRef = useRef<DriverCabViewProps>({
    status,
    currentState,
    startPosition,
    targetStopPosition,
    speedLimit,
    stopResult,
    safetyEventCount,
    isPaused,
    stationStops,
  });

  propsRef.current = {
    status,
    currentState,
    startPosition,
    targetStopPosition,
    speedLimit,
    stopResult,
    safetyEventCount,
    isPaused,
    stationStops,
  };

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) {
      return undefined;
    }

    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return undefined;
    }

    let animationFrame = 0;

    const resizeCanvas = () => {
      const rect = canvas.getBoundingClientRect();
      const width = Math.max(rect.width, 320);
      const height = Math.max(rect.height, 240);
      const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };

    const draw = (now: number) => {
      const rect = canvas.getBoundingClientRect();
      const width = Math.max(rect.width, 320);
      const height = Math.max(rect.height, 240);
      drawCabViewCanvas(ctx, width, height, propsRef.current, now);
      animationFrame = window.requestAnimationFrame(draw);
    };

    const observer = new ResizeObserver(resizeCanvas);
    observer.observe(canvas);
    resizeCanvas();
    animationFrame = window.requestAnimationFrame(draw);

    return () => {
      observer.disconnect();
      window.cancelAnimationFrame(animationFrame);
    };
  }, []);

  const currentPosition = currentState?.position ?? startPosition;
  const currentVelocity = currentState?.velocity ?? 0;
  const currentAcceleration = currentState?.acceleration ?? 0;
  const speedKmh = mpsToKmh(currentVelocity);
  const speedLimitKmh = mpsToKmh(speedLimit);
  // 目标距离 = 下一站距离（目标二）
  const { distance: distanceToTarget, nextStopName } = computeDistanceToNextStop(
    currentPosition, targetStopPosition, stationStops,
  );
  // 全程总目标距离（用于 summary 信息展示）
  const totalDistanceToTarget = targetStopPosition - currentPosition;
  const normalizedPhase = normalizePhase(currentState?.phase);
  const operatingMode = getOperatingMode(status, isPaused, externalDriveMode);
  const speedRatio = speedLimit > 0 ? currentVelocity / speedLimit : 0;
  const speedGaugeDeg = clamp(speedRatio, 0, 1.18) * 270;
  const speedMarkerLeft = clamp(speedRatio / 1.2, 0, 1) * 100;
  const speedStateClass =
    speedLimit > 0 && currentVelocity > speedLimit
      ? 'is-over-limit'
      : speedLimit > 0 && currentVelocity >= speedLimit * 0.9
        ? 'is-near-limit'
        : 'is-normal';
  const stopWindowState = normalizeStopWindowState(stopResult?.stopWindowState);
  const stopPanelState = status === 'finished' && stopResult ? stopWindowState : 'pending';
  const leverMode = currentAcceleration > 0.05 ? 'traction' : currentAcceleration < -0.05 ? 'brake' : 'neutral';
  const tractionLevel = clamp(currentAcceleration / 1.1, 0, 1);
  const brakeLevel = clamp(Math.abs(currentAcceleration) / 1.3, 0, 1);
  const leverTopPercent =
    leverMode === 'traction'
      ? 50 - tractionLevel * 34
      : leverMode === 'brake'
        ? 50 + brakeLevel * 34
        : 50;
  const accelMagnitude = clamp(Math.abs(currentAcceleration) / 1.4, 0, 1);
  const accelClass =
    currentAcceleration > 0.05
      ? 'is-positive'
      : currentAcceleration < -0.05
        ? 'is-negative'
        : 'is-neutral';

  const speedGaugeStyle = {
    '--speed-gauge-deg': `${speedGaugeDeg}deg`,
    '--speed-marker-left': `${speedMarkerLeft}%`,
  } as CSSProperties;

  const leverStyle = {
    '--lever-top': `${leverTopPercent}%`,
  } as CSSProperties;

  const directionPosition =
    driverCabDirection === 'FORWARD'
      ? 'forward'
      : driverCabDirection === 'REVERSE'
        ? 'reverse'
        : driverCabDirection === 'ZERO'
          ? 'zero'
          : 'unknown';
  const directionLabel =
    driverCabDirection === 'FORWARD'
      ? '前进'
      : driverCabDirection === 'REVERSE'
        ? '后退'
        : driverCabDirection === 'ZERO'
          ? '零位'
          : '暂无方向数据';

  const accelStyle = {
    '--accel-level': `${accelMagnitude * 50}%`,
  } as CSSProperties;

  const distanceTrend = useMemo(() => {
    if (distanceToTarget === 0 && stationStops && stationStops.length > 0) {
      // 已过所有站或已到终点
      const allPassed = stationStops.every((s) => s.actualPosition <= currentPosition + 1);
      return allPassed ? '已到达' : '到站';
    }
    if (distanceToTarget > 300) {
      return '巡航区间';
    }
    if (distanceToTarget >= 200) {
      return '接近目标';
    }
    if (distanceToTarget >= 0) {
      return '入站区间';
    }
    return '已越过目标';
  }, [distanceToTarget, stationStops, currentPosition]);

  const systemLights = useMemo(
    () => [
      {
        label: '安全保护',
        value: safetyEventCount === 0 ? '正常' : `已触发 ${safetyEventCount}`,
        tone: safetyEventCount === 0 ? 'ok' : 'warn',
      },
      {
        label: 'ATP 防护',
        value: '仿真防护',
        tone: 'info',
      },
      {
        label: '通信状态',
        value: status === 'error' ? '异常' : '已连接',
        tone: status === 'error' ? 'danger' : 'ok',
      },
      {
        label: '系统状态',
        value: status === 'error' ? '故障' : '正常',
        tone: status === 'error' ? 'danger' : 'ok',
      },
    ],
    [safetyEventCount, status],
  );

  // Speed ratio for ThreeRailwayView motion sense
  const speedRatioForThree = speedLimit > 0 ? clamp(currentVelocity / speedLimit, 0, 1.2) : 0;

  return (
    <section
      className={`driver-cab-view driver-cab-view--${status} ${isPaused ? 'is-paused' : ''}`}
      aria-label="车载驾驶台运行视景"
    >
      <div className="driver-cab-view__layout">

        {/* ── 左列：3D场景 + Canvas HUD + 底部仪表条 ── */}
        <div className="driver-cab-view__left-col">

          <div className="driver-cab-view__viewport">
            {/* Three.js 3D 背景层：不白屏兜底 */}
            <ThreeRailwayView
              currentState={currentState}
              speedRatio={speedRatioForThree}
              targetStopPosition={targetStopPosition}
              stationStops={stationStops}
              className="driver-cab-view__three-bg"
            />
            {/* Canvas HUD 叠加层（透明背景，叠在 Three.js 上方）*/}
            <canvas ref={canvasRef} className="driver-cab-view__canvas driver-cab-view__canvas--hud" aria-label="驾驶台仪表叠加层" />
            <div className={`driver-cab-view__mode-chip driver-cab-view__mode-chip--${operatingMode.tone}`}>
              <span />
              <strong>{operatingMode.label}</strong>
              <small>{currentState ? `T+${currentState.time.toFixed(1)}s` : 'T+0.0s'}</small>
            </div>
          </div>

          {/* 底部仪表条 */}
          <div className="driver-cab-view__console" aria-label="驾驶台仪表">
            <div className={`cab-instrument cab-instrument--speed ${speedStateClass}`} style={speedGaugeStyle}>
              <div className="cab-instrument__label">速度</div>
              <div className="cab-speed-gauge" aria-label="速度仪表">
                <div className="cab-speed-gauge__inner">
                  <span className="cab-speed-gauge__value">{speedKmh.toFixed(0)}</span>
                  <span className="cab-speed-gauge__unit">km/h</span>
                  <span className="cab-speed-gauge__mps">{currentVelocity.toFixed(2)} m/s</span>
                </div>
              </div>
              <div className="cab-speed-reference" aria-label="限速参考">
                <span className="cab-speed-reference__bar" />
                <span className="cab-speed-reference__marker" />
              </div>
              <div className="cab-instrument__subline">限速 {speedLimitKmh.toFixed(0)} km/h</div>
            </div>

            <div className={`cab-instrument cab-instrument--accel ${accelClass}`} style={accelStyle}>
              <div className="cab-instrument__label">加速度</div>
              <div className="cab-accel-meter">
                <span className="cab-accel-meter__axis" />
                <span className="cab-accel-meter__fill" />
              </div>
              <div className="cab-accel-value">
                <span>{currentAcceleration.toFixed(2)}</span>
                <small>m/s²</small>
              </div>
              <div className="cab-instrument__subline">
                {accelClass === 'is-positive' ? '牵引' : accelClass === 'is-negative' ? '制动' : '惰行'}
              </div>
            </div>

            <div className="cab-instrument cab-instrument--distance">
              <div className="cab-instrument__label">下一站距离</div>
              <div className={distanceToTarget <= 0 ? 'cab-distance is-passed' : 'cab-distance'}>
                <span>{distanceToTarget === 0 ? '0.0' : formatDistance(distanceToTarget)}</span>
                <small>m</small>
              </div>
              {nextStopName && (
                <div className="cab-instrument__stop-name" title={nextStopName}>{nextStopName}</div>
              )}
              <div className="cab-instrument__subline">{distanceTrend}</div>
            </div>

            <div className="cab-instrument cab-instrument--phase">
              <div className="cab-instrument__label">运行阶段</div>
              <div className="cab-phase-strip">
                {PHASE_SEQUENCE.map((phase) => (
                  <div
                    className={`cab-phase-step ${normalizedPhase === phase.key ? 'is-active' : ''}`}
                    key={phase.key}
                  >
                    <span />
                    <strong>{phase.label}</strong>
                  </div>
                ))}
              </div>
            </div>

            <div className={`cab-instrument cab-instrument--lever cab-lever--${leverMode}`} style={leverStyle}>
              <div className="cab-instrument__label">牵引 / 制动手柄</div>
              <div className="cab-lever">
                <span className="cab-lever__slot" />
                <span className="cab-lever__fill" />
                <span className="cab-lever__handle" />
              </div>
              <div className="cab-instrument__subline">
                {leverMode === 'traction' ? '牵引（向上）' : leverMode === 'brake' ? '制动（向下）' : '惰行（零位）'}
              </div>
            </div>

            <div className={`cab-instrument cab-instrument--direction cab-direction--${directionPosition}`}>
              <div className="cab-instrument__label">司机台方向</div>
              <div className="cab-direction" aria-label="司机台方向手柄">
                <span className="cab-direction__slot" />
                <span className="cab-direction__mark cab-direction__mark--forward">前进</span>
                <span className="cab-direction__mark cab-direction__mark--zero">零位</span>
                <span className="cab-direction__mark cab-direction__mark--reverse">后退</span>
                <span className="cab-direction__handle" />
              </div>
              <div className="cab-instrument__subline">{directionLabel}</div>
            </div>
          </div>

        </div>

        {/* ── 右列：状态面板 + 控制面板 ── */}
        <aside className="driver-cab-view__right-col" aria-label="车载系统状态与控制">

          <div className="driver-cab-view__side-console">
            <div className={`cab-mode-panel cab-mode-panel--${operatingMode.tone}`}>
              <div className="cab-mode-row">
                <span className="cab-mode-indicator" />
                <span className="cab-mode-label">{operatingMode.label}</span>
                <small className="cab-mode-time">{currentState ? `T+${currentState.time.toFixed(0)}s` : 'T+0s'}</small>
              </div>
            </div>

            <div className="cab-system-panel">
              <span className="cab-panel-label">系统状态灯</span>
              <div className="cab-system-lights">
                {systemLights.map((light) => (
                  <div className={`cab-system-light cab-system-light--${light.tone}`} key={light.label}>
                    <span />
                    <div>
                      <strong>{light.label}</strong>
                      <small>{light.value}</small>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div className={`cab-stop-badge-panel cab-stop-badge-panel--${stopPanelState}`}>
              <div className="cab-stop-row">
                <span className="cab-panel-label">停站精度</span>
                <strong className="cab-stop-value">
                  {status === 'finished' && stopResult
                    ? describeStopWindowState(stopResult.stopWindowState)
                    : '待结果'}
                </strong>
              </div>
              {status === 'finished' && stopResult && (
                <small className="cab-stop-error">误差 {stopResult.stopError.toFixed(2)} m</small>
              )}
            </div>

            {/* 各站停车精度 strip（目标三：紧凑站点条，不做大表格）*/}
            {stationStops && stationStops.length > 0 && (
              <div className="cab-station-stops" aria-label="各站停车精度">
                <span className="cab-panel-label">各站精度</span>
                <div className="cab-station-stops__list">
                  {stationStops.map((stop, idx) => {
                    // 判断是否为当前站：最近刚到达（actualPosition <= currentPosition + 1）
                    const isPassed = stop.actualPosition <= currentPosition + 1;
                    const isNearest = isPassed && (
                      idx === stationStops.length - 1
                      || stationStops[idx + 1].actualPosition > currentPosition + 1
                    );
                    const hasResult = isPassed;
                    return (
                      <div
                        key={stop.stationId}
                        className={`cab-stop-chip ${hasResult ? (stop.inWindow ? 'is-ok' : 'is-warn') : 'is-pending'} ${isNearest ? 'is-current' : ''}`}
                        title={`${stop.stationName}\n目标 ${stop.targetPosition.toFixed(0)}m\n实停 ${stop.actualPosition.toFixed(0)}m\n误差 ${stop.stopError.toFixed(2)}m\n驻留 ${stop.dwellTime.toFixed(0)}s`}
                      >
                        <span className="cab-stop-chip__name">{stop.stationName}</span>
                        {hasResult ? (
                          <span className="cab-stop-chip__err">
                            {stop.stopError >= 0 ? '+' : ''}{stop.stopError.toFixed(2)}m
                          </span>
                        ) : (
                          <span className="cab-stop-chip__err">—</span>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>

          {/* 驾驶员控制面板 */}
          <div className="driver-control-panel" aria-label="驾驶员控制面板">
            <div className="dcp-section dcp-section--mode">
              <span className="dcp-section__label">
                驾驶模式{externalDriveMode === 'emergency' ? ' ⚠紧急' : ''}
              </span>
              {controlLocked && (
                <div className="dcp-handle-hint">
                  {controlLockMessage ?? '实验室司机台正在控制此列车'}
                </div>
              )}
              <div className="dcp-mode-buttons" role="group">
                <button
                  type="button"
                  className={`dcp-mode-btn dcp-mode-btn--ato ${effectiveDriveMode === 'ato' ? 'is-active' : ''}`}
                  disabled={controlLocked || isModeControlled}
                  onClick={() => {
                    if (!isModeControlled) setDriveMode('ato');
                  }}>
                  <span className="dcp-mode-btn__dot" />ATO 自动
                </button>
                <button
                  type="button"
                  className={`dcp-mode-btn dcp-mode-btn--manual ${effectiveDriveMode === 'manual' ? 'is-active' : ''}`}
                  disabled={controlLocked || isEmergencyMode || effectiveDriveMode === 'manual'}
                  onClick={() => {
                    if (isModeControlled) {
                      setManualRequestState('pending');
                      if (onRequestManual) onRequestManual();
                    } else {
                      setDriveMode('manual');
                      setManualRequestState('idle');
                    }
                  }}>
                  <span className="dcp-mode-btn__dot" />人工驾驶
                </button>
              </div>
            </div>

            {/* 手柄状态指示条 */}
            <div className="dcp-handle-status">
              <div className="dcp-handle-status__row">
                <span className="dcp-handle-status__label">模式</span>
                <span className={`dcp-handle-status__value dcp-handle-status__value--${effectiveDriveMode}`}>
                  {effectiveDriveMode === 'ato' ? 'ATO 自动' : isEmergencyMode ? '紧急' : '人工'}
                </span>
              </div>
              <div className="dcp-handle-status__row">
                <span className="dcp-handle-status__label">牵引</span>
                <span className={`dcp-handle-status__value ${tractionLevelUI > 0 ? 'dcp-handle-status__value--traction' : ''}`}>
                  {tractionLevelUI > 0 ? `${tractionLevelUI} 级` : '—'}
                </span>
              </div>
              <div className="dcp-handle-status__row">
                <span className="dcp-handle-status__label">制动</span>
                <span className={`dcp-handle-status__value ${brakeLevelUI > 0 ? 'dcp-handle-status__value--brake' : ''}`}>
                  {brakeLevelUI > 0 ? `${brakeLevelUI} 级` : '—'}
                </span>
              </div>
              <div className="dcp-handle-status__row">
                <span className="dcp-handle-status__label">惰行</span>
                <span className={`dcp-handle-status__value ${tractionLevelUI === 0 && brakeLevelUI === 0 ? 'dcp-handle-status__value--coast' : ''}`}>
                  {tractionLevelUI === 0 && brakeLevelUI === 0 ? '回零' : '—'}
                </span>
              </div>
            </div>
            {!isEmergencyMode && effectiveDriveMode === 'manual' && normalizedPhase === 'stopped' && (
              <div className="dcp-handle-hint dcp-handle-hint--can-traction">● 停稳后可继续牵引</div>
            )}

            {/* 牵引手柄 0~7：0=回零惰行，1~7=牵引级位 */}
            <div className="dcp-section dcp-section--traction-handle">
              <span className="dcp-section__label">牵引手柄（级位 {tractionLevelUI}）{tractionLevelUI === 0 && !handlesDisabled ? ' · 回零' : ''}</span>
              <div className={`dcp-traction-levels ${handlesDisabled ? 'is-disabled' : ''}`} role="group">
                {[0,1,2,3,4,5,6,7].map((level) => (
                  <button
                    key={level}
                    type="button"
                    className={`dcp-traction-level-btn ${tractionLevelUI === level ? 'is-active' : ''} ${level === 0 ? 'is-zero' : ''}`}
                    disabled={handlesDisabled}
                    onClick={() => {
                      if (level === 0) {
                        setTractionLevelUI(0);
                        setBrakeLevelUI(0);
                        if (onCoast) onCoast();
                      } else {
                        setTractionLevelUI(level);
                        setBrakeLevelUI(0);
                        if (onTractionLevel) {
                          const lp = (level / 7) * 100;
                          onTractionLevel(level, lp);
                        }
                      }
                    }}>
                    {level === 0 ? '0' : level}
                  </button>
                ))}
              </div>
            </div>

            {/* 制动手柄 0~7：0=回零惰行，1~7=制动级位 */}
            <div className="dcp-section dcp-section--brake-handle">
              <span className="dcp-section__label">制动手柄（级位 {brakeLevelUI}）{brakeLevelUI === 0 && !handlesDisabled ? ' · 回零' : ''}</span>
              <div className={`dcp-brake-levels ${handlesDisabled ? 'is-disabled' : ''}`} role="group">
                {[0,1,2,3,4,5,6,7].map((level) => (
                  <button
                    key={level}
                    type="button"
                    className={`dcp-brake-level-btn ${brakeLevelUI === level ? 'is-active' : ''} ${level === 0 ? 'is-zero' : ''}`}
                    disabled={handlesDisabled}
                    onClick={() => {
                      if (level === 0) {
                        setBrakeLevelUI(0);
                        setTractionLevelUI(0);
                        if (onCoast) onCoast();
                      } else {
                        setBrakeLevelUI(level);
                        setTractionLevelUI(0);
                        if (onBrakeLevel) {
                          const targetDecel = (level / 7) * 1.2;
                          const lp = (level / 7) * 100;
                          onBrakeLevel(level, targetDecel, lp);
                        }
                      }
                    }}>
                    {level === 0 ? '0' : level}
                  </button>
                ))}
              </div>
            </div>

            <div className="dcp-bottom-row">
              <div className="dcp-section dcp-section--emergency">
                <button
                  type="button"
                  className={`dcp-emergency-btn ${emergencyActive || externalDriveMode === 'emergency' ? 'is-triggered' : ''}`}
                  disabled={controlLocked}
                  onClick={() => {
                    setEmergencyActive(true);
                    if (onEmergencyBrake) onEmergencyBrake();
                  }}>
                  ⚠ {emergencyActive || externalDriveMode === 'emergency' ? '已施加' : '紧急制动'}
                </button>
              </div>

              {/* Bug#8: EB latch banner + reset */}
              {isEmergencyMode && (
                <div className="dcp-section dcp-section--eb-banner" role="alert">
                  <strong>紧急制动已激活</strong>
                  {canResetEmergency && onResetEmergency ? (
                    <button
                      type="button"
                      className="dcp-reset-emergency-btn"
                      disabled={modeSwitching}
                      onClick={() => {
                        if (modeSwitching) return;
                        beginModeSwitchCooldown('manual');
                        if (onResetEmergency) onResetEmergency();
                      }}>
                      {modeSwitching ? '切换中…' : '复位紧急制动'}
                    </button>
                  ) : (
                    <span>等待停稳后可复位</span>
                  )}
                </div>
              )}

              {/* ATO 模式：申请人工接管（ATO → MANUAL）*/}
              {!isEmergencyMode && effectiveDriveMode === 'ato' && (
                <div className="dcp-section dcp-section--manual-req">
                  <button
                    type="button"
                    className={`dcp-manual-req-btn ${manualRequestState === 'pending' ? 'is-pending' : ''}`}
                    disabled={controlLocked || modeSwitching || manualRequestState === 'pending'}
                    onClick={() => {
                      if (modeSwitching) return;
                      setManualRequestState('pending');
                      beginModeSwitchCooldown('manual');
                      if (onRequestManual) onRequestManual();
                    }}>
                    {modeSwitching
                      ? '切换中…'
                      : manualRequestState === 'pending'
                        ? '等待批准…'
                        : '申请人工接管'}
                  </button>
                </div>
              )}

              {/* MANUAL 模式：恢复 ATO（MANUAL → ATO，调用后端 resume_ato）*/}
              {!isEmergencyMode && effectiveDriveMode === 'manual' && onRequestAto && (
                <div className="dcp-section dcp-section--ato-resume">
                  <button
                    type="button"
                    className="dcp-ato-resume-btn"
                    disabled={controlLocked || modeSwitching}
                    onClick={() => {
                      if (modeSwitching) return;
                      beginModeSwitchCooldown('ato');
                      if (onRequestAto) onRequestAto();
                    }}>
                    {modeSwitching ? '切换中…' : '恢复 ATO'}
                  </button>
                </div>
              )}

              {/* EMERGENCY 模式：EB 停稳后复位到人工（EMERGENCY → MANUAL，调用后端 reset_emergency）*/}
              {isEmergencyMode && canResetEmergency && onResetEmergency && (
                <div className="dcp-section dcp-section--reset-emergency">
                  <button
                    type="button"
                    className="dcp-reset-emergency-btn"
                    disabled={controlLocked || modeSwitching}
                    onClick={() => {
                      if (modeSwitching) return;
                      beginModeSwitchCooldown('manual');
                      if (onResetEmergency) onResetEmergency();
                    }}>
                    {modeSwitching ? '切换中…' : '复位到人工'}
                  </button>
                </div>
              )}
            </div>
            {modeSwitchHint && (
              <div className="dcp-handle-hint" role="status">{modeSwitchHint}</div>
            )}
          </div>

        </aside>

      </div>
    </section>
  );
}

export default DriverCabView;
