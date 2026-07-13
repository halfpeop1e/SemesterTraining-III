import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Input, Select, Tag, message } from 'antd';
import { LinkOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { addDispatchTrain } from '../../../api/dispatch';
import type { LineProfile, TrainState } from '../../../types/signal';

type ChannelStatus = {
  enabled?: boolean;
  connected?: boolean;
  framesSent?: number;
};

type HilStatus = {
  enabled?: boolean;
  trainId?: string;
  signalScreen?: ChannelStatus;
  networkScreen?: ChannelStatus;
  vision?: ChannelStatus;
};

type DeskStatus = {
  connected?: boolean;
  receivedValidFrame?: boolean;
  simulationReady?: boolean;
  simulationReadiness?: string;
  lastMappedCommand?: { command?: string };
  realtimeVehicleState?: { velocityMs?: number; positionM?: number };
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

function channel(name: string, value?: ChannelStatus) {
  if (!value?.enabled) return <Tag>{name}: 未启用</Tag>;
  if (value.connected) return <Tag color="green">{name}: 已发送 {value.framesSent ?? 0}</Tag>;
  return <Tag color="red">{name}: 未连接</Tag>;
}

export default function LabTrainQuickStart({ lineProfile, trains, initialStationId, onCreated }: Props) {
  const stationIds = useMemo(
    () => lineProfile.stations.map((station) => Number(station.id)).sort((a, b) => a - b),
    [lineProfile.stations],
  );
  const firstStation = stationIds[0] ?? 1;
  const terminalStation = stationIds[stationIds.length - 1] ?? 13;
  const [trainId, setTrainId] = useState('LB');
  const [fromStationId, setFromStationId] = useState(firstStation);
  const [starting, setStarting] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [desk, setDesk] = useState<DeskStatus | null>(null);
  const [hil, setHil] = useState<HilStatus | null>(null);

  useEffect(() => {
    const selected = Number(initialStationId);
    if (Number.isFinite(selected) && selected < terminalStation) setFromStationId(selected);
  }, [initialStationId, terminalStation]);

  const refresh = useCallback(async () => {
    const id = trainId.trim();
    if (!id) return;
    setRefreshing(true);
    try {
      const [deskState, hilState] = await Promise.all([
        api<DeskStatus>(`/vehicle/protocol704/status?trainId=${encodeURIComponent(id)}`),
        api<HilStatus>('/hil/status'),
      ]);
      setDesk(deskState);
      setHil(hilState);
    } catch (error) {
      console.debug('Laboratory status is not ready', error);
    } finally {
      setRefreshing(false);
    }
  }, [trainId]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), 1000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const start = async () => {
    const id = trainId.trim();
    if (!/^[A-Za-z0-9_-]+$/.test(id)) {
      message.warning('列车编号仅可使用字母、数字、下划线或连字符');
      return;
    }
    if (hil?.enabled && hil.trainId && hil.trainId !== id) {
      message.warning(`HIL 当前绑定 ${hil.trainId}，请使用相同列车编号后再测试实体设备`);
      return;
    }
    setStarting(true);
    try {
      if (!trains.some((train) => train.trainId === id)) {
        await addDispatchTrain({ trainId: id, headLinkId: 1, direction: 'UP', stationId: fromStationId, routePattern: 'FULL' });
      }
      await api('/vehicle/simulation/run', {
        method: 'POST',
        body: JSON.stringify({
          trainId: id,
          fromStationId,
          toStationId: terminalStation,
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

  const stationOptions = stationIds.filter((id) => id < terminalStation).map((id) => {
    const station = lineProfile.stations.find((item) => Number(item.id) === id);
    return { value: id, label: `站${id} ${station?.name || ''}` };
  });
  const speed = desk?.realtimeVehicleState?.velocityMs ?? 0;
  const position = desk?.realtimeVehicleState?.positionM ?? 0;

  return (
    <section className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-amber-500/20 bg-slate-950/70 px-4 py-2">
      <div className="flex items-center gap-2 whitespace-nowrap text-sm font-semibold text-amber-100"><LinkOutlined /> 实验列车</div>
      <Input aria-label="实验列车编号" size="small" value={trainId}
        onChange={(event) => setTrainId(event.target.value.toUpperCase())} style={{ width: 88 }} />
      <Select aria-label="实验列车起点站" size="small" value={fromStationId} options={stationOptions}
        onChange={setFromStationId} style={{ width: 150 }} />
      <span className="text-xs text-slate-500">至 站{terminalStation}</span>
      <Button type="primary" size="small" icon={<PlusOutlined />} loading={starting} onClick={() => void start()} className="app-btn-gold">
        创建并连接司机台
      </Button>
      <Button size="small" icon={<ReloadOutlined />} loading={refreshing} onClick={() => void refresh()}>刷新设备</Button>
      <div className="ml-auto flex flex-wrap items-center gap-1 text-xs">
        <Tag color={desk?.simulationReady ? 'green' : 'default'}>仿真 {desk?.simulationReady ? '已准备' : desk?.simulationReadiness || '未准备'}</Tag>
        <Tag color={desk?.connected ? 'green' : 'default'}>PLC {desk?.connected ? (desk?.receivedValidFrame ? '已接帧' : '已连接，等输入') : '未连接'}</Tag>
        <Tag color={speed > 0.05 ? 'cyan' : 'default'}>{speed.toFixed(2)} m/s · {position.toFixed(0)} m</Tag>
        {desk?.lastMappedCommand?.command && <Tag color="blue">{desk.lastMappedCommand.command}</Tag>}
        {hil?.trainId && hil.trainId !== trainId.trim() && <Tag color="orange">HIL 绑定 {hil.trainId}</Tag>}
        {channel('网络屏', hil?.networkScreen)}
        {channel('信号屏', hil?.signalScreen)}
        {channel('视景', hil?.vision)}
      </div>
    </section>
  );
}
