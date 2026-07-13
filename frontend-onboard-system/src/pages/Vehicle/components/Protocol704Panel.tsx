import { useEffect, useMemo, useRef, useState } from 'react';
import {
  connectProtocol704,
  disconnectProtocol704,
  getProtocol704Status,
  resetProtocol704,
  sendTestFrame,
  syncProtocol704State,
} from '../../../api/protocol704';
import type {
  Protocol704PortStatus,
  Protocol704Status,
  RealtimeVehicleState704,
} from '../../../types/protocol704';
import type { SimulationControlRequest, SimulationResult, TrainState } from '../../../types/vehicle';

interface Protocol704PanelProps {
  trainId: string;
  enabled: boolean;
  onError: (message: string) => void;
  /** 命令执行回调（commandId 去重后只触发一次）。
   *  result 非空时包含完整 EB 制动轨迹，前端拼接到主 result.states 后播放。 */
  onExecutedState?: (
    state: TrainState,
    mode: string,
    departureState: string | undefined,
    latched: boolean,
    result?: SimulationResult,
  ) => void;
  /** 每次状态轮询都向主 Vehicle 页面发布后端实时车辆状态。 */
  onRealtimeState?: (state: RealtimeVehicleState704) => void;
  /** 获取当前 ATO 播放状态，测试帧发送前同步到 Bridge，使 EB 从真实当前位置开始。 */
  getCurrentState?: () => SimulationControlRequest | null;
}

function formatTime(timestamp?: number) {
  if (!timestamp) return '暂无数据';
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false });
}

const inputStateLabels: Record<string, string> = {
  TCP_NOT_CONNECTED: '设备未连接',
  TCP_CONNECTING: '连接中',
  CHANNEL_DISABLED: '通道未启用',
  CONNECTED_NO_BYTES: '已连接，未收到数据',
  PARTIAL_FRAME: '已收到部分数据，等待完整帧',
  HEADER_MISMATCH: '帧头错误',
  TOTAL_LENGTH_MISMATCH: '总长度错误',
  DATA_LENGTH_MISMATCH: '数据长度错误',
  STRUCTURE_INVALID: '解析失败',
  FIRST_FRAME_RECEIVED: '首帧成功',
  FRAME_RECEIVED: '后续帧成功',
};

const parsedFieldLabels: Record<string, string> = {
  header_valid: '帧头有效',
  total_len_field: '总长度字段',
  data_len_field: '数据长度字段',
  year: '年',
  month: '月',
  day: '日',
  hour: '时',
  minute: '分',
  second: '秒',
  mode_byte: '模式字节',
  direction_handle: '方向手柄',
  direction_desc: '方向语义',
  direction_semantic: '方向语义',
  master_handle: '主手柄',
  control_mode_request: '司机台驾驶模式输入',
  traction_level_raw: '牵引级位原始值',
  traction_level_percent_raw: '牵引输入/级位',
  brake_level_raw: '制动级位原始值',
  brake_level_percent_raw: '制动输入/级位',
  mapped_command: '映射命令',
};

function presentValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '暂无数据';
  if (Array.isArray(value)) return value.length === 0 ? '[]' : JSON.stringify(value);
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  return String(value);
}

function directionDisplay(key: string, value: unknown) {
  if (value === undefined || value === null || value === '') return '暂无方向数据';
  if (key === 'driverCabDirection' || key === 'direction_semantic') {
    return ({ FORWARD: '前进', REVERSE: '后退', ZERO: '零位' } as Record<string, string>)[String(value)] ?? String(value);
  }
  if (key === 'direction') {
    return ({ UP: '上行', DOWN: '下行' } as Record<string, string>)[String(value)] ?? String(value);
  }
  return presentValue(value);
}

function inputStateLabel(state?: string) {
  return state ? (inputStateLabels[state] ?? state) : '未接收';
}

type DeviceKey = 'PLC' | 'HMI' | 'MMI';
type ConnectionAction = 'connect' | 'disconnect' | 'reset' | null;

function deviceConnectionLabel(
  enabled: boolean,
  port: Protocol704PortStatus | undefined,
  action: ConnectionAction,
) {
  if (!enabled) return '设备模式未启用';
  if (action === 'disconnect') return '断开中';
  if (action === 'connect') return port?.lastError || port?.lastDisconnectTime ? '重连中' : '连接中';
  if (port?.connected) return '已连接';
  if (port?.connecting && port.reconnecting) return '重连中';
  if (port?.connecting) return '连接中';
  if (port?.lastError) return '连接失败';
  if (port?.lastDisconnectTime) return '已断开';
  return '已断开';
}

function deviceStatusClass(label: string) {
  if (label === '已连接') return 'vehicle-704-device-card--connected';
  if (label === '连接中' || label === '重连中' || label === '断开中') {
    return 'vehicle-704-device-card--connecting';
  }
  if (label === '连接失败') return 'vehicle-704-device-card--error';
  return '';
}

export default function Protocol704Panel({
  trainId,
  enabled,
  onError,
  onExecutedState,
  onRealtimeState,
  getCurrentState,
}: Protocol704PanelProps) {
  const [status, setStatus] = useState<Protocol704Status | null>(null);
  const [polling, setPolling] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [testFrameLoading, setTestFrameLoading] = useState<string | null>(null);
  const [connectionAction, setConnectionAction] = useState<ConnectionAction>(null);
  const [requestFeedback, setRequestFeedback] = useState<string | null>(null);

  // commandId 去重：同一个 lastCommandLifecycle.commandId 只允许触发一次 onExecutedState。
  // 防止 500ms 轮询反复用历史 executedState 覆盖主页面 displayState。
  const lastProcessedCommandIdRef = useRef<string | null>(null);
  // 首次 refresh 标志：mount/trainId 切换后的第一次 refresh 只建立 baseline，不触发回调。
  const firstRefreshRef = useRef(true);

  const processLifecycle = (next: Protocol704Status, fromTestFrame: boolean) => {
    const lifecycle = next.lastCommandLifecycle;
    if (!lifecycle) return;
    const latched = next.realtimeVehicleState?.emergencyLatched === true
      || lifecycle.resultMode === 'EMERGENCY'
      || lifecycle.emergencyLatchedAfter === true;

    if (lifecycle.status === 'EXECUTED' && lifecycle.executedState) {
      const cmdId = lifecycle.commandId;
      if (firstRefreshRef.current && !fromTestFrame) {
        // 首次 refresh：只建立 baseline，不重放历史命令
        lastProcessedCommandIdRef.current = cmdId ?? null;
      } else if (cmdId && cmdId !== lastProcessedCommandIdRef.current) {
        // 新 commandId：触发一次回调
        lastProcessedCommandIdRef.current = cmdId;
        onExecutedState?.(
           lifecycle.executedState as TrainState,
           lifecycle.resultMode ?? next.realtimeVehicleState?.mode ?? 'UNKNOWN',
           lifecycle.departureState,
           latched,
           lifecycle.executedResult as SimulationResult | undefined,
        );
      }
      // 同 commandId 重复轮询：不触发（去重核心）
    }
    // REJECTED/FAILED：不调用 onExecutedState，拒绝原因由面板自身的 rejectionReason 字段展示。
    // 不再合成 BRAKING 状态覆盖 displayState（旧逻辑的 else if (latched) 分支已移除）。
  };

  const refresh = async () => {
    if (!enabled) return;
    try {
      const next = await getProtocol704Status(trainId);
      setStatus(next);
      if (next.realtimeVehicleState) {
        onRealtimeState?.(next.realtimeVehicleState);
      }
      processLifecycle(next, false);
    } catch (error) {
      onError(error instanceof Error ? error.message : String(error));
    } finally {
      firstRefreshRef.current = false;
    }
  };

  useEffect(() => {
    if (!enabled) {
      setPolling(false);
      setStatus(null);
      setExpanded(false);
      firstRefreshRef.current = true;
      lastProcessedCommandIdRef.current = null;
      return undefined;
    }
    firstRefreshRef.current = true;
    lastProcessedCommandIdRef.current = null;
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
  const plcPortNumber = status?.ports?.[0] ?? 8001;
  const plcInput = portStatuses.find((port) => port.port === plcPortNumber);
  const hmiInput = portStatuses.find((port) => port.channel?.includes('hmi-input'))
    ?? portStatuses.find((port) => port.port === 8888);
  const mmiOutput = portStatuses.find((port) => port.channel?.includes('mmi-output'))
    ?? portStatuses.find((port) => port.port === 9999);
  const plcInputReceived = (plcInput?.frameCount ?? 0) > 0;
  const parsedFields = Object.entries(status?.lastParsedFrame?.fields ?? {});
  const parsedValue = (key: string) => status?.lastParsedFrame?.fields?.[key];

  const devicePorts: Record<DeviceKey, Protocol704PortStatus | undefined> = {
    PLC: plcInput,
    HMI: hmiInput,
    MMI: mmiOutput,
  };

  const deviceDefinitions: Array<{
    key: DeviceKey;
    name: string;
    purpose: string;
    channel: string;
    frame: string;
  }> = [
    {
      key: 'PLC',
      name: 'PLC（可编程逻辑控制器）',
      purpose: '704控制帧接收与控制回写',
      channel: plcInput?.channel ?? 'plc-input / plc-output',
      frame: '46B local-v1 输入 / 协议输出',
    },
    {
      key: 'HMI',
      name: 'HMI（网络屏）',
      purpose: '牵引切除输入和网络屏输出',
      channel: hmiInput?.channel ?? 'hmi-input / hmi-output',
      frame: '26B local-v1 输入 / HMI 输出',
    },
    {
      key: 'MMI',
      name: 'MMI（信号屏）',
      purpose: '信号屏状态输出',
      channel: mmiOutput?.channel ?? 'mmi-output',
      frame: 'MMI 输出',
    },
  ];

  const handleConnect = async () => {
    if (!enabled) return;
    setConnectionAction('connect');
    setRequestFeedback(null);
    try {
      await connectProtocol704(trainId);
      setPolling(true);
      setExpanded(true);
      setRequestFeedback('连接请求已发送，等待设备状态确认');
      await refresh();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setRequestFeedback(`连接请求失败：${message}`);
      onError(message);
    } finally {
      setConnectionAction(null);
    }
  };

  const handleDisconnect = async () => {
    if (!enabled) return;
    setConnectionAction('disconnect');
    setRequestFeedback(null);
    try {
      await disconnectProtocol704(trainId);
      setPolling(false);
      setRequestFeedback('断开请求已发送，等待设备状态确认');
      await refresh();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setRequestFeedback(`断开请求失败：${message}`);
      onError(message);
    } finally {
      setConnectionAction(null);
    }
  };

  const handleReset = async () => {
    if (!enabled) return;
    setConnectionAction('reset');
    setRequestFeedback(null);
    try {
      await resetProtocol704(trainId);
      setPolling(false);
      setRequestFeedback('重置请求已发送，等待设备状态确认');
      await refresh();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      setRequestFeedback(`重置请求失败：${message}`);
      onError(message);
    } finally {
      setConnectionAction(null);
    }
  };

  const handleTestFrame = async (type: string) => {
    if (!enabled) return;
    setTestFrameLoading(type);
    try {
      // 测试帧发送前，把当前 ATO 播放状态同步到 Bridge，使 EB 从真实当前位置开始制动。
      // 同步是 best-effort：失败不阻塞测试帧发送。
      if (getCurrentState) {
        const syncPayload = getCurrentState();
        if (syncPayload) {
          try {
            await syncProtocol704State(syncPayload);
          } catch {
            // best-effort: 继续
          }
        }
      }

      const next = await sendTestFrame(trainId, type);
      setStatus(next);
      if (next.realtimeVehicleState) {
        onRealtimeState?.(next.realtimeVehicleState);
      }
      processLifecycle(next, true);
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
            <div className="vehicle-704-section__title">联调前置条件</div>
            <div className="vehicle-704-preflight">
              <strong>
                {!enabled
                  ? '设备模式未启用'
                  : simulationReady
                    ? '当前列车可接收 PLC 控制'
                    : '当前列车尚未准备好'}
              </strong>
              <span>
                {!enabled
                  ? '切换到实验室司机台模式后，才会发送704连接请求'
                  : status?.simulationReadiness ?? '正在检查仿真状态'}
              </span>
            </div>
            {requestFeedback && <div className="vehicle-704-request-feedback">{requestFeedback}</div>}
          </div>

          <div className="vehicle-704-section">
            <div className="vehicle-704-section__title">704设备连接</div>
            <div className="vehicle-704-device-grid">
              {deviceDefinitions.map((device) => {
                const port = devicePorts[device.key];
                const stateLabel = deviceConnectionLabel(enabled, port, connectionAction);
                const endpoint = port?.host && port.port
                  ? `${port.host}:${port.port}`
                  : port?.port
                    ? `暂无数据:${port.port}`
                    : '暂无数据';
                return (
                <div
                  key={device.key}
                  className={`vehicle-704-device-card ${deviceStatusClass(stateLabel)}`}
                >
                  <div className="vehicle-704-device-card__header">
                    <div>
                      <strong>{device.name}</strong>
                      <small>原始标识：{device.key}</small>
                    </div>
                    <span>{stateLabel}</span>
                  </div>
                  <div className="vehicle-704-device-card__details">
                    <span><b>用途</b>{device.purpose}</span>
                    <span><b>endpoint</b>{endpoint}</span>
                    <span><b>通道</b>{device.channel}</span>
                    <span><b>帧</b>{device.frame}</span>
                    <span><b>最近连接</b>{formatTime(port?.lastConnectSuccessTime)}</span>
                    <span><b>最近断开</b>{formatTime(port?.lastDisconnectTime)}</span>
                    <span><b>最近数据</b>{port?.lastReceiveTime ? formatTime(port.lastReceiveTime) : '未接收'}</span>
                    <span><b>最近错误</b>{presentValue(port?.lastError ?? port?.lastOutputError)}</span>
                  </div>
                  <div className="vehicle-704-device-card__actions">
                    <button
                      type="button"
                      disabled={!enabled || connectionAction !== null || stateLabel === '已连接'}
                      onClick={() => void handleConnect()}
                    >
                      连接/重试
                    </button>
                    <button
                      type="button"
                      disabled={!enabled || connectionAction !== null || (!port?.connected && !polling)}
                      onClick={() => void handleDisconnect()}
                    >
                      断开
                    </button>
                    <button
                      type="button"
                      disabled={!enabled || connectionAction !== null}
                      onClick={() => void handleReset()}
                    >
                      重置
                    </button>
                  </div>
                  <div className="vehicle-704-device-card__note">
                    卡片操作复用整体704连接/断开/重置接口；接口成功仅代表请求已发送。
                  </div>
                </div>
                );
              })}
            </div>
          </div>

          <div className="vehicle-704-section">
            <div className="vehicle-704-section__title">端口状态与输出统计</div>
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
                    <strong>{port.channel ?? `端口 ${port.port}`}</strong>
                    <span>{deviceConnectionLabel(enabled, port, null)}</span>
                  </div>
                  <div className="vehicle-704-port-card__details">
                    <span>endpoint {port.host ? `${port.host}:${port.port}` : `暂无数据:${port.port}`}</span>
                    <span>输入字节 {presentValue(port.bytesReceived)}</span>
                    <span>输入帧数 {presentValue(port.frameCount)}</span>
                    <span>输出帧数 {presentValue(port.outputFrameCount)}</span>
                    <span>输出字节 {presentValue(port.bytesSent)}</span>
                    <span>最近输出 {formatTime(port.lastOutputTime)}</span>
                  </div>
                  {(port.lastError || port.lastOutputError) && (
                    <div className="vehicle-704-port-card__error">{port.lastError ?? port.lastOutputError}</div>
                  )}
                </div>
              ))}
            </div>
          </div>

          <div className="vehicle-704-section vehicle-704-live-data-section">
            <div className="vehicle-704-section__title">协议704实时数据与输入诊断</div>
            <div className="vehicle-704-input-diagnostic-grid">
              {[
                { name: 'PLC 输入', endpoint: `${status?.host ?? '192.168.100.123'}:${plcPortNumber}`, frame: '46B local-v1', port: plcInput },
                {
                  name: 'HMI 输入',
                  endpoint: hmiInput?.host && hmiInput.port
                    ? `${hmiInput.host}:${hmiInput.port}`
                    : '地址未返回',
                  frame: '26B local-v1',
                  port: hmiInput,
                },
              ].map(({ name, endpoint, frame, port }) => (
                <div className="vehicle-704-input-diagnostic" key={name}>
                  <div className="vehicle-704-input-diagnostic__header">
                    <strong>{name}</strong>
                    <span>{inputStateLabel(port?.inputState)}</span>
                  </div>
                  <div className="vehicle-704-input-diagnostic__endpoint">{endpoint} · {frame}</div>
                  <div className="vehicle-704-input-diagnostic__message">
                    {port?.inputDiagnostic ?? (port ? '未接收' : '暂无数据')}
                  </div>
                  <div className="vehicle-704-input-diagnostic__stats">
                    <span>bytesReceived {presentValue(port?.bytesReceived)}</span>
                    <span>frameCount {presentValue(port?.frameCount)}</span>
                    <span>lastFrameLength {presentValue(port?.lastFrameLength)}</span>
                    <span>pending {presentValue(port?.pendingInputBytes)}</span>
                    <span>最近更新 {formatTime(port?.lastReceiveTime)}</span>
                  </div>
                  <div className="vehicle-704-input-diagnostic__stats">
                    <span>header {presentValue(port?.lastInputHeader)}</span>
                    <span>totalLength {presentValue(port?.lastInputTotalLength)}</span>
                    <span>dataLength {presentValue(port?.lastInputDataLength)}</span>
                    <span>已接收 {port?.inputState === 'FIRST_FRAME_RECEIVED' || port?.inputState === 'FRAME_RECEIVED' ? '是' : '否'}</span>
                  </div>
                </div>
              ))}
            </div>

            <div className="vehicle-704-live-data-block">
              <strong>最近PLC原始帧与解析字段</strong>
              <div className="vehicle-704-live-data-caption">
                原始字段名 / 中文名称 · 当前值 · 解析状态 · 数据来源 · 最近更新时间 · 是否已接收
              </div>
              <div className="vehicle-704-live-data-table">
                {parsedFields.length > 0 ? parsedFields.map(([key, value]) => (
                  <div className="vehicle-704-live-data-row" key={key}>
                    <span><code>{key}</code><small>{parsedFieldLabels[key] ?? '未定义字段'}</small></span>
                    <span>{presentValue(value)}</span>
                    <span>{plcInputReceived ? '已解析' : '未接收'}</span>
                    <span>PLC 46B local-v1</span>
                    <span>{formatTime(plcInput?.lastReceiveTime)}</span>
                    <span>{plcInputReceived ? '是' : '否'}</span>
                  </div>
                )) : (
                  <div className="vehicle-704-live-data-empty">暂无数据 · 未接收合法PLC输入帧</div>
                )}
              </div>
              <div className="vehicle-704-live-data-raw">
                <span>rawFrame</span>
                <code>{status?.lastRawHex ?? '未接收'}</code>
              </div>
            </div>

            <div className="vehicle-704-live-data-block">
              <strong>实时车辆数据</strong>
              <div className="vehicle-704-live-data-table vehicle-704-live-data-table--compact">
                {[
                  ['velocityMs', '速度', realtimeState?.velocityMs],
                  ['positionM', '位置', realtimeState?.positionM],
                  ['accelerationMs2', '加速度', realtimeState?.accelerationMs2],
                  ['mode', '模式', realtimeState?.mode],
                  ['lastCommand', '最近命令', realtimeState?.lastCommand],
                  ['driverCabDirection', '司机台方向', realtimeState?.driverCabDirection],
                  ['direction', '折返方向', realtimeState?.direction],
                ].map(([key, label, value]) => (
                  <div className="vehicle-704-live-data-row" key={String(key)}>
                    <span><code>{String(key)}</code><small>{String(label)}</small></span>
                    <span>{directionDisplay(String(key), value)}</span>
                    <span>{plcInputReceived ? '已接收' : '未接收'}</span>
                    <span>Protocol704Status</span>
                    <span>{formatTime(realtimeState?.lastUpdateTime)}</span>
                    <span>{plcInputReceived ? '是' : '否'}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="vehicle-704-live-data-block">
              <strong>司机台驾驶输入与方向</strong>
              <div className="vehicle-704-live-data-table vehicle-704-live-data-table--compact">
                {[
                  ['mode', '当前驾驶模式', realtimeState?.mode],
                  ['control_mode_request', '司机台驾驶模式输入', parsedValue('control_mode_request') ?? parsedValue('mode_byte')],
                  ['direction_handle', '方向手柄', parsedValue('direction_handle')],
                  ['direction_semantic', '前进/后退方向', parsedValue('direction_semantic') ?? parsedValue('direction_desc')],
                  ['master_handle', '主手柄', parsedValue('master_handle')],
                  ['traction_level_percent_raw', '牵引输入和牵引级位', parsedValue('traction_level_percent_raw')],
                  ['brake_level_percent_raw', '制动输入和制动级位', parsedValue('brake_level_percent_raw')],
                ].map(([key, label, value]) => (
                  <div className="vehicle-704-live-data-row" key={String(key)}>
                    <span><code>{String(key)}</code><small>{String(label)}</small></span>
                    <span>{presentValue(value)}</span>
                    <span>{plcInputReceived ? '已解析' : '未接收'}</span>
                    <span>PLC 46B local-v1</span>
                    <span>{formatTime(plcInput?.lastReceiveTime)}</span>
                    <span>{plcInputReceived ? '是' : '否'}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="vehicle-704-live-data-block">
              <strong>最近映射命令与输出帧</strong>
              <div className="vehicle-704-live-data-table vehicle-704-live-data-table--compact">
                {[
                  ['command', '最近映射命令', lastMappedCommand?.command],
                  ['source', '数据来源', status?.activeBinding ?? status?.connectionNote],
                  ['updatedAt', '最近更新时间', realtimeState?.lastUpdateTime
                    ? formatTime(realtimeState.lastUpdateTime)
                    : undefined],
                  ['protocolOutputFrame', '协议输出帧', status?.lastOutputFrame],
                  ['hmiOutputFrame', 'HMI输出帧', status?.lastOutputHmi],
                  ['mmiOutputFrame', 'MMI输出帧', mmiOutput?.lastOutputHex],
                ].map(([key, label, value]) => (
                  <div className="vehicle-704-live-data-row" key={String(key)}>
                    <span><code>{String(key)}</code><small>{String(label)}</small></span>
                    <span>{presentValue(value)}</span>
                    <span>{value === undefined || value === null ? '暂无数据' : '已返回'}</span>
                    <span>Protocol704Status / PortConnectionStatus</span>
                    <span>{formatTime(realtimeState?.lastUpdateTime)}</span>
                    <span>{value === undefined || value === null ? '否' : '是'}</span>
                  </div>
                ))}
              </div>
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
                <div className="vehicle-704-parse-item"><span>PLC输入帧</span><strong>{inputStateLabel(plcInput?.inputState)}</strong></div>
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
