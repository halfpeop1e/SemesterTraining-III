// 郭逸晨车载模块（成员三）—— Vehicle 页面
//
// 本轮新增：驾驶员控制回调接线（手动制动、EB、ATO→MANUAL 申请），
// SafetyEvent 展示区域（放在现有 summary 下方，不新增大块布局）。
// 多站连续仿真：toStationId > fromStationId+1 时后端自动多站，前端照常播放。

import { useCallback, useEffect, useState } from 'react';
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

  // 起止站选择，默认 1→2
  const [fromStationId, setFromStationId] = useState(1);
  const [toStationId, setToStationId] = useState(2);

  // 驾驶模式（由后端 control 接口返回的 currentMode 驱动，初始为 ATO）
  const [drivingMode, setDrivingMode] = useState<DrivingMode>('ato');

  // 累积 safetyEvents（续算时可能追加新事件）
  const [allSafetyEvents, setAllSafetyEvents] = useState<SafetyEvent[]>([]);

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
        if (prev >= totalFrames - 1) {
          window.clearInterval(timerId);
          setStatus('finished');
          return prev;
        }
        const next = prev + 1;
        if (next >= totalFrames - 1) {
          window.clearInterval(timerId);
          setStatus('finished');
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
      const simulationResult = await runVehicleSimulation({ fromStationId, toStationId });
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
   * 前后端架构保持"后端计算，前端播放"，控制按钮真正影响仿真。
   */
  const handleControl = useCallback(async (
    command: string,
    targetDecel: number,
    mode: DrivingMode,
  ) => {
    if (!result || !currentStateRef.current) return;
    const currentFrame = currentStateRef.current;
    const curIdx = currentFrameIndexRef.current;
    const curFromId = fromStationIdRef.current;
    const curToId = toStationIdRef.current;

    try {
      const controlResult = await callVehicleControl({
        fromStationId: curFromId,
        toStationId: curToId,
        currentState: currentFrame,
        currentMode: mode,
        controlCommand: { command, targetDecel },
      });

      // 用新 states 替换当前帧之后的旧 states
      setResult((prev) => {
        if (!prev) return prev;
        const before = prev.states.slice(0, curIdx + 1);
        const newStates = [...before, ...controlResult.states];
        return {
          ...prev,
          states: newStates,
          stopResult: controlResult.stopResult,
          safetyEvents: [...(prev.safetyEvents ?? []), ...(controlResult.safetyEvents ?? [])],
          stationStops: prev.stationStops,
        };
      });

      // 更新驾驶模式
      if (controlResult.summary.currentMode) {
        setDrivingMode(controlResult.summary.currentMode);
      }
      // 追加 safetyEvents
      if (controlResult.safetyEvents && controlResult.safetyEvents.length > 0) {
        setAllSafetyEvents((prev) => [...prev, ...controlResult.safetyEvents]);
      }
    } catch (err) {
      // 控制续算失败不中断播放，只在 console 记录
      console.warn('控制续算失败:', err);
    }
  }, [result]); // eslint-disable-line react-hooks/exhaustive-deps

  // 使用 ref 避免 handleControl 闭包陈旧问题
  const currentStateRef = { current: null as TrainState | null };
  const currentFrameIndexRef = { current: 0 };
  const fromStationIdRef = { current: fromStationId };
  const toStationIdRef = { current: toStationId };

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

  const currentState: TrainState | null =
    result && result.states.length > 0 ? result.states[frameIndex] : null;

  // 同步 ref（用于 handleControl 闭包）
  currentStateRef.current = currentState;
  currentFrameIndexRef.current = frameIndex;
  fromStationIdRef.current = fromStationId;
  toStationIdRef.current = toStationId;

  // DriverCabView 仍使用相对 targetStopPosition（0 → runDistanceM）
  const targetStopPosition = result?.stopResult?.targetStopPosition ?? 1200;
  const speedLimitValue = result?.summary?.speedLimit ?? DEMO_SPEED_LIMIT_FALLBACK_MS;

  // 真实线路绝对起点里程，供 LineRunView
  const lineStartPosition = result?.summary?.lineStartPosition ?? 0;

  const fromStationName = result?.summary?.fromStationName ?? null;
  const toStationName = result?.summary?.toStationName ?? null;

  const isLoading = status === 'loading';
  const isPlaying = status === 'playing';
  const canReset = result !== null || status === 'error';

  const toStationOptions = STATIONS.filter((s) => s.stationId > fromStationId);

  // 驾驶员控制回调
  const handleModeRequest = useCallback(() => {
    // ATO → MANUAL：第一版简化为允许（真实系统需要地面授权，此处标注为仿真原型假设）
    setDrivingMode('manual');
    handleControl('coast', 0, 'manual');
  }, [handleControl]);

  const handleBrakeLevel = useCallback((level: number, targetDecel: number) => {
    if (level === 0) return; // 0 级 = 无制动，不触发续算
    handleControl('brake', targetDecel, 'manual');
  }, [handleControl]);

  const handleEmergencyBrake = useCallback(() => {
    const mode = drivingMode;
    setDrivingMode('emergency');
    handleControl('emergency_brake', 0, mode);
  }, [drivingMode, handleControl]);

  // 多站信息展示
  const totalStations = result?.summary?.totalStations;
  const completedStops = result?.summary?.completedStops;
  const stationStops = result?.stationStops;

  return (
    <div className="vehicle-page">

      {/* 顶部控制栏 */}
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

          {/* 区间/多站信息 */}
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
              {isPaused ? '已暂停' : '播放中'} · {frameIndex + 1}/{result?.states.length} · t={currentState.time.toFixed(1)}s
              {currentState.stationName ? ` · ${currentState.stationName}` : ''}
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

      {/* 主内容区：左右分屏（布局不变） */}
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
          externalMode={drivingMode}
          onModeRequest={handleModeRequest}
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
        />
      </div>

      {/* SafetyEvent 列表（放在主内容区下方，现有 summary 区域风格，不新增大块结构）*/}
      {allSafetyEvents.length > 0 && (
        <div className="vehicle-safety-events" aria-label="安全事件列表">
          <h3 className="vehicle-safety-events__title">⚠ 安全事件 ({allSafetyEvents.length})</h3>
          <table className="vehicle-safety-table">
            <thead>
              <tr>
                <th>原因</th>
                <th>时刻 (s)</th>
                <th>位置 (m)</th>
                <th>速度 (m/s)</th>
                <th>动作</th>
              </tr>
            </thead>
            <tbody>
              {allSafetyEvents.map((ev, i) => (
                <tr key={i}>
                  <td>{ev.reason}</td>
                  <td>{ev.time.toFixed(1)}</td>
                  <td>{ev.position.toFixed(1)}</td>
                  <td>{ev.velocity.toFixed(2)}</td>
                  <td>{ev.action}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* 多站停车记录（仅多站时显示，放在 safetyEvents 下方）*/}
      {stationStops && stationStops.length > 0 && status === 'finished' && (
        <div className="vehicle-station-stops" aria-label="各站停车记录">
          <h3 className="vehicle-station-stops__title">各站停车记录</h3>
          <table className="vehicle-safety-table">
            <thead>
              <tr>
                <th>站点</th>
                <th>到站时刻 (s)</th>
                <th>驻留 (s)</th>
                <th>停车误差 (m)</th>
                <th>窗内</th>
              </tr>
            </thead>
            <tbody>
              {stationStops.map((stop, i) => (
                <tr key={i}>
                  <td>{stop.stationName}</td>
                  <td>{stop.arrivalTime.toFixed(1)}</td>
                  <td>{stop.dwellTime.toFixed(0)}</td>
                  <td>{stop.stopError.toFixed(2)}</td>
                  <td>{stop.inWindow ? '✓' : '✗'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

    </div>
  );
}

export default Vehicle;
