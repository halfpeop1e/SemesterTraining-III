import { useEffect, useMemo, useState } from 'react';
import {
  connectProtocol704,
  disconnectProtocol704,
  getProtocol704Status,
  resetProtocol704,
  sendTestFrame,
} from '../../../api/protocol704';
import type { Protocol704Status } from '../../../types/protocol704';
import type { TrainState } from '../../../types/vehicle';

interface Protocol704PanelProps {
  trainId: string;
  enabled: boolean;
  onError: (message: string) => void;
  onExecutedState?: (state: TrainState, mode?: string, departureState?: string) => void;
}

function formatTime(timestamp?: number) {
  if (!timestamp) return '-';
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false });
}

export default function Protocol704Panel({
  trainId,
  enabled,
  onError,
  onExecutedState,
}: Protocol704PanelProps) {
  const [status, setStatus] = useState<Protocol704Status | null>(null);
  const [polling, setPolling] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [testFrameLoading, setTestFrameLoading] = useState<string | null>(null);

  const refresh = async () => {
    if (!enabled) return;
    try {
      const next = await getProtocol704Status(trainId);
      setStatus(next);
      if (next.lastCommandLifecycle?.status === 'EXECUTED' && next.lastCommandLifecycle.executedState) {
        onExecutedState?.(
          next.lastCommandLifecycle.executedState as TrainState,
          next.lastCommandLifecycle.resultMode,
          next.lastCommandLifecycle.departureState,
        );
      }
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    }
  };

  useEffect(() => {
    if (!enabled) {
      setPolling(false);
      setStatus(null);
      setExpanded(false);
      return undefined;
    }
    void refresh();
  }, [trainId, enabled]);

  useEffect(() => {
    if (!enabled || !polling) return undefined;
    const timerId = window.setInterval(() => {
      void refresh();
    }, 500);
    return () => window.clearInterval(timerId);
  }, [enabled, polling, trainId]);

  const portStatuses = useMemo(
    () => Object.values(status?.portStatuses ?? {}),
    [status?.portStatuses],
  );
  const connected = status?.connected ?? false;
  const simulationReady = status?.simulationReady ?? false;
  const lastMappedCommand = status?.lastMappedCommand;
  const realtimeState = status?.realtimeVehicleState;

  const handleConnect = async () => {
    if (!enabled) return;
    try {
      await connectProtocol704(trainId);
      setPolling(true);
      setExpanded(true);
      await refresh();
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    }
  };

  const handleDisconnect = async () => {
    if (!enabled) return;
    try {
      await disconnectProtocol704(trainId);
      setPolling(false);
      await refresh();
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    }
  };

  const handleReset = async () => {
    if (!enabled) return;
    try {
      await resetProtocol704(trainId);
      setPolling(false);
      await refresh();
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    }
  };

  const handleTestFrame = async (type: string) => {
    if (!enabled) return;
    setTestFrameLoading(type);
    try {
      const next = await sendTestFrame(trainId, type);
      setStatus(next);
      if (next.lastCommandLifecycle?.status === 'EXECUTED' && next.lastCommandLifecycle.executedState) {
        onExecutedState?.(
          next.lastCommandLifecycle.executedState as TrainState,
          next.lastCommandLifecycle.resultMode,
          next.lastCommandLifecycle.departureState,
        );
      }
      setExpanded(true);
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    } finally {
      setTestFrameLoading(null);
    }
  };

  return (
    <section
      className={`vehicle-704-panel ${enabled ? '' : 'is-disabled'}`}
      aria-label={`${trainId} 704协议状态`}
    >
      <div
        className="vehicle-704-panel__header"
      >
        <div className="vehicle-704-panel__summary">
          <strong>704司机台 · {trainId}</strong>
          <span
            className={`vehicle-704-badge ${
                !enabled
                  ? 'vehicle-704-badge--disconnected'
                  : connected
                ? 'vehicle-704-badge--connected'
                : polling
                  ? 'vehicle-704-badge--connecting'
                  : 'vehicle-704-badge--disconnected'
            }`}
          >
              {!enabled ? '设备模式未启用' : connected ? '已连接' : polling ? '连接中' : '未连接'}
            </span>
            {enabled ? (
              <>
                <span className="vehicle-704-target">
                  {status?.host ?? '192.168.100.123'}:
                  {portStatuses.map((port) => port.port).join('/') || '8001/8002/8003'}
                </span>
                <span className={`vehicle-704-readiness ${simulationReady ? 'vehicle-704-readiness--ready' : 'vehicle-704-readiness--pending'}`}>
                  {simulationReady ? '仿真已准备' : '仿真未准备'}
                </span>
              </>
            ) : (
              <span className="vehicle-704-mode-note">当前由软件仿真控制，不会连接 PLC</span>
            )}
          {!expanded && lastMappedCommand && (
            <span className="vehicle-704-last-command">
              最近命令 {lastMappedCommand.command}
              {lastMappedCommand.levelPercent > 0
                ? ` ${lastMappedCommand.levelPercent}%`
                : ''}
            </span>
          )}
        </div>
        <div className="vehicle-704-panel__actions">
          <button
            type="button"
            className="vehicle-704-btn"
            disabled={!enabled || polling}
            onClick={() => void handleConnect()}
          >
            连接
          </button>
          <button
            type="button"
            className="vehicle-704-btn"
            disabled={!enabled || !polling}
            onClick={() => void handleDisconnect()}
          >
            断开
          </button>
          <button
            type="button"
            className="vehicle-704-btn"
            disabled={!enabled}
            onClick={() => void handleReset()}
          >
            重置
          </button>
          <button
            type="button"
            className="vehicle-704-expand-btn"
            disabled={!enabled}
            aria-label={expanded ? '收起704协议详情' : '展开704协议详情'}
            onClick={() => setExpanded((value) => !value)}
          >
            {expanded ? '▲' : '▼'}
          </button>
        </div>
      </div>

      {enabled && expanded && (
        <div className="vehicle-704-panel__body">
          <div className="vehicle-704-section">
            <div className="vehicle-704-section__title">联调前置条件</div>
            <div className="vehicle-704-preflight">
              <strong>{simulationReady ? '当前列车可接收 PLC 控制' : '当前列车尚未准备好'}</strong>
              <span>{status?.simulationReadiness ?? '正在检查仿真状态'}</span>
            </div>
          </div>

          <div className="vehicle-704-section">
            <div className="vehicle-704-section__title">端口状态</div>
            <div className="vehicle-704-port-grid">
              {portStatuses.map((port) => (
                <div
                  key={port.port}
                  className={`vehicle-704-port-card ${
                    port.connected
                      ? 'vehicle-704-port-card--connected'
                      : port.connecting
                        ? 'vehicle-704-port-card--connecting'
                        : ''
                  }`}
                >
                  <div className="vehicle-704-port-card__header">
                    <strong>PLC {port.port}</strong>
                    <span>
                      {port.connecting ? '连接中' : port.connected ? '已连接' : '未连接'}
                    </span>
                  </div>
                  <div className="vehicle-704-port-card__details">
                    <span>最近接收 {formatTime(port.lastReceiveTime)}</span>
                    <span>帧数 {port.frameCount ?? 0}</span>
                    <span>帧长 {port.lastFrameLength ?? 0}B</span>
                    <span>字节 {port.bytesReceived ?? 0}</span>
                  </div>
                  {port.lastError && (
                    <div className="vehicle-704-port-card__error">{port.lastError}</div>
                  )}
                </div>
              ))}
            </div>
          </div>

          <div className="vehicle-704-section vehicle-704-test-section">
            <div className="vehicle-704-section__title">
              本地解析测试帧
              <span className="vehicle-704-test-warning">不经过 TCP，不代表设备命令</span>
            </div>
            <div className="vehicle-704-test-buttons">
              {[
                ['coast', '测试惰行'],
                ['traction', '测试牵引'],
                ['brake', '测试制动'],
                ['emergency_brake', '测试EB'],
              ].map(([type, label]) => (
                <button
                  key={type}
                  type="button"
                  className={`vehicle-704-test-btn vehicle-704-test-btn--${type}`}
                  disabled={!enabled || testFrameLoading !== null}
                  onClick={() => void handleTestFrame(type)}
                >
                  {testFrameLoading === type ? '发送中...' : label}
                </button>
              ))}
            </div>
          </div>

          {(status?.lastParsedFrame || lastMappedCommand || realtimeState) && (
            <div className="vehicle-704-section">
              <div className="vehicle-704-section__title">解析与实时状态</div>
              <div className="vehicle-704-parse-grid">
                <div className="vehicle-704-parse-item">
                  <span>映射命令</span>
                  <strong>{lastMappedCommand?.command ?? '-'}</strong>
                </div>
                <div className="vehicle-704-parse-item">
                  <span>级位</span>
                  <strong>{lastMappedCommand?.levelPercent ?? 0}%</strong>
                </div>
                <div className="vehicle-704-parse-item">
                  <span>仿真速度</span>
                  <strong>{realtimeState?.velocityMs.toFixed(2) ?? '0.00'} m/s</strong>
                </div>
                <div className="vehicle-704-parse-item">
                  <span>仿真位置</span>
                  <strong>{realtimeState?.positionM.toFixed(1) ?? '0.0'} m</strong>
                </div>
              </div>
              <div className="vehicle-704-parse-grid">
                <div className="vehicle-704-parse-item"><span>收到合法帧</span><strong>{status?.receivedValidFrame ? '是' : '否'}</strong></div>
                <div className="vehicle-704-parse-item"><span>最近合法帧</span><strong>{formatTime(status?.lastValidFrameTime)}</strong></div>
                <div className="vehicle-704-parse-item"><span>命令生命周期</span><strong>{status?.lastCommandLifecycle?.status ?? '-'}</strong></div>
                <div className="vehicle-704-parse-item"><span>拒绝/失败原因</span><strong>{status?.lastCommandLifecycle?.rejectionReason ?? status?.lastCommandLifecycle?.executionError ?? '-'}</strong></div>
              </div>
              {status?.activeBinding && <div className="vehicle-704-test-warning">绑定：{status.activeBinding}</div>}
            </div>
          )}

          {status?.lastRawHex && (
            <div className="vehicle-704-section">
              <div className="vehicle-704-section__title">最近原始帧</div>
              <div className="vehicle-704-raw-hex">{status.lastRawHex}</div>
            </div>
          )}

          {status?.recentLogs && status.recentLogs.length > 0 && (
            <div className="vehicle-704-section">
              <div className="vehicle-704-section__title">最近日志</div>
              <div className="vehicle-704-log-list">
                {status.recentLogs.slice(-12).reverse().map((log, index) => (
                  <div
                    key={`${log.timestamp}-${index}`}
                    className={`vehicle-704-log-item ${
                      log.source === 'TEST_FRAME' ? 'vehicle-704-log-item--test' : ''
                    }`}
                  >
                    <span>{formatTime(log.timestamp)}</span>
                    <strong>{log.source === 'TEST_FRAME' ? '测试帧' : '硬件帧'}</strong>
                    <span>端口 {log.port || '-'}</span>
                    <span>{log.frameLength}B</span>
                    <span>{log.mappedCommand?.command ?? '-'}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
