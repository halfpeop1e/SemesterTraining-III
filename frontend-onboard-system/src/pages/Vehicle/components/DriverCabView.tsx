import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import type { StopResult, TrainState } from '../../../types/vehicle';
import { STATIONS } from '../data/lineMap';
import './DriverCabView.css';

export interface DriverCabViewProps {
  status: 'idle' | 'loading' | 'playing' | 'finished' | 'error';
  currentState: TrainState | null;
  startPosition: number;
  targetStopPosition: number;
  speedLimit: number;
  stopResult: StopResult | null;
  safetyEventCount?: number;
  isPaused?: boolean;
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

function getOperatingMode(status: DriverCabViewProps['status'], isPaused: boolean): OperatingMode {
  if (status === 'idle') {
    return { label: '待机', note: '等待仿真任务', tone: 'idle' };
  }
  if (status === 'loading') {
    return { label: '系统准备', note: '后端正在计算仿真结果', tone: 'ready' };
  }
  if (status === 'playing' && isPaused) {
    return { label: 'ATO 暂停', note: '播放暂停，当前帧保持', tone: 'paused' };
  }
  if (status === 'playing') {
    return { label: 'ATO 自动驾驶', note: '按后端 SIM 状态播放', tone: 'active' };
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
) {
  if (distanceToTarget > 340 || distanceToTarget < -80) {
    return;
  }

  const project = buildProjection(width, height);
  const stationFront = clamp(distanceToTarget - 34, 4, 320);
  const stationBack = clamp(distanceToTarget + 130, stationFront + 28, 420);
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
  const distanceToTarget = props.targetStopPosition - currentPosition;
  const phase = normalizePhase(props.currentState?.phase);
  const mode = getOperatingMode(props.status, Boolean(props.isPaused));
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
  ctx.font = '800 22px Consolas, "SFMono-Regular", monospace';
  ctx.textAlign = 'left';
  ctx.fillText(`${speedKmh.toFixed(0)} km/h`, width - metricsWidth, height - 76);
  ctx.fillStyle = '#a8bac1';
  ctx.font = '12px "Microsoft YaHei", sans-serif';
  ctx.fillText(`限速 ${limitKmh.toFixed(0)} km/h · ${props.speedLimit.toFixed(2)} m/s`, width - metricsWidth, height - 52);
  ctx.fillText(`目标距离 ${formatDistance(distanceToTarget)} m`, width - metricsWidth, height - 30);

  const chipY = height - 40;
  let chipX = 18;
  [
    inbound ? '入站 < 200m' : null,
    braking ? '制动提示' : null,
    props.isPaused ? '播放暂停' : null,
  ].forEach((chip) => {
    if (!chip) {
      return;
    }
    const chipWidth = chip.length * 13 + 22;
    ctx.fillStyle = chip === '制动提示' ? 'rgba(185, 28, 28, 0.82)' : 'rgba(180, 83, 9, 0.82)';
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
  const distanceToTarget = props.targetStopPosition - currentPosition;

  ctx.clearRect(0, 0, width, height);
  drawTunnel(ctx, width, height, now);
  drawTunnelLights(ctx, width, height, currentPosition, velocity);
  drawTrackSurface(ctx, width, height);
  drawSleepers(ctx, width, height, currentPosition);
  drawStationAndStopTarget(ctx, width, height, distanceToTarget, props.status, props.stopResult);
  drawRails(ctx, width, height);

  const vignette = ctx.createRadialGradient(width * 0.5, height * 0.5, height * 0.24, width * 0.5, height * 0.5, width * 0.64);
  vignette.addColorStop(0, 'rgba(0, 0, 0, 0)');
  vignette.addColorStop(1, 'rgba(0, 0, 0, 0.46)');
  ctx.fillStyle = vignette;
  ctx.fillRect(0, 0, width, height);

  ctx.strokeStyle = 'rgba(206, 218, 216, 0.16)';
  ctx.lineWidth = 2;
  ctx.strokeRect(10, 10, width - 20, height - 20);
  drawStationHud(ctx, width, height, currentPosition);
  drawCanvasHud(ctx, width, height, props);
}

type DriveMode = 'ato' | 'manual';

function DriverCabView({
  status,
  currentState,
  startPosition,
  targetStopPosition,
  speedLimit,
  stopResult,
  safetyEventCount = 0,
  isPaused = false,
}: DriverCabViewProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  // 驾驶员控制面板 UI 状态（纯前端，不接后端）
  const [driveMode, setDriveMode] = useState<DriveMode>('ato');
  const [brakeLevelUI, setBrakeLevelUI] = useState(0);
  const [emergencyActive, setEmergencyActive] = useState(false);
  const [manualRequestState, setManualRequestState] = useState<'idle' | 'pending'>('idle');
  const propsRef = useRef<DriverCabViewProps>({
    status,
    currentState,
    startPosition,
    targetStopPosition,
    speedLimit,
    stopResult,
    safetyEventCount,
    isPaused,
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
  const distanceToTarget = targetStopPosition - currentPosition;
  const normalizedPhase = normalizePhase(currentState?.phase);
  const operatingMode = getOperatingMode(status, isPaused);
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
  const activeLeverLevel = leverMode === 'traction' ? tractionLevel : leverMode === 'brake' ? brakeLevel : 0.08;
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
    '--lever-level': `${activeLeverLevel * 100}%`,
  } as CSSProperties;

  const accelStyle = {
    '--accel-level': `${accelMagnitude * 50}%`,
  } as CSSProperties;

  const distanceTrend = useMemo(() => {
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
  }, [distanceToTarget]);

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

  return (
    <section
      className={`driver-cab-view driver-cab-view--${status} ${isPaused ? 'is-paused' : ''}`}
      aria-label="车载驾驶台运行视景"
    >
      <div className="driver-cab-view__layout">

        {/* ── 左列：Canvas + 底部仪表条 ── */}
        <div className="driver-cab-view__left-col">

          <div className="driver-cab-view__viewport">
            <canvas ref={canvasRef} className="driver-cab-view__canvas" aria-label="第一人称前方轨道仿真视景" />
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
              <div className="cab-instrument__label">目标距离</div>
              <div className={distanceToTarget < 0 ? 'cab-distance is-passed' : 'cab-distance'}>
                <span>{formatDistance(distanceToTarget)}</span>
                <small>m</small>
              </div>
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
              <div className="cab-instrument__label">牵 / 制趋势</div>
              <div className="cab-lever">
                <span className="cab-lever__slot" />
                <span className="cab-lever__fill" />
                <span className="cab-lever__handle" />
              </div>
              <div className="cab-instrument__subline">
                {leverMode === 'traction' ? '牵引' : leverMode === 'brake' ? '制动' : '惰行'}
              </div>
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
          </div>

          {/* 驾驶员控制面板 */}
          <div className="driver-control-panel" aria-label="驾驶员控制面板">
            <div className="dcp-section dcp-section--mode">
              <span className="dcp-section__label">驾驶模式</span>
              <div className="dcp-mode-buttons" role="group">
                <button type="button" className={`dcp-mode-btn dcp-mode-btn--ato ${driveMode === 'ato' ? 'is-active' : ''}`} onClick={() => setDriveMode('ato')}>
                  <span className="dcp-mode-btn__dot" />ATO 自动
                </button>
                <button type="button" className={`dcp-mode-btn dcp-mode-btn--manual ${driveMode === 'manual' ? 'is-active' : ''}`} onClick={() => { setDriveMode('manual'); setManualRequestState('idle'); }}>
                  <span className="dcp-mode-btn__dot" />人工驾驶
                </button>
              </div>
            </div>

            <div className="dcp-section dcp-section--brake-handle">
              <span className="dcp-section__label">制动手柄（级位 {brakeLevelUI}）</span>
              <div className={`dcp-brake-levels ${driveMode !== 'manual' ? 'is-disabled' : ''}`} role="group">
                {[0,1,2,3,4,5,6,7].map((level) => (
                  <button key={level} type="button" className={`dcp-brake-level-btn ${brakeLevelUI === level ? 'is-active' : ''}`} disabled={driveMode !== 'manual'} onClick={() => setBrakeLevelUI(level)}>{level}</button>
                ))}
              </div>
            </div>

            <div className="dcp-bottom-row">
              <div className="dcp-section dcp-section--emergency">
                <button type="button" className={`dcp-emergency-btn ${emergencyActive ? 'is-triggered' : ''}`} onClick={() => setEmergencyActive((v) => !v)}>
                  ⚠ {emergencyActive ? '已施加' : '紧急制动'}
                </button>
              </div>
              <div className="dcp-section dcp-section--manual-req">
                <button type="button" className={`dcp-manual-req-btn ${manualRequestState === 'pending' ? 'is-pending' : ''}`} disabled={driveMode !== 'ato'} onClick={() => setManualRequestState(manualRequestState === 'idle' ? 'pending' : 'idle')}>
                  {manualRequestState === 'pending' ? '等待批准…' : '申请人工接管'}
                </button>
              </div>
            </div>
          </div>

        </aside>

      </div>
    </section>
  );
}

export default DriverCabView;
