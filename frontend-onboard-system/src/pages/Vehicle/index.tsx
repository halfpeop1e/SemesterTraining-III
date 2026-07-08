// 郭逸晨车载模块（成员三）—— Vehicle 页面
//
// 本页负责真实调用后端 POST /api/vehicle/simulation/run，拿到完整仿真结果后按时间顺序
// 播放 states。DriverCabView 只接收当前播放状态做展示，不发请求、不生成仿真数据。
//
// 倍速控制：speedMultiplier 决定播放 interval 快慢。
// intervalMs = (result.summary.dtPerFrame ?? 0.5) * 1000 / speedMultiplier
// 只改前端播放节奏，不改后端 dt 或任何物理计算。

import { useEffect, useState } from 'react';
import { runVehicleSimulation } from '../../api/vehicle';
import type { SimulationResult, TrainState } from '../../types/vehicle';
import DriverCabView from './components/DriverCabView';
import LineRunView from './components/LineRunView';
import './vehicle.css';

const DEMO_SPEED_LIMIT_FALLBACK_MS = 20;
const SPEED_MULTIPLIER_OPTIONS = [0.5, 1, 2, 4, 8] as const;
type SpeedMultiplier = (typeof SPEED_MULTIPLIER_OPTIONS)[number];

const STOP_WINDOW_STATE_LABELS: Record<string, string> = {
  IN_WINDOW: '窗内',
  OVERSHOOT: '冲标',
  UNDERSHOOT: '欠标',
  NOT_ACCURATE: '未停准',
};

function describeStopWindowState(stopWindowState: string | undefined): string {
  if (!stopWindowState) {
    return '-';
  }
  const key = stopWindowState.toUpperCase();
  return STOP_WINDOW_STATE_LABELS[key] ?? stopWindowState;
}

type PageStatus = 'idle' | 'loading' | 'playing' | 'finished' | 'error';

function Vehicle() {
  const [status, setStatus] = useState<PageStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<SimulationResult | null>(null);
  const [frameIndex, setFrameIndex] = useState(0);
  const [isPaused, setIsPaused] = useState(false);
  const [isResultExpanded, setIsResultExpanded] = useState(false);
  const [speedMultiplier, setSpeedMultiplier] = useState<SpeedMultiplier>(1);

  useEffect(() => {
    const existingIcon = document.querySelector<HTMLLinkElement>(
      'link[rel="icon"], link[rel="shortcut icon"]',
    );
    if (existingIcon) {
      return;
    }

    const icon = document.createElement('link');
    icon.rel = 'icon';
    icon.href = 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 16 16%22%3E%3Crect width=%2216%22 height=%2216%22 rx=%223%22 fill=%22%230f766e%22/%3E%3Cpath d=%22M4 11h8M5 4h6l1 4H4l1-4Z%22 stroke=%22white%22 stroke-width=%221.4%22 fill=%22none%22 stroke-linecap=%22round%22 stroke-linejoin=%22round%22/%3E%3C/svg%3E';
    document.head.appendChild(icon);
  }, []);

  // 播放 interval 由 dtPerFrame 和 speedMultiplier 共同决定：
  //   intervalMs = dtPerFrame * 1000 / speedMultiplier
  // speedMultiplier 变化时 effect 重新执行，清除旧 interval，按新速度重建。
  useEffect(() => {
    if (status !== 'playing' || isPaused || !result || result.states.length === 0) {
      return undefined;
    }

    const dtPerFrame = result.summary.dtPerFrame ?? 0.5;
    const intervalMs = (dtPerFrame * 1000) / speedMultiplier;

    const totalFrames = result.states.length;
    const timerId = window.setInterval(() => {
      setFrameIndex((previousIndex) => {
        if (previousIndex >= totalFrames - 1) {
          window.clearInterval(timerId);
          setStatus('finished');
          return previousIndex;
        }

        const nextIndex = previousIndex + 1;
        if (nextIndex >= totalFrames - 1) {
          window.clearInterval(timerId);
          setStatus('finished');
        }
        return nextIndex;
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
    setIsResultExpanded(false);
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
    setIsResultExpanded(false);
    setSpeedMultiplier(1);
  };

  const handleTogglePause = () => {
    if (status === 'playing') {
      setIsPaused((value) => !value);
    }
  };

  const currentState: TrainState | null =
    result && result.states.length > 0 ? result.states[frameIndex] : null;
  const targetStopPosition = result?.stopResult?.targetStopPosition ?? 1200;
  const speedLimitValue = result?.summary?.speedLimit ?? DEMO_SPEED_LIMIT_FALLBACK_MS;
  const isLoading = status === 'loading';
  const isPlaying = status === 'playing';
  const canReset = result !== null || status === 'error';
  const stopWindowState = result?.stopResult?.stopWindowState;
  const stopWindowClass = stopWindowState ? stopWindowState.toLowerCase() : 'not_accurate';

  return (
    <div className="vehicle-page">
      <header className="vehicle-page__header">
        <div>
          <p className="vehicle-page__eyebrow">Onboard cab HMI</p>
          <h2>车载驾驶台系统</h2>
        </div>
        <div className="vehicle-page__actions">
          <button
            className="vehicle-start-btn"
            onClick={handleStart}
            disabled={isLoading || isPlaying}
            type="button"
          >
            {isLoading ? '仿真计算中...' : status === 'finished' || status === 'error' ? '重新仿真' : '开始仿真'}
          </button>
          <button
            className="vehicle-secondary-btn"
            onClick={handleTogglePause}
            disabled={!isPlaying}
            type="button"
          >
            {isPaused ? '继续' : '暂停'}
          </button>
          <button
            className="vehicle-ghost-btn"
            onClick={handleReset}
            disabled={!canReset || isLoading}
            type="button"
          >
            复位
          </button>

          {/* 倍速按钮组：只在有仿真结果时可交互（播放中也能切换），复位后重置为 1x */}
          <div className="vehicle-speed-group" aria-label="播放倍速">
            {SPEED_MULTIPLIER_OPTIONS.map((m) => (
              <button
                key={m}
                type="button"
                className={`vehicle-speed-btn${speedMultiplier === m ? ' is-active' : ''}`}
                onClick={() => setSpeedMultiplier(m)}
                disabled={!result && status !== 'playing'}
                aria-pressed={speedMultiplier === m}
              >
                {m}x
              </button>
            ))}
          </div>

          {isPlaying && currentState && (
            <span className="vehicle-status-text">
              {isPaused ? '已暂停' : '播放中'} · 第 {frameIndex + 1} / {result?.states.length} 帧 · t = {currentState.time.toFixed(1)}s
            </span>
          )}
          {status === 'finished' && <span className="vehicle-status-text">仿真播放完成</span>}
        </div>
      </header>

      {status === 'error' && (
        <div className="vehicle-error">
          <span>仿真调用失败：{errorMessage}</span>
          <button onClick={handleStart} type="button">重新仿真</button>
        </div>
      )}

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

      {status === 'finished' && result && (
        <section className="vehicle-results" aria-label="仿真结果">
          <div className="vehicle-results__summary">
            <span className={`vehicle-stop-badge vehicle-stop-badge--${stopWindowClass}`}>
              {describeStopWindowState(result.stopResult.stopWindowState)}
            </span>
            <div>
              <h3>停站精度摘要</h3>
              <p>
                误差 {result.stopResult.stopError.toFixed(2)} m · 实停 {result.stopResult.actualStopPosition.toFixed(1)} m ·
                全程 {result.summary.totalTime.toFixed(1)} s
              </p>
            </div>
            <button
              className="vehicle-detail-toggle"
              onClick={() => setIsResultExpanded((value) => !value)}
              type="button"
            >
              {isResultExpanded ? '收起详情' : '查看详情'}
            </button>
          </div>

          {isResultExpanded && (
            <div className="vehicle-results__details">
              <div className="vehicle-result">
                <h3>自动停站结果</h3>
                <table>
                  <tbody>
                    <tr>
                      <td>目标停车点</td>
                      <td>{result.stopResult.targetStopPosition.toFixed(1)} m</td>
                    </tr>
                    <tr>
                      <td>实际停车点</td>
                      <td>{result.stopResult.actualStopPosition.toFixed(1)} m</td>
                    </tr>
                    <tr>
                      <td>停站误差</td>
                      <td>{result.stopResult.stopError.toFixed(2)} m</td>
                    </tr>
                    <tr>
                      <td>停车窗到位状态</td>
                      <td>{describeStopWindowState(result.stopResult.stopWindowState)}</td>
                    </tr>
                    <tr>
                      <td>是否成功</td>
                      <td className={result.stopResult.success ? 'stop-success' : 'stop-fail'}>
                        {result.stopResult.success ? '成功' : '未达标'}
                      </td>
                    </tr>
                    {result.stopResult.reason && (
                      <tr>
                        <td>原因/风险提示</td>
                        <td>{result.stopResult.reason}</td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>

              <div className="vehicle-result">
                <h3>仿真总结</h3>
                <table>
                  <tbody>
                    <tr>
                      <td>最大速度</td>
                      <td>{(result.summary.maxVelocity * 3.6).toFixed(1)} km/h · {result.summary.maxVelocity.toFixed(2)} m/s</td>
                    </tr>
                    <tr>
                      <td>总运行时长</td>
                      <td>{result.summary.totalTime.toFixed(1)} s</td>
                    </tr>
                    <tr>
                      <td>终点位置</td>
                      <td>{result.summary.finalPosition.toFixed(1)} m</td>
                    </tr>
                    <tr>
                      <td>线路限速</td>
                      <td>{(result.summary.speedLimit * 3.6).toFixed(1)} km/h · {result.summary.speedLimit.toFixed(2)} m/s</td>
                    </tr>
                    <tr>
                      <td>帧步长</td>
                      <td>{result.summary.dtPerFrame.toFixed(2)} s</td>
                    </tr>
                    <tr>
                      <td>安全事件数量</td>
                      <td>{result.safetyEvents.length}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </section>
      )}
    </div>
  );
}

export default Vehicle;
