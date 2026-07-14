import { useEffect, useMemo, useState } from 'react';
import { Button, Popconfirm, Select, Tag, message } from 'antd';
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { addDispatchTrain, removeDispatchTrain, startSimulation } from '../../../api/dispatch';
import type { LineProfile, TrainState } from '../../../types/signal';

interface Props {
  lineProfile: LineProfile;
  trains: TrainState[];
  initialStationId: string;
  onChanged: () => void;
}

function stationName(lineProfile: LineProfile, stationId: number) {
  const station = lineProfile.stations.find((item) => Number(item.id) === stationId);
  return station ? `站${stationId} ${station.name}` : `站${stationId}`;
}

const issuedLocalTrainIds = new Set<string>();

function findNextLocalTrainId(existingIds: string[]) {
  const occupied = new Set(existingIds);
  let sequence = 1;
  while (true) {
    const candidate = `LOCAL-${String(sequence).padStart(3, '0')}`;
    if (!occupied.has(candidate) && !issuedLocalTrainIds.has(candidate)) {
      return candidate;
    }
    sequence += 1;
  }
}

export default function LocalTrainOperations({ lineProfile, trains, initialStationId, onChanged }: Props) {
  const stationIds = useMemo(
    () => lineProfile.stations.map((station) => Number(station.id)).filter(Number.isFinite).sort((a, b) => a - b),
    [lineProfile.stations],
  );
  const firstStationId = stationIds[0] ?? 1;
  const lastStationId = stationIds[stationIds.length - 1] ?? firstStationId;
  const [direction, setDirection] = useState<'UP' | 'DOWN'>('UP');
  const [fromStationId, setFromStationId] = useState(firstStationId);
  const [destinationStationId, setDestinationStationId] = useState(firstStationId + 1);
  const [selectedTrainId, setSelectedTrainId] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const nextTrainId = useMemo(
    () => findNextLocalTrainId(trains.map((train) => train.trainId)),
    [trains],
  );

  const originStationIds = useMemo(
    () => stationIds.filter((stationId) => direction === 'UP'
      ? stationId < lastStationId
      : stationId > firstStationId),
    [direction, firstStationId, lastStationId, stationIds],
  );
  const destinationStationIds = useMemo(
    () => stationIds.filter((stationId) => direction === 'UP'
      ? stationId > fromStationId
      : stationId < fromStationId),
    [direction, fromStationId, stationIds],
  );

  useEffect(() => {
    const selected = Number(initialStationId);
    if (originStationIds.includes(selected)) setFromStationId(selected);
  }, [initialStationId, originStationIds]);

  useEffect(() => {
    if (!originStationIds.includes(fromStationId)) {
      setFromStationId(originStationIds[0] ?? firstStationId);
    }
  }, [firstStationId, fromStationId, originStationIds]);

  useEffect(() => {
    if (!destinationStationIds.includes(destinationStationId)) {
      setDestinationStationId(destinationStationIds[0] ?? fromStationId);
    }
  }, [destinationStationId, destinationStationIds, fromStationId]);

  const createTrain = async () => {
    if (!destinationStationIds.includes(destinationStationId)) {
      message.warning('终点站必须位于列车运行方向的前方');
      return;
    }

    const id = findNextLocalTrainId(trains.map((train) => train.trainId));
    issuedLocalTrainIds.add(id);
    setCreating(true);
    try {
      await startSimulation(3600);
      await addDispatchTrain({
        trainId: id,
        headLinkId: 1,
        direction,
        stationId: fromStationId,
        destinationStationId,
        routePattern: 'FULL',
      });
      setSelectedTrainId(id);
      onChanged();
      message.success(`${id} 已由信号系统创建: ${stationName(lineProfile, fromStationId)} → ${stationName(lineProfile, destinationStationId)}`);
    } catch (error: any) {
      message.error(error?.message || '本地列车创建失败');
    } finally {
      setCreating(false);
    }
  };

  const deleteTrain = async () => {
    if (!selectedTrainId) return;
    setDeleting(true);
    try {
      await removeDispatchTrain(selectedTrainId);
      message.success(`${selectedTrainId} 已下线，线路占用、MA 和进路绑定已清除`);
      setSelectedTrainId(undefined);
      onChanged();
    } catch (error: any) {
      message.error(error?.message || '列车删除失败');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <section className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-emerald-500/20 bg-slate-950/70 px-4 py-2">
      <div className="flex items-center gap-2 whitespace-nowrap text-sm font-semibold text-emerald-100">
        <PlusOutlined /> 添加本地列车
      </div>
      <Tag color="cyan" aria-label="自动生成的本地列车编号">下次编号 {nextTrainId}</Tag>
      <Select aria-label="本地列车运行方向" size="small" value={direction}
        onChange={(value) => setDirection(value)} style={{ width: 96 }}
        options={[{ value: 'UP', label: '上行' }, { value: 'DOWN', label: '下行' }]} />
      <Select aria-label="本地列车起点站" size="small" value={fromStationId}
        onChange={setFromStationId} style={{ width: 150 }}
        options={originStationIds.map((stationId) => ({ value: stationId, label: stationName(lineProfile, stationId) }))} />
      <Select aria-label="本地列车终点站" size="small" value={destinationStationId}
        onChange={setDestinationStationId} style={{ width: 150 }}
        options={destinationStationIds.map((stationId) => ({ value: stationId, label: stationName(lineProfile, stationId) }))} />
      <Button type="primary" size="small" icon={<PlusOutlined />} loading={creating}
        disabled={destinationStationIds.length === 0} onClick={() => void createTrain()}>
        添加本地列车
      </Button>
      <Select aria-label="已创建本地列车" size="small" placeholder="选择列车" value={selectedTrainId}
        onChange={setSelectedTrainId} style={{ width: 130 }}
        options={trains.map((train) => ({ value: train.trainId, label: train.trainId }))} />
      <Popconfirm
        title={`删除列车 ${selectedTrainId || ''}？`}
        description="将清除列车占用、移动授权与进路绑定。"
        okText="确认删除"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        onConfirm={() => void deleteTrain()}
      >
        <Button size="small" danger icon={<DeleteOutlined />} loading={deleting} disabled={!selectedTrainId}>
          删除列车
        </Button>
      </Popconfirm>
      <Button size="small" icon={<ReloadOutlined />} onClick={onChanged}>刷新状态</Button>
      <div className="ml-auto flex items-center gap-1 text-xs">
        <Tag color="green">信号系统本地仿真</Tag>
        <Tag>{trains.length} 列车在线</Tag>
      </div>
    </section>
  );
}
