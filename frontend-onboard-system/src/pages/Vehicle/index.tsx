// 郭逸晨车载模块（成员三）—— Vehicle 页面
// 本轮修正：
// 1. 使用真正的 useRef 保存 currentState/frameIndex/fromId/toId
// 2. 不在 render 期间 setState
// 3. ATO→MANUAL 切换不自动续算轨迹
// 4. SafetyEvent 在现有 summary 区域内紧凑展示，不新增大块表格撑坏布局

import { useCallback, useEffect, useRef, useState } from 'react';
import { callVehicleControl, runVehicleSimulation } from '../../api/vehicle';
import type { DrivingMode, SafetyEvent, SimulationResult, TrainState } from '../../types/vehicle';
import { STATIONS } from './data/lineMap';
import DriverCabView from './components/DriverCabView';
import LineRunView from './components/LineRunView';
import './vehicle.css';

const DEMO_SPEED_LIMIT_FALLBACK_MS = 20;
const SPEED_MULTIPLIER_OPTIONS = [0.5, 1, 2, 4, 8] as const;
type SpeedMultiplier = (typeof SPEED_MULTIPLIER_OPTIONS)[number];

type PageStatus = 'idle' | 'loading' | 'playing' | 'finished' | 'error';

function Vehicle() {
  const [status, setStatus] = useState<PageStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<SimulationResult | null>(null);
  const [frameIndex, setFrameIndex] = useState(0);
  const [isPaused, setIsPaused] = useState(false);
  const [speedMultiplier, setSpeedMultiplier] = useState<SpeedMultiplier>(1);
  const [fromStationId, setFromStationId] = useState(1);
  const [toStationId, setToStationId] = useState(2);
  const [drivingMode, setDrivingMode] = useState<DrivingMode>('ato');
  // 累积 SafetyEvents（仿真结果自带 + 续算追加）
  const [allSafetyEvents, setAllSafetyEvents] = useState<SafetyEvent[]>([]);

  // ---- 真正的 useRef，不用普通对象伪装 ----
  const currentStateRef = useRef<TrainState | null>(null);
  const frameIndexRef = useRef(0);
  const fromStationIdRef = useRef(fromStationId);
  const toStationIdRef = useRef(toStationId);
  const resultRef = useRef<SimulationResult | null>(null);
  const drivingModeRef = useRef<DrivingMode>('ato');

  // 同步 ref 与 state（在 render 体内赋值 ref.current 是合法的，不是 setState）
  const currentState: TrainState | null =
    result && result.states.length > 0 ? result.states[frameIndex] : null;
  currentStateRef.current = currentState;
  frameIndexRef.current = frameIndex;
  fromStationIdRef.current = fromStationId;
  toStationIdRef.current = toStationId;
  resultRef.current = result;
  drivingModeRef.current = drivingMode;

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

  /**
   * 驾驶员控制续算：取当前帧，发给后端续算，用新 states 替换当前帧之后的旧 states。
   * 使用 useRef 读取最新的 currentState/frameIndex，避免闭包陈旧。
   */
  const handleControl = useCallback(async (
    command: string,
    targetDecel: number,
    mode: DrivingMode,
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
        controlCommand: { command, targetDecel },
        totalTargetPosition: totalTarget,
      });

      // 用新 states 替换当前帧之后的旧 states
      setResult((prev) => {
        if (!prev) return prev;
        const before = prev.states.slice(0, fi + 1);
        return {
          ...prev,
          states: [...before, ...controlResult.states],
          stopResult: controlResult.stopResult,
          safetyEvents: [...(prev.safetyEvents ?? []), ...(controlResult.safetyEvents ?? [])],
          // EB 中断多站任务，清空旧 stationStops
          stationStops: controlResult.stationStops,
        };
      });

      // 更新驾驶模式
      if (controlResult.summary.currentMode) {
        setDrivingMode(controlResult.summary.currentMode);
      }

      // 追加 SafetyEvents（前端累积显示）
      if (controlResult.safetyEvents && controlResult.safetyEvents.length > 0) {
        setAllSafetyEvents((prev) => [...prev, ...controlResult.safetyEvents]);
      }

      // 续算后继续播放
      setStatus('playing');
    } catch (err) {
      // 控制续算失败不中断播放，console 记录
      console.warn('控制续算失败:', err);
    }
  }, []); // useRef 读值，闭包不需要依赖

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

  // ---- 驾驶员控制回调 ----
  const handleRequestManual = useCallback(() => {
    // ATO→MANUAL：只切换模式，不续算轨迹（仿真原型简化；真实系统需地面授权）
    setDrivingMode('manual');
  }, []);

  const handleBrakeLevel = useCallback((level: number, targetDecel: number) => {
    if (level === 0) return; // 0 级=无制动，不触发续算
    // 当前模式读 ref，不用闭包捕获 drivingMode state
    handleControl('brake', targetDecel, drivingModeRef.current);
  }, [handleControl]);

  const handleEmergencyBrake = useCallback(() => {
    const prevMode = drivingModeRef.current;
    setDrivingMode('emergency');
    handleControl('emergency_brake', 0, prevMode);
  }, [handleControl]);

  // ---- 派生值 ----
  const targetStopPosition = result?.stopResult?.targetStopPosition ?? 1200;
  const speedLimitValue = result?.summary?.speedLimit ?? DEMO_SPEED_LIMIT_FALLBACK_MS;
  const lineStartPosition = result?.summary?.lineStartPosition ?? 0;
  const fromStationName = result?.summary?.fromStationName ?? null;
  const toStationName = result?.summary?.toStationName ?? null;
  const isLoading = status === 'loading';
  const isPlaying = status === 'playing';
  const canReset = result !== null || status === 'error';
  const toStationOptions = STATIONS.filter((s) => s.stationId > fromStationId);
  const totalStations = result?.summary?.totalStations;
  const completedStops = result?.summary?.completedStops;

  return (
    <div className="vehicle-page">

      <header className="vehicle-page__header">
        <div>
          <p className="vehicle-page__eyebrow">Onboard cab HMI</p>
          <h2>车载驾驶台系统</h2>
        </div>
        <div className="vehicle-page__actions">
          {/* 起止站选择器 */}
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

          {/* 区间 / 多站信息 */}
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

      {/* SafetyEvent 紧凑展示区（在现有 header 下方，不新增大块结构撑坏布局）*/}
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

      {/* 主内容区：左右分屏（布局不变）*/}
      <div className="vehicle-main-area">
        <DriverCabView
          status={status}
          currentState={currentState}
          startPosition={0}
          targetStopPosition={targetStopPosition}
          speedLimit={speedLimitValue}
          stopResult={result?.stopResult ?? null}
          safetyEventCount={allSafetyEvents.length}
          isPaused={isPaused}
          stationStops={result?.stationStops}
          externalDriveMode={drivingMode}
          onRequestManual={handleRequestManual}
          onBrakeLevel={handleBrakeLevel}
          onEmergencyBrake={handleEmergencyBrake}
        />
        <LineRunView
          status={status}
          currentState={currentState}
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
