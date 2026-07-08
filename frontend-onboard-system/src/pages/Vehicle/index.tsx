// 郭逸晨车载模块（成员三）—— Vehicle 页面
//
// 布局：顶部控制栏（固定高度）+ 下方左右分屏（DriverCabView 左 | LineRunView 右）
// 整页锁死在 100vh，不产生滚动。

import { useEffect, useState } from 'react';
import { runVehicleSimulation } from '../../api/vehicle';
import type { SimulationResult, TrainState } from '../../types/vehicle';
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
    try {
      const simulationResult = await runVehicleSimulation();
      if (!simulationResult.states || simulationResult.states.length === 0) {
        throw new Error('后端返回的仿真结果不包含任何 states 数据');
      }
      setResult(simulationResult);
      setStatus('playing');
      setFrameIndex(0);
    } catch (err) {
      setStatus('error');
      setErrorMessage(err instanceof Error ? err.message : String(err));
    }
  };

  const handleReset = () => {
    setStatus('idle');
    setErrorMessage(null);
    setResult(null);
    setFrameIndex(0);
    setIsPaused(false);
    setSpeedMultiplier(1);
  };

  const handleTogglePause = () => {
    if (status === 'playing') setIsPaused((v) => !v);
  };

  const currentState: TrainState | null =
    result && result.states.length > 0 ? result.states[frameIndex] : null;
  const targetStopPosition = result?.stopResult?.targetStopPosition ?? 1200;
  const speedLimitValue = result?.summary?.speedLimit ?? DEMO_SPEED_LIMIT_FALLBACK_MS;
  const isLoading = status === 'loading';
  const isPlaying = status === 'playing';
  const canReset = result !== null || status === 'error';

  return (
    <div className="vehicle-page">

      {/* 顶部控制栏 */}
      <header className="vehicle-page__header">
        <div>
          <p className="vehicle-page__eyebrow">Onboard cab HMI</p>
          <h2>车载驾驶台系统</h2>
        </div>
        <div className="vehicle-page__actions">
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

          {isPlaying && currentState && (
            <span className="vehicle-status-text">
              {isPaused ? '已暂停' : '播放中'} · {frameIndex + 1}/{result?.states.length} · t={currentState.time.toFixed(1)}s
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

      {/* 主内容区：左右分屏 */}
      <div className="vehicle-main-area">
        <DriverCabView
          status={status}
          currentState={currentState}
          startPosition={0}
          targetStopPosition={targetStopPosition}
          speedLimit={speedLimitValue}
          stopResult={result?.stopResult ?? null}
          safetyEventCount={result?.safetyEvents.length ?? 0}
          isPaused={isPaused}
        />
        <LineRunView
          status={status}
          currentState={currentState}
          startPosition={0}
          targetStopPosition={targetStopPosition}
          speedLimit={speedLimitValue}
          stopResult={result?.stopResult ?? null}
        />
      </div>

    </div>
  );
}

export default Vehicle;
