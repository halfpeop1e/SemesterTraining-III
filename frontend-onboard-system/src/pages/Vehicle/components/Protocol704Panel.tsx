import { useEffect, useMemo, useState } from 'react';
import {
  connectProtocol704,
  disconnectProtocol704,
  getProtocol704Status,
  resetProtocol704,
  sendTestFrame,
} from '../../../api/protocol704';
import type { Protocol704Status } from '../../../types/protocol704';

interface Protocol704PanelProps {
  trainId: string;
  onError: (message: string) => void;
}

function formatTime(timestamp?: number) {
  if (!timestamp) return '-';
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false });
}

export default function Protocol704Panel({
  trainId,
  onError,
}: Protocol704PanelProps) {
  const [status, setStatus] = useState<Protocol704Status | null>(null);
  const [polling, setPolling] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [testFrameLoading, setTestFrameLoading] = useState<string | null>(null);

  const refresh = async () => {
    try {
      setStatus(await getProtocol704Status(trainId));
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    }
  };

  useEffect(() => {
    void refresh();
  }, [trainId]);

  useEffect(() => {
    if (!polling) return undefined;
    const timerId = window.setInterval(() => {
      void refresh();
    }, 500);
    return () => window.clearInterval(timerId);
  }, [polling, trainId]);

  const portStatuses = useMemo(
    () => Object.values(status?.portStatuses ?? {}),
    [status?.portStatuses],
  );
  const connected = status?.connected ?? false;
  const lastMappedCommand = status?.lastMappedCommand;
  const realtimeState = status?.realtimeVehicleState;

  const handleConnect = async () => {
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
    try {
      await disconnectProtocol704(trainId);
      setPolling(false);
      await refresh();
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    }
  };

  const handleReset = async () => {
    try {
      await resetProtocol704(trainId);
      setPolling(false);
      await refresh();
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    }
  };

  const handleTestFrame = async (type: string) => {
    setTestFrameLoading(type);
    try {
      setStatus(await sendTestFrame(trainId, type));
      setExpanded(true);
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    } finally {
      setTestFrameLoading(null);
    }
  };

  return (
    <section className="vehicle-704-panel" aria-label={`${trainId} 704协议状态`}>
      <div
        className="vehicle-704-panel__header"
      >
        <div className="vehicle-704-panel__summary">
          <strong>704司机台 · {trainId}</strong>
          <span
            className={`vehicle-704-badge ${
              connected
                ? 'vehicle-704-badge--connected'
                : polling
                  ? 'vehicle-704-badge--connecting'
                  : 'vehicle-704-badge--disconnected'
            }`}
          >
            {connected ? '已连接' : polling ? '连接中' : '未连接'}
          </span>
          <span className="vehicle-704-target">
            {status?.host ?? '192.168.100.123'}:
            {portStatuses.map((port) => port.port).join('/') || '8001/8002/8003'}
          </span>
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
            disabled={polling}
            onClick={() => void handleConnect()}
          >
            连接
          </button>
          <button
            type="button"
            className="vehicle-704-btn"
            disabled={!polling}
            onClick={() => void handleDisconnect()}
          >
            断开
          </button>
          <button
            type="button"
            className="vehicle-704-btn"
            onClick={() => void handleReset()}
          >
            重置
          </button>
          <button
            type="button"
            className="vehicle-704-expand-btn"
            aria-label={expanded ? '收起704协议详情' : '展开704协议详情'}
            onClick={() => setExpanded((value) => !value)}
          >
            {expanded ? '▲' : '▼'}
          </button>
        </div>
      </div>

      {expanded && (
        <div className="vehicle-704-panel__body">
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
              测试帧
              <span className="vehicle-704-test-warning">仅用于协议链路验证</span>
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
                  disabled={testFrameLoading !== null}
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
                  <span>实时速度</span>
                  <strong>{realtimeState?.velocityMs.toFixed(2) ?? '0.00'} m/s</strong>
                </div>
                <div className="vehicle-704-parse-item">
                  <span>实时位置</span>
                  <strong>{realtimeState?.positionM.toFixed(1) ?? '0.0'} m</strong>
                </div>
              </div>
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
