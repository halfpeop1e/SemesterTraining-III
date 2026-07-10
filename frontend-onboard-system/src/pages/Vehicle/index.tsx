// 郭逸晨车载模块（成员三）—— Vehicle 页面
// 704协议对接：URL参数trainId、704连接状态面板、实时状态展示

import { useCallback, useEffect, useRef, useState } from 'react';
import { callVehicleControl, runVehicleSimulation } from '../../api/vehicle';
import { connectProtocol704, disconnectProtocol704, getProtocol704Status, resetProtocol704, sendTestFrame } from '../../api/protocol704';
import type { DrivingMode, SafetyEvent, SimulationResult, TrainState } from '../../types/vehicle';
import type { Protocol704Status } from '../../types/protocol704';
import { STATIONS } from './data/lineMap';
import DriverCabView from './components/DriverCabView';
import LineRunView from './components/LineRunView';
import './vehicle.css';

const DEMO_SPEED_LIMIT_FALLBACK_MS = 20;
const SPEED_MULTIPLIER_OPTIONS = [0.5, 1, 2, 4, 8] as const;
type SpeedMultiplier = (typeof SPEED_MULTIPLIER_OPTIONS)[number];

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

type PageStatus = 'idle' | 'loading' | 'playing' | 'finished' | 'error';

function getTrainIdFromUrl(): string {
  const params = new URLSearchParams(window.location.search);
  return params.get('trainId') || 'T1';
}

function formatTime(ts?: number): string {
  if (!ts) return '-';
  const d = new Date(ts);
  return d.toLocaleTimeString('zh-CN', { hour12: false });
}

function Vehicle() {
  const [trainId] = useState(getTrainIdFromUrl());
  const [status, setStatus] = useState<PageStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<SimulationResult | null>(null);
  const [frameIndex, setFrameIndex] = useState(0);
  const [isPaused, setIsPaused] = useState(false);
  const [speedMultiplier, setSpeedMultiplier] = useState<SpeedMultiplier>(1);
  const [fromStationId, setFromStationId] = useState(1);
  const [toStationId, setToStationId] = useState(2);
  const [drivingMode, setDrivingMode] = useState<DrivingMode>('ato');
  const [allSafetyEvents, setAllSafetyEvents] = useState<SafetyEvent[]>([]);
  // 受控重置 token：resume_ato / reset_emergency 成功后递增，DriverCabView 监听后清空级位 UI
  const [handleResetToken, setHandleResetToken] = useState(0);
  // 播放视觉插值层：在 frameIndex 之上用 rAF 计算相邻帧间的 displayState（不影响底层 frameIndex/暂停/倍速/续算）
  const [displayState, setDisplayState] = useState<TrainState | null>(null);

  const [p704Status, setP704Status] = useState<Protocol704Status | null>(null);
  const [p704Polling, setP704Polling] = useState(false);
  const [p704Expanded, setP704Expanded] = useState(false);
  const [p704TestFrameLoading, setP704TestFrameLoading] = useState<string | null>(null);

  const currentStateRef = useRef<TrainState | null>(null);
  const frameIndexRef = useRef(0);
  const fromStationIdRef = useRef(fromStationId);
  const toStationIdRef = useRef(toStationId);
  const resultRef = useRef<SimulationResult | null>(null);
  const drivingModeRef = useRef<DrivingMode>('ato');
  const trainIdRef = useRef(trainId);

  const currentState: TrainState | null =
    result && result.states.length > 0 ? result.states[frameIndex] : null;
  currentStateRef.current = currentState;
  // 传给驾驶台/线路图的视觉状态：优先用 rAF 插值后的 displayState，回退到底层帧
  const viewState: TrainState | null = displayState ?? currentState;
  frameIndexRef.current = frameIndex;
  fromStationIdRef.current = fromStationId;
  toStationIdRef.current = toStationId;
  resultRef.current = result;
  drivingModeRef.current = drivingMode;
  trainIdRef.current = trainId;

  useEffect(() => {
    const existingIcon = document.querySelector<HTMLLinkElement>(
      'link[rel="icon"], link[rel="shortcut icon"]',
    );
    if (existingIcon) return;
    const icon = document.createElement('link');
    icon.rel = 'icon';
    icon.href = 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 16 16%22%3E%3Crect width=%2216%22 height=%2216%22 rx=%223%22 fill=%22%230f766e%22/%3E%3Cpath d=%22M4 11h8M5 4h6l1 4H4l1-4Z%22 stroke=%22white%22 stroke-width=%221.4%22 fill=%22none%22 stroke-linecap=%22round%22 stroke-linejoin=%22round%22/%3E%3C/svg%3E';
    document.head.appendChild(icon);
  }, []);

  useEffect(() => {
    if (status !== 'playing' || isPaused || !result || result.states.length === 0) {
      return undefined;
    }
    const dtPerFrame = result.summary.dtPerFrame ?? 0.5;
    const intervalMs = (dtPerFrame * 1000) / speedMultiplier;
    const totalFrames = result.states.length;
    const timerId = window.setInterval(() => {
      setFrameIndex((prev) => {
        const next = prev + 1;
        if (next >= totalFrames - 1) {
          window.clearInterval(timerId);
          setStatus('finished');
          return totalFrames - 1;
        }
        return next;
      });
    }, intervalMs);
    return () => window.clearInterval(timerId);
  }, [isPaused, result, status, speedMultiplier]);

  // 视觉插值层：保留现有 setInterval 推进 frameIndex（不破坏暂停/倍速/控制续算），
  // 在 frameIndex 之上用 requestAnimationFrame 在 states[fi] 与 states[fi+1] 之间线性插值出 displayState。
  // 控制续算仍使用 currentStateRef.current（底层帧），不用插值帧，避免续算错位。
  useEffect(() => {
    if (!result || result.states.length === 0) {
      setDisplayState(null);
      return;
    }
    const states = result.states;
    const fi = frameIndex;
    const frame = states[fi];

    // 非播放 / 暂停 / 末帧：不插值，直接用当前帧
    if (status !== 'playing' || isPaused || fi >= states.length - 1) {
      setDisplayState(frame);
      return;
    }

    const upper = states[fi + 1];
    const lowerTime = frame.time;
    const upperTime = upper.time;
    const span = upperTime - lowerTime;
    const frameStartPerf = performance.now();
    let rafId = 0;

    const tick = () => {
      const elapsed = (performance.now() - frameStartPerf) / 1000;
      let frac = span > 0 ? (elapsed * speedMultiplier) / span : 1;
      frac = clamp(frac, 0, 1);
      setDisplayState({
        time: lowerTime + span * frac,
        position: frame.position + (upper.position - frame.position) * frac,
        velocity: frame.velocity + (upper.velocity - frame.velocity) * frac,
        acceleration: frame.acceleration + (upper.acceleration - frame.acceleration) * frac,
        phase: frame.phase,
        trainId: frame.trainId,
        absolutePosition: frame.absolutePosition !== undefined && upper.absolutePosition !== undefined
          ? frame.absolutePosition + (upper.absolutePosition - frame.absolutePosition) * frac
          : frame.absolutePosition,
      });
      if (frac < 1) {
        rafId = window.requestAnimationFrame(tick);
      }
    };
    rafId = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(rafId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result, frameIndex, isPaused, speedMultiplier, status]);

  useEffect(() => {
    let cancelled = false;
    async function initialLoad() {
      try {
        const s = await getProtocol704Status(trainIdRef.current);
        if (!cancelled) setP704Status(s);
      } catch {
        // ignore
      }
    }
    initialLoad();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!p704Polling) return undefined;
    const timerId = window.setInterval(async () => {
      try {
        const s = await getProtocol704Status(trainIdRef.current);
        setP704Status(s);
      } catch {
        // ignore
      }
    }, 500);
    return () => window.clearInterval(timerId);
  }, [p704Polling]);

  const handleStart = async () => {
    setStatus('loading');
    setErrorMessage(null);
    setResult(null);
    setFrameIndex(0);
    setIsPaused(false);
    setSpeedMultiplier(1);
    setDrivingMode('ato');
    setAllSafetyEvents([]);
    try {
      const simulationResult = await runVehicleSimulation({
        fromStationId,
        toStationId,
      });
      if (!simulationResult.states || simulationResult.states.length === 0) {
        throw new Error('后端返回的仿真结果不包含任何 states 数据');
      }
      setResult(simulationResult);
      setAllSafetyEvents(simulationResult.safetyEvents ?? []);
      setStatus('playing');
      setFrameIndex(0);
    } catch (err) {
      setStatus('error');
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  };

  const handleControl = useCallback(async (
    command: string,
    targetDecel: number,
    mode: DrivingMode,
    levelPercent?: number,
  ) => {
    const cs = currentStateRef.current;
    const fi = frameIndexRef.current;
    const fromId = fromStationIdRef.current;
    const toId = toStationIdRef.current;
    const res = resultRef.current;
    if (!cs || !res) return;

    const totalTarget = res.stopResult?.targetStopPosition ?? 0;

    try {
      const controlResult = await callVehicleControl({
        fromStationId: fromId,
        toStationId: toId,
        currentState: cs,
        currentMode: mode,
        controlCommand: { command, targetDecel, levelPercent },
        totalTargetPosition: totalTarget,
      });

      setResult((prev) => {
        if (!prev) return prev;
        const before = prev.states.slice(0, fi + 1);
        return {
          ...prev,
          states: [...before, ...controlResult.states],
          stopResult: controlResult.stopResult,
          safetyEvents: [...(prev.safetyEvents ?? []), ...(controlResult.safetyEvents ?? [])],
          stationStops: controlResult.stationStops,
          // 仅合并模式相关字段，保留原始线路/限速等字段
          summary: {
            ...prev.summary,
            currentMode: controlResult.summary.currentMode ?? prev.summary.currentMode,
            nextMode: controlResult.summary.nextMode ?? prev.summary.nextMode,
          },
        };
      });

      if (controlResult.summary.currentMode) {
        setDrivingMode(controlResult.summary.currentMode);
      }

      if (controlResult.safetyEvents && controlResult.safetyEvents.length > 0) {
        setAllSafetyEvents((prev) => [...prev, ...controlResult.safetyEvents]);
      }

      setStatus('playing');
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  }, []);

  const handleReset = () => {
    setStatus('idle');
    setErrorMessage(null);
    setResult(null);
    setFrameIndex(0);
    setIsPaused(false);
    setSpeedMultiplier(1);
    setDrivingMode('ato');
    setAllSafetyEvents([]);
  };

  const handleTogglePause = () => {
    if (status === 'playing') setIsPaused((v) => !v);
  };

  const handleRequestManual = useCallback(() => {
    setDrivingMode('manual');
  }, []);

  const handleTractionLevel = useCallback((_level: number, levelPercent: number) => {
    handleControl('traction', 0, drivingModeRef.current, levelPercent);
  }, [handleControl]);

  const handleBrakeLevel = useCallback((_level: number, targetDecel: number, levelPercent: number) => {
    handleControl('brake', targetDecel, drivingModeRef.current, levelPercent);
  }, [handleControl]);

  const handleCoast = useCallback(() => {
    handleControl('coast', 0, drivingModeRef.current, 0);
  }, [handleControl]);

  const handleEmergencyBrake = useCallback(() => {
    const prevMode = drivingModeRef.current;
    setDrivingMode('emergency');
    handleControl('emergency_brake', 0, prevMode, 0);
  }, [handleControl]);

  // 恢复 ATO（MANUAL → ATO）：调用后端 /control resume_ato，成功后切换模式 + 清空级位 UI + 续算播放
  const handleRequestAto = useCallback(async () => {
    const cs = currentStateRef.current;
    const fi = frameIndexRef.current;
    const fromId = fromStationIdRef.current;
    const toId = toStationIdRef.current;
    const res = resultRef.current;
    if (!cs || !res) return;
    const totalTarget = res.stopResult?.targetStopPosition ?? 0;
    try {
      const controlResult = await callVehicleControl({
        fromStationId: fromId,
        toStationId: toId,
        currentState: cs,
        currentMode: 'manual',
        controlCommand: { command: 'resume_ato', targetDecel: 0, levelPercent: 0 },
        totalTargetPosition: totalTarget,
      });
      setResult((prev) => {
        if (!prev) return prev;
        const before = prev.states.slice(0, fi + 1);
        return {
          ...prev,
          states: [...before, ...controlResult.states],
          stopResult: controlResult.stopResult,
          safetyEvents: [...(prev.safetyEvents ?? []), ...(controlResult.safetyEvents ?? [])],
          stationStops: controlResult.stationStops,
          summary: {
            ...prev.summary,
            currentMode: controlResult.summary.currentMode ?? prev.summary.currentMode,
            nextMode: controlResult.summary.nextMode ?? prev.summary.nextMode,
          },
        };
      });
      setDrivingMode('ato');
      setHandleResetToken((t) => t + 1);
      if (controlResult.safetyEvents && controlResult.safetyEvents.length > 0) {
        setAllSafetyEvents((prev) => [...prev, ...controlResult.safetyEvents]);
      }
      setStatus('playing');
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  }, []);

  // EB 复位（EMERGENCY → MANUAL）：调用后端 /control reset_emergency，成功后切换模式 + 清空级位 UI + 续算播放
  const handleResetEmergency = useCallback(async () => {
    const cs = currentStateRef.current;
    const fi = frameIndexRef.current;
    const fromId = fromStationIdRef.current;
    const toId = toStationIdRef.current;
    const res = resultRef.current;
    if (!cs || !res) return;
    const totalTarget = res.stopResult?.targetStopPosition ?? 0;
    try {
      const controlResult = await callVehicleControl({
        fromStationId: fromId,
        toStationId: toId,
        currentState: cs,
        currentMode: 'emergency',
        controlCommand: { command: 'reset_emergency', targetDecel: 0, levelPercent: 0 },
        totalTargetPosition: totalTarget,
      });
      setResult((prev) => {
        if (!prev) return prev;
        const before = prev.states.slice(0, fi + 1);
        return {
          ...prev,
          states: [...before, ...controlResult.states],
          stopResult: controlResult.stopResult,
          safetyEvents: [...(prev.safetyEvents ?? []), ...(controlResult.safetyEvents ?? [])],
          stationStops: controlResult.stationStops,
          summary: {
            ...prev.summary,
            currentMode: controlResult.summary.currentMode ?? prev.summary.currentMode,
            nextMode: controlResult.summary.nextMode ?? prev.summary.nextMode,
          },
        };
      });
      setDrivingMode('manual');
      setHandleResetToken((t) => t + 1);
      if (controlResult.safetyEvents && controlResult.safetyEvents.length > 0) {
        setAllSafetyEvents((prev) => [...prev, ...controlResult.safetyEvents]);
      }
      setStatus('playing');
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  }, []);

  const handleP704Connect = async () => {
    try {
      await connectProtocol704(trainId);
      setP704Polling(true);
      setP704Expanded(true);
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  };

  const handleP704Disconnect = async () => {
    try {
      await disconnectProtocol704(trainId);
      setP704Polling(false);
      const s = await getProtocol704Status(trainId);
      setP704Status(s);
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  };

  const handleP704Reset = async () => {
    try {
      await resetProtocol704(trainId);
      setP704Polling(false);
      const s = await getProtocol704Status(trainId);
      setP704Status(s);
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  };

  const handleTestFrame = async (type: string) => {
    setP704TestFrameLoading(type);
    try {
      const s = await sendTestFrame(trainId, type);
      setP704Status(s);
      setP704Expanded(true);
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setP704TestFrameLoading(null);
    }
  };

  const targetStopPosition = result?.stopResult?.targetStopPosition ?? 1200;
  const speedLimitValue = result?.summary?.speedLimit ?? DEMO_SPEED_LIMIT_FALLBACK_MS;
  const lineStartPosition = result?.summary?.lineStartPosition ?? 0;
  const fromStationName = result?.summary?.fromStationName ?? null;
  const toStationName = result?.summary?.toStationName ?? null;
  const isLoading = status === 'loading';
  const isPlaying = status === 'playing';
  const canReset = result !== null || status === 'error';
  // EB 停稳后可复位到人工：EMERGENCY 模式 + 后端返回 nextMode==='manual'
  const canResetEmergency = drivingMode === 'emergency' && result?.summary?.nextMode === 'manual';
  const toStationOptions = STATIONS.filter((s) => s.stationId > fromStationId);
  const totalStations = result?.summary?.totalStations;
  const completedStops = result?.summary?.completedStops;

  const p704connected = p704Status?.connected ?? false;
  const portStatuses = p704Status?.portStatuses ? Object.values(p704Status.portStatuses) : [];
  const lastMappedCmd = p704Status?.lastMappedCommand;
  const rtState = p704Status?.realtimeVehicleState;

  return (
    <div className="vehicle-page">

      <header className="vehicle-page__header">
        <div>
          <p className="vehicle-page__eyebrow">Onboard cab HMI <span style={{ marginLeft: 8, padding: '2px 8px', background: '#0f766e', color: 'white', borderRadius: 4, fontSize: 12 }}>车组 {trainId}</span></p>
          <h2>车载驾驶台系统</h2>
        </div>
        <div className="vehicle-page__actions">
          <div className="vehicle-station-selector" aria-label="起止站选择">
            <label htmlFor="from-station-select" className="vehicle-station-label">出发站</label>
            <select
              id="from-station-select"
              className="vehicle-station-select"
              value={fromStationId}
              disabled={isLoading || isPlaying}
              onChange={(e) => {
                const newFrom = Number(e.target.value);
                setFromStationId(newFrom);
                if (toStationId <= newFrom) {
                  const nextId = STATIONS.find((s) => s.stationId > newFrom)?.stationId;
                  if (nextId !== undefined) setToStationId(nextId);
                }
              }}
            >
              {STATIONS.filter((s) => s.stationId < STATIONS[STATIONS.length - 1].stationId).map((s) => (
                <option key={s.stationId} value={s.stationId}>
                  {s.displayNameOverride ?? s.displayName}
                </option>
              ))}
            </select>

            <span className="vehicle-station-arrow">→</span>

            <label htmlFor="to-station-select" className="vehicle-station-label">目标站</label>
            <select
              id="to-station-select"
              className="vehicle-station-select"
              value={toStationId}
              disabled={isLoading || isPlaying}
              onChange={(e) => setToStationId(Number(e.target.value))}
            >
              {toStationOptions.map((s) => (
                <option key={s.stationId} value={s.stationId}>
                  {s.displayNameOverride ?? s.displayName}
                </option>
              ))}
            </select>
          </div>

          <button className="vehicle-start-btn" onClick={handleStart}
            disabled={isLoading || isPlaying} type="button">
            {isLoading ? '计算中...' : status === 'finished' || status === 'error' ? '重新仿真' : '开始仿真'}
          </button>
          <button className="vehicle-secondary-btn" onClick={handleTogglePause}
            disabled={!isPlaying} type="button">
            {isPaused ? '继续' : '暂停'}
          </button>
          <button className="vehicle-ghost-btn" onClick={handleReset}
            disabled={!canReset || isLoading} type="button">
            复位
          </button>

          <div className="vehicle-speed-group" aria-label="播放倍速">
            {SPEED_MULTIPLIER_OPTIONS.map((m) => (
              <button key={m} type="button"
                className={`vehicle-speed-btn${speedMultiplier === m ? ' is-active' : ''}`}
                onClick={() => setSpeedMultiplier(m)}
                disabled={!result && status !== 'playing'}
                aria-pressed={speedMultiplier === m}>
                {m}x
              </button>
            ))}
          </div>

          {(fromStationName && toStationName) ? (
            <span className="vehicle-section-label">
              {fromStationName} → {toStationName}
              {totalStations && totalStations > 2 ? ` (${completedStops}/${totalStations - 1}站)` : ''}
            </span>
          ) : (
            <span className="vehicle-section-label vehicle-section-label--pending">
              {(STATIONS.find((s) => s.stationId === fromStationId)?.displayNameOverride
                ?? STATIONS.find((s) => s.stationId === fromStationId)?.displayName) || `站${fromStationId}`}
              {' → '}
              {(STATIONS.find((s) => s.stationId === toStationId)?.displayNameOverride
                ?? STATIONS.find((s) => s.stationId === toStationId)?.displayName) || `站${toStationId}`}
            </span>
          )}

          {isPlaying && currentState && (
            <span className="vehicle-status-text">
              {isPaused ? '已暂停' : '播放中'} · {frameIndex + 1}/{result?.states.length}
              {' · '}t={currentState.time.toFixed(1)}s
              {' · '}模式:{drivingMode.toUpperCase()}
            </span>
          )}
          {status === 'finished' && <span className="vehicle-status-text">仿真完成</span>}
        </div>
      </header>

      {status === 'error' && (
        <div className="vehicle-error">
          <span>仿真失败：{errorMessage}</span>
          <button onClick={handleStart} type="button">重试</button>
        </div>
      )}

      {allSafetyEvents.length > 0 && (
        <div className="vehicle-safety-bar" role="alert" aria-label="安全事件">
          <strong>⚠ 安全事件({allSafetyEvents.length})：</strong>
          {allSafetyEvents.slice(-3).map((ev, i) => (
            <span key={i} className="vehicle-safety-chip">
              {ev.reason} t={ev.time.toFixed(0)}s pos={ev.position.toFixed(0)}m v={ev.velocity.toFixed(1)}m/s [{ev.action}]
            </span>
          ))}
          {allSafetyEvents.length > 3 && <span className="vehicle-safety-chip">…</span>}
        </div>
      )}

      {/* 704 协议状态面板 */}
      <div className="vehicle-704-panel">
        <div className="vehicle-704-panel__header" onClick={() => setP704Expanded(!p704Expanded)} style={{ cursor: 'pointer' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <strong>704/司机台连接状态 · 车组 {trainId}</strong>
            <span className={`vehicle-704-badge ${p704connected ? 'vehicle-704-badge--connected' : (p704Polling ? 'vehicle-704-badge--connecting' : 'vehicle-704-badge--disconnected')}`}>
              {p704connected ? '已连接' : (p704Polling ? '连接中' : '未连接')}
            </span>
            <span style={{ fontSize: 12, color: '#6b7280' }}>目标: {p704Status?.host ?? '192.168.100.123'}:{portStatuses.map(p => p.port).join('/') || '8001/8002/8003'}</span>
            {!p704Expanded && (
              <>
                {lastMappedCmd && (
                  <span style={{ fontSize: 11, color: lastMappedCmd.verified ? '#059669' : '#d97706', fontWeight: 600 }}>
                    最近命令: {lastMappedCmd.command}
                    {lastMappedCmd.levelPercent > 0 ? ` ${lastMappedCmd.levelPercent}%` : ''}
                  </span>
                )}
                {p704Status?.lastFrameLength != null && (
                  <span style={{ fontSize: 11, color: '#6b7280' }}>帧长: {p704Status.lastFrameLength}B</span>
                )}
                {p704Status?.recentLogs && p704Status.recentLogs.length > 0 && (
                  <span style={{ fontSize: 11, color: '#6b7280' }}>日志: {p704Status.recentLogs.length}条</span>
                )}
              </>
            )}
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <button onClick={(e) => { e.stopPropagation(); handleP704Connect(); }} disabled={p704Polling} type="button" className="vehicle-704-btn">连接704</button>
            <button onClick={(e) => { e.stopPropagation(); handleP704Disconnect(); }} disabled={!p704Polling} type="button" className="vehicle-704-btn">断开</button>
            <button onClick={(e) => { e.stopPropagation(); handleP704Reset(); }} type="button" className="vehicle-704-btn">重置</button>
            <span className="vehicle-704-expand-icon">{p704Expanded ? '▲' : '▼'}</span>
          </div>
        </div>

        {p704Expanded && (
          <div className="vehicle-704-panel__body">
            {/* 端口状态 */}
            <div className="vehicle-704-section">
              <div className="vehicle-704-section__title">端口状态</div>
              <div className="vehicle-704-port-grid">
                {portStatuses.map((ps) => (
                  <div key={ps.port} className={`vehicle-704-port-card ${ps.connected ? 'vehicle-704-port-card--connected' : (ps.connecting ? 'vehicle-704-port-card--connecting' : '')}`}>
                    <div className="vehicle-704-port-card__header">
                      <span className="vehicle-704-port-card__port">PLC 端口 {ps.port}</span>
                      <span className={`vehicle-704-port-card__status ${ps.connected ? 'vehicle-704-text-green' : ps.connecting ? 'vehicle-704-text-yellow' : 'vehicle-704-text-red'}`}>
                        {ps.connecting ? '连接中...' : ps.connected ? '✓ 已连接' : '✗ 未连接'}
                      </span>
                    </div>
                    <div className="vehicle-704-port-card__details">
                      <div>最近接收: {formatTime(ps.lastReceiveTime)}</div>
                      <div>帧间隔: {ps.lastFrameIntervalMs ? ps.lastFrameIntervalMs + 'ms' : '-'}</div>
                      <div>帧长度: {ps.lastFrameLength ? ps.lastFrameLength + 'B' : '-'}</div>
                      <div>收帧数: {ps.frameCount ?? 0}</div>
                      <div>接收字节: {ps.bytesReceived != null ? (ps.bytesReceived > 1024 ? (ps.bytesReceived / 1024).toFixed(1) + 'KB' : ps.bytesReceived + 'B') : '0B'}</div>
                    </div>
                    {ps.lastError && (
                      <div className="vehicle-704-port-card__error">{ps.lastError}</div>
                    )}
                  </div>
                ))}
              </div>
            </div>

            {/* 测试帧按钮 */}
            <div className="vehicle-704-section vehicle-704-test-section">
              <div className="vehicle-704-section__title">
                测试帧
                <span className="vehicle-704-test-warning">（不代表真实704已连接）</span>
              </div>
              <div className="vehicle-704-test-buttons">
                <button onClick={() => handleTestFrame('coast')} disabled={p704TestFrameLoading !== null} type="button" className="vehicle-704-test-btn">
                  {p704TestFrameLoading === 'coast' ? '发送中...' : '测试惰行'}
                </button>
                <button onClick={() => handleTestFrame('traction')} disabled={p704TestFrameLoading !== null} type="button" className="vehicle-704-test-btn vehicle-704-test-btn--traction">
                  {p704TestFrameLoading === 'traction' ? '发送中...' : '测试牵引'}
                </button>
                <button onClick={() => handleTestFrame('brake')} disabled={p704TestFrameLoading !== null} type="button" className="vehicle-704-test-btn vehicle-704-test-btn--brake">
                  {p704TestFrameLoading === 'brake' ? '发送中...' : '测试制动'}
                </button>
                <button onClick={() => handleTestFrame('emergency_brake')} disabled={p704TestFrameLoading !== null} type="button" className="vehicle-704-test-btn vehicle-704-test-btn--eb">
                  {p704TestFrameLoading === 'emergency_brake' ? '发送中...' : '测试EB'}
                </button>
              </div>
            </div>

            {/* 解析字段 & 映射命令 */}
            {(p704Status?.lastParsedFrame || lastMappedCmd) && (
              <div className="vehicle-704-section">
                <div className="vehicle-704-section__title">解析结果</div>
                <div className="vehicle-704-parse-grid">
                  {p704Status?.lastParsedFrame?.fields && (
                    <>
                      <div className="vehicle-704-parse-item">
                        <span className="vehicle-704-parse-label">帧头</span>
                        <span className={`vehicle-704-parse-value ${p704Status.lastParsedFrame.fields.header_valid ? 'vehicle-704-text-green' : 'vehicle-704-text-red'}`}>
                          {String(p704Status.lastParsedFrame.fields.frame_header ?? '-')}
                          {p704Status.lastParsedFrame.fields.header_valid ? ' ✓' : ' ✗'}
                        </span>
                      </div>
                      <div className="vehicle-704-parse-item">
                        <span className="vehicle-704-parse-label">totalLen / dataLen</span>
                        <span className="vehicle-704-parse-value">{String(p704Status.lastParsedFrame.fields.total_len_field ?? '-')} / {String(p704Status.lastParsedFrame.fields.data_len_field ?? '-')}</span>
                      </div>
                      <div className="vehicle-704-parse-item">
                        <span className="vehicle-704-parse-label">主手柄</span>
                        <span className="vehicle-704-parse-value">{String(p704Status.lastParsedFrame.fields.master_handle ?? '-')}</span>
                      </div>
                      <div className="vehicle-704-parse-item">
                        <span className="vehicle-704-parse-label">方向手柄</span>
                        <span className="vehicle-704-parse-value">{String(p704Status.lastParsedFrame.fields.direction_desc ?? '-')}</span>
                      </div>
                      <div className="vehicle-704-parse-item">
                        <span className="vehicle-704-parse-label">牵引级位</span>
                        <span className="vehicle-704-parse-value">{String(p704Status.lastParsedFrame.fields.traction_level_percent_raw ?? '-')}</span>
                      </div>
                      <div className="vehicle-704-parse-item">
                        <span className="vehicle-704-parse-label">制动级位</span>
                        <span className="vehicle-704-parse-value">{String(p704Status.lastParsedFrame.fields.brake_level_percent_raw ?? '-')}</span>
                      </div>
                      <div className="vehicle-704-parse-item">
                        <span className="vehicle-704-parse-label">EB按钮</span>
                        <span className={`vehicle-704-parse-value ${p704Status.lastParsedFrame.fields.eb_button_locked ? 'vehicle-704-text-red' : ''}`}>
                          {p704Status.lastParsedFrame.fields.eb_button_locked ? '⚠ 已触发' : '正常'}
                        </span>
                      </div>
                    </>
                  )}
                  {lastMappedCmd && (
                    <div className="vehicle-704-parse-item vehicle-704-parse-item--full">
                      <span className="vehicle-704-parse-label">映射命令</span>
                      <span className={`vehicle-704-parse-value ${lastMappedCmd.verified ? 'vehicle-704-text-green' : 'vehicle-704-text-yellow'}`}>
                        {lastMappedCmd.command}
                        {lastMappedCmd.levelPercent > 0 ? ` (级位:${lastMappedCmd.levelPercent}%)` : ''}
                        {lastMappedCmd.targetDecel > 0 ? ` (减速度:${lastMappedCmd.targetDecel.toFixed(2)}m/s²)` : ''}
                        {' '}
                        {lastMappedCmd.verified ? '(已验证)' : '(未现场验证)'}
                      </span>
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* 实时状态 */}
            {rtState && (
              <div className="vehicle-704-section">
                <div className="vehicle-704-section__title">实时车辆状态 {rtState.note ? `(${rtState.note})` : ''}</div>
                <div className="vehicle-704-parse-grid">
                  <div className="vehicle-704-parse-item">
                    <span className="vehicle-704-parse-label">速度</span>
                    <span className="vehicle-704-parse-value">{rtState.velocityMs.toFixed(2)} m/s</span>
                  </div>
                  <div className="vehicle-704-parse-item">
                    <span className="vehicle-704-parse-label">加速度</span>
                    <span className="vehicle-704-parse-value">{rtState.accelerationMs2.toFixed(2)} m/s²</span>
                  </div>
                  <div className="vehicle-704-parse-item">
                    <span className="vehicle-704-parse-label">位置</span>
                    <span className="vehicle-704-parse-value">{rtState.positionM.toFixed(1)} m</span>
                  </div>
                  <div className="vehicle-704-parse-item">
                    <span className="vehicle-704-parse-label">模式</span>
                    <span className="vehicle-704-parse-value">{rtState.mode}</span>
                  </div>
                </div>
              </div>
            )}

            {/* raw hex */}
            {p704Status?.lastRawHex && (
              <div className="vehicle-704-section">
                <div className="vehicle-704-section__title">最近原始帧 (hex)</div>
                <div className="vehicle-704-raw-hex">{p704Status.lastRawHex}</div>
              </div>
            )}

            {/* 最近日志 */}
            {p704Status?.recentLogs && p704Status.recentLogs.length > 0 && (
              <div className="vehicle-704-section">
                <div className="vehicle-704-section__title">最近日志 (最近{p704Status.recentLogs.length}条)</div>
                <div className="vehicle-704-log-list">
                  {p704Status.recentLogs.slice(-20).reverse().map((log, i) => (
                    <div key={i} className={`vehicle-704-log-item ${log.source === 'TEST_FRAME' ? 'vehicle-704-log-item--test' : ''}`}>
                      <span className="vehicle-704-log-time">{formatTime(log.timestamp)}</span>
                      <span className={`vehicle-704-log-source ${log.source === 'TEST_FRAME' ? 'vehicle-704-text-blue' : 'vehicle-704-text-green'}`}>
                        {log.source === 'TEST_FRAME' ? '测试帧' : '硬件帧'}
                      </span>
                      <span className="vehicle-704-log-port">端口:{log.port || '-'}</span>
                      <span className="vehicle-704-log-len">{log.frameLength}B</span>
                      <span className="vehicle-704-log-cmd">{log.mappedCommand?.command ?? '-'}</span>
                      {log.note && <span className="vehicle-704-log-note">{log.note}</span>}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      <div className="vehicle-main-area">
        <DriverCabView
          status={status}
          currentState={viewState}
          startPosition={0}
          targetStopPosition={targetStopPosition}
          speedLimit={speedLimitValue}
          stopResult={result?.stopResult ?? null}
          safetyEventCount={allSafetyEvents.length}
          isPaused={isPaused}
          stationStops={result?.stationStops}
          externalDriveMode={drivingMode}
          onRequestManual={handleRequestManual}
          onTractionLevel={handleTractionLevel}
          onBrakeLevel={handleBrakeLevel}
          onCoast={handleCoast}
          onEmergencyBrake={handleEmergencyBrake}
          onRequestAto={handleRequestAto}
          onResetEmergency={handleResetEmergency}
          canResetEmergency={canResetEmergency}
          handleResetToken={handleResetToken}
        />
        <LineRunView
          status={status}
          currentState={viewState}
          startPosition={0}
          targetStopPosition={targetStopPosition}
          speedLimit={speedLimitValue}
          stopResult={result?.stopResult ?? null}
          positionOffset={lineStartPosition}
          stationStops={result?.stationStops}
        />
      </div>

    </div>
  );
}

export default Vehicle;
