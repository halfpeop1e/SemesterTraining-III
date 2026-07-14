import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Input, Popconfirm, Select, Tag, message } from 'antd';
import {
  DeleteOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { addDispatchTrain, removeDispatchTrain, startSimulation } from '../../../api/dispatch';
import {
  buildLaboratoryStationLeg,
  cancelLaboratoryStationLeg,
  getLaboratoryStationLegCapabilities,
  type LaboratoryStationLegCapability,
} from '../../../api/signal';
import type { LineProfile, TrainState } from '../../../types/signal';

type ChannelStatus = {
  enabled?: boolean;
  connected?: boolean;
  framesSent?: number;
  lastError?: string | null;
};

type HilStatus = {
  enabled?: boolean;
  trainId?: string;
  signalScreen?: ChannelStatus;
  networkScreen?: ChannelStatus;
  plcOutput?: ChannelStatus;
  vision?: ChannelStatus;
};

type DeskStatus = {
  connected?: boolean;
  receivedValidFrame?: boolean;
  lastFrameLength?: number;
  lastValidFrameTime?: number;
  staleInputFailSafeTriggered?: boolean;
  portStatuses?: Record<string, {
    connected?: boolean;
    lastReceiveTime?: number;
    lastFrameIntervalMs?: number;
    frameCount?: number;
  }>;
  simulationReady?: boolean;
  simulationReadiness?: string;
  lastMappedCommand?: { command?: string };
  realtimeVehicleState?: { velocityMs?: number; positionM?: number };
};

type DriverWorkflow = {
  atoReady?: boolean;
  atoReadinessBlockingReason?: string;
  control?: {
    fromStationId?: number;
    toStationId?: number;
    currentStationId?: number;
    nextTargetStationId?: number;
  };
};

interface Props {
  lineProfile: LineProfile;
  trains: TrainState[];
  initialStationId: string;
  onCreated: () => void;
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  const payload = await response.json();
  if (!response.ok || payload.success === false) throw new Error(payload.message || `HTTP ${response.status}`);
  return (payload.data ?? payload) as T;
}

async function postText(path: string): Promise<void> {
  const response = await fetch(`/api${path}`, { method: 'POST' });
  if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
}

function channel(name: string, value?: ChannelStatus, unacknowledged = false) {
  if (!value?.enabled) return <Tag>{name}: 未启用</Tag>;
  if (value.connected && unacknowledged) {
    return <Tag color="gold">{name}: 已投递 {value.framesSent ?? 0}（未确认接收）</Tag>;
  }
  if (value.connected) return <Tag color="green">{name}: 已发送 {value.framesSent ?? 0}</Tag>;
  return <Tag color="red">{name}: 未连接</Tag>;
}

function plcOutputChannel(value?: ChannelStatus) {
  if (!value?.enabled) return <Tag>PLC回写: 未启用</Tag>;
  if (value.connected) return <Tag color="green">PLC回写: 28B #{value.framesSent ?? 0}</Tag>;
  return <Tag color="red" title={value.lastError || undefined}>PLC回写: 未连接</Tag>;
}

export default function LabTrainQuickStart({ lineProfile, trains, initialStationId, onCreated }: Props) {
  const stationIds = useMemo(
    () => lineProfile.stations.map((station) => Number(station.id)).sort((a, b) => a - b),
    [lineProfile.stations],
  );
  const firstStation = stationIds[0] ?? 1;
  const [trainId, setTrainId] = useState('LB');
  const [fromStationId, setFromStationId] = useState(firstStation);
  const [toStationId, setToStationId] = useState(firstStation + 1);
  const [starting, setStarting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [routing, setRouting] = useState(false);
  const [desk, setDesk] = useState<DeskStatus | null>(null);
  const [hil, setHil] = useState<HilStatus | null>(null);
  const [workflow, setWorkflow] = useState<DriverWorkflow | null>(null);
  const [capabilities, setCapabilities] = useState<LaboratoryStationLegCapability[]>([]);
  const [expanded, setExpanded] = useState(false);

  const contiguousTerminalStation = useMemo(() => {
    const bySource = new Map(capabilities.map((capability) => [capability.fromStationId, capability]));
    let destination = fromStationId;
    while (bySource.get(destination)?.supported) destination += 1;
    return destination;
  }, [capabilities, fromStationId]);

  const canStartFromSelectedStation = contiguousTerminalStation > fromStationId;
  const destinationStationIds = useMemo(
    () => stationIds.filter((stationId) => stationId > fromStationId
      && stationId <= contiguousTerminalStation),
    [stationIds, fromStationId, contiguousTerminalStation],
  );
  const canStartSelectedTrip = canStartFromSelectedStation
    && destinationStationIds.includes(toStationId);
  const nextStationLegSupported = Boolean(
    workflow?.control?.currentStationId
    && workflow?.control?.nextTargetStationId
    && capabilities.some((capability) => capability.supported
      && capability.fromStationId === workflow.control?.currentStationId
      && capability.toStationId === workflow.control?.nextTargetStationId),
  );

  useEffect(() => {
    const selected = Number(initialStationId);
    if (Number.isFinite(selected)) setFromStationId(selected);
  }, [initialStationId]);

  useEffect(() => {
    if (!destinationStationIds.length) return;
    if (!destinationStationIds.includes(toStationId)) {
      setToStationId(destinationStationIds[0]);
    }
  }, [destinationStationIds, toStationId]);

  useEffect(() => {
    let active = true;
    getLaboratoryStationLegCapabilities()
      .then((data) => { if (active) setCapabilities(data); })
      .catch((error) => console.debug('Laboratory station-leg capabilities are not ready', error));
    return () => { active = false; };
  }, []);

  const refresh = useCallback(async () => {
    const id = trainId.trim();
    if (!id) return;
    setRefreshing(true);
    try {
      const [deskState, hilState, workflowState] = await Promise.all([
        api<DeskStatus>(`/vehicle/protocol704/status?trainId=${encodeURIComponent(id)}`),
        api<HilStatus>('/hil/status'),
        api<DriverWorkflow>(`/signal/driver-workflow?trainId=${encodeURIComponent(id)}`),
      ]);
      setDesk(deskState);
      setHil(hilState);
      setWorkflow(workflowState);
    } catch (error) {
      console.debug('Laboratory status is not ready', error);
    } finally {
      setRefreshing(false);
    }
  }, [trainId]);

  useEffect(() => {
    if (!expanded) return undefined;
    void refresh();
    const timer = window.setInterval(() => void refresh(), 1000);
    return () => window.clearInterval(timer);
  }, [expanded, refresh]);

  useEffect(() => {
    const activeFrom = workflow?.control?.fromStationId;
    const activeTo = workflow?.control?.toStationId;
    if (activeFrom && activeTo && activeTo > activeFrom) {
      setFromStationId(activeFrom);
      setToStationId(activeTo);
    }
  }, [workflow?.control?.fromStationId, workflow?.control?.toStationId]);

  const start = async () => {
    const id = trainId.trim();
    if (!/^[A-Za-z0-9_-]+$/.test(id)) {
      message.warning('列车编号仅可使用字母、数字、下划线或连字符');
      return;
    }
    if (!canStartSelectedTrip) {
      message.warning('请选择当前起点可连续验证到达的终点站');
      return;
    }
    if (hil?.enabled && hil.trainId && hil.trainId !== id) {
      message.warning(`HIL 当前绑定 ${hil.trainId}，请使用相同列车编号后再测试实体设备`);
      return;
    }
    setStarting(true);
    try {
      // This page owns the laboratory workflow. Starting is idempotent and
      // does not require navigating to the separate dispatch workspace.
      await startSimulation(3600);
      if (!trains.some((train) => train.trainId === id)) {
        await addDispatchTrain({ trainId: id, headLinkId: 1, direction: 'UP', stationId: fromStationId, routePattern: 'FULL' });
      }
      await api('/vehicle/simulation/run', {
        method: 'POST',
        body: JSON.stringify({
          trainId: id,
          fromStationId,
          toStationId,
          hardwareControlEnabled: true,
          labAutoDepartureEnabled: true,
        }),
      });
      await postText(`/vehicle/protocol704/connect?trainId=${encodeURIComponent(id)}`);
      await refresh();
      onCreated();
      message.success(`${id} 已创建，正在连接 704 司机台`);
    } catch (error: any) {
      message.error(error?.message || '实验列车初始化失败');
    } finally {
      setStarting(false);
    }
  };

  const deleteLaboratoryTrain = async () => {
    const id = trainId.trim();
    if (!id) return;
    if (!trains.some((train) => train.trainId === id)) {
      message.warning(`线路信号页中没有列车 ${id}`);
      return;
    }
    setDeleting(true);
    try {
      // The backend delete endpoint resets 704, releases CBI resources and
      // removes MA before deleting the dispatch-side state.
      await removeDispatchTrain(id);
      setDesk(null);
      setWorkflow(null);
      await refresh();
      onCreated();
      message.success(`${id} 已删除，司机台和站间进路已解除`);
    } catch (error: any) {
      message.error(error?.message || `删除 ${id} 失败`);
    } finally {
      setDeleting(false);
    }
  };

  const buildNextStationLeg = async () => {
    const id = trainId.trim();
    const from = workflow?.control?.currentStationId;
    const to = workflow?.control?.nextTargetStationId;
    if (!id || !from || !to || to <= from) {
      message.warning('当前未获得可办理的下一站进路');
      return;
    }
    setRouting(true);
    try {
      const result = await buildLaboratoryStationLeg(id, from, to);
      if (!result.success) throw new Error(result.message);
      await refresh();
      onCreated();
      message.success(`站${from}至站${to}进路已建立: ${(result.routeIds || []).join(' -> ')}`);
    } catch (error: any) {
      message.error(error?.message || '站间进路办理失败');
    } finally {
      setRouting(false);
    }
  };

  const cancelStationLeg = async () => {
    const id = trainId.trim();
    if (!id) return;
    setRouting(true);
    try {
      const result = await cancelLaboratoryStationLeg(id);
      if (!result.success) throw new Error(result.message);
      await refresh();
      onCreated();
      message.success(`已取消站间进路: ${(result.routeIds || []).join(' -> ')}`);
    } catch (error: any) {
      message.error(error?.message || '站间进路取消失败');
    } finally {
      setRouting(false);
    }
  };

  const stationOptions = stationIds.filter((id) => capabilities.some(
    (capability) => capability.fromStationId === id && capability.supported,
  )).map((id) => {
    const station = lineProfile.stations.find((item) => Number(item.id) === id);
    return { value: id, label: `站${id} ${station?.name || ''}` };
  });
  const destinationOptions = destinationStationIds.map((id) => {
    const station = lineProfile.stations.find((item) => Number(item.id) === id);
    return { value: id, label: `站${id} ${station?.name || ''}` };
  });
  const speed = desk?.realtimeVehicleState?.velocityMs ?? 0;
  const position = desk?.realtimeVehicleState?.positionM ?? 0;
  const controlPort = desk?.portStatuses?.['8001'];
  const validFrameAgeMs = desk?.lastValidFrameTime ? Date.now() - desk.lastValidFrameTime : Number.POSITIVE_INFINITY;
  const plcInputContinuous = Boolean(
    controlPort?.connected
    && desk?.receivedValidFrame
    && desk.lastFrameLength === 46
    && validFrameAgeMs <= 5000
    && !desk.staleInputFailSafeTriggered,
  );
  const plcInputLabel = plcInputContinuous
    ? `8001 46B #${controlPort?.frameCount ?? 0} ${controlPort?.lastFrameIntervalMs ?? 0}ms`
    : controlPort?.connected
      ? (desk?.receivedValidFrame ? '8001 输入超时' : '8001 等待 46B')
      : '8001 未连接';

  if (!expanded) {
    return (
      <section className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-amber-500/20 bg-slate-950/60 px-4 py-2">
        <div className="flex items-center gap-2 whitespace-nowrap text-sm font-semibold text-amber-100">
          <LinkOutlined /> 实验室联调 <Tag color="gold">704</Tag>
        </div>
        <span className="text-xs text-slate-400">连接实体司机台时使用，日常本地仿真无需打开</span>
        <Button size="small" icon={<LinkOutlined />} onClick={() => setExpanded(true)}>
          展开704联调
        </Button>
      </section>
    );
  }

  return (
    <section className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-amber-500/20 bg-slate-950/70 px-4 py-2">
      <div className="flex items-center gap-2 whitespace-nowrap text-sm font-semibold text-amber-100"><LinkOutlined /> 实验列车</div>
      <Button type="text" size="small" onClick={() => setExpanded(false)}>收起</Button>
      <Input aria-label="实验列车编号" size="small" value={trainId} disabled={Boolean(desk?.simulationReady)}
        onChange={(event) => setTrainId(event.target.value.toUpperCase())} style={{ width: 88 }} />
      <Select aria-label="实验列车起点站" size="small" value={fromStationId} options={stationOptions}
        disabled={Boolean(desk?.simulationReady)}
        onChange={(stationId) => {
          setFromStationId(stationId);
          setToStationId(stationId + 1);
        }} style={{ width: 150 }} />
      <Select aria-label="实验列车终点站" size="small" value={toStationId}
        options={destinationOptions} disabled={!canStartFromSelectedStation || Boolean(desk?.simulationReady)}
        onChange={setToStationId} style={{ width: 150 }} placeholder="选择终点站" />
      <Button type="primary" size="small" icon={<PlusOutlined />} loading={starting}
        disabled={!canStartSelectedTrip || Boolean(desk?.simulationReady)} onClick={() => void start()} className="app-btn-gold">
        创建并连接司机台
      </Button>
      <Popconfirm
        title={`删除实验列车 ${trainId.trim() || 'LB'}？`}
        description="将断开司机台、取消站间进路并清除移动授权。"
        okText="确认删除"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onConfirm={() => void deleteLaboratoryTrain()}
      >
        <Button size="small" danger icon={<DeleteOutlined />} loading={deleting}
          disabled={!trains.some((train) => train.trainId === trainId.trim())}>
          删除实验列车
        </Button>
      </Popconfirm>
      <Button size="small" loading={routing} disabled={!nextStationLegSupported}
        onClick={() => void buildNextStationLeg()}>
        办理下一站进路
      </Button>
      <Button size="small" icon={<StopOutlined />} loading={routing}
        onClick={() => void cancelStationLeg()}>
        取消站间进路
      </Button>
      <Button size="small" icon={<ReloadOutlined />} loading={refreshing} onClick={() => void refresh()}>刷新设备</Button>
      <div className="ml-auto flex flex-wrap items-center gap-1 text-xs">
        {workflow?.control?.fromStationId && workflow?.control?.toStationId && (
          <Tag color="gold">当前任务 站{workflow.control.fromStationId} → 站{workflow.control.toStationId}</Tag>
        )}
        <Tag color={desk?.simulationReady ? 'green' : 'default'}>仿真 {desk?.simulationReady ? '已准备' : desk?.simulationReadiness || '未准备'}</Tag>
        <Tag color={plcInputContinuous ? 'green' : controlPort?.connected ? 'orange' : 'default'}>
          PLC {plcInputLabel}
        </Tag>
        <Tag color={speed > 0.05 ? 'cyan' : 'default'}>{speed.toFixed(2)} m/s · {position.toFixed(0)} m</Tag>
        {desk?.lastMappedCommand?.command && <Tag color="blue">{desk.lastMappedCommand.command}</Tag>}
        <Tag color={workflow?.atoReady ? 'green' : 'default'}>
          ATO {workflow?.atoReady ? 'READY' : workflow?.atoReadinessBlockingReason || 'WAITING'}
        </Tag>
        {hil?.trainId && hil.trainId !== trainId.trim() && <Tag color="orange">HIL 绑定 {hil.trainId}</Tag>}
        {plcOutputChannel(hil?.plcOutput)}
        {channel('网络屏', hil?.networkScreen)}
        {channel('信号屏', hil?.signalScreen)}
        {channel('视景', hil?.vision, true)}
      </div>
    </section>
  );
}
