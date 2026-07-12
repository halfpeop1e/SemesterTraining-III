import { useMemo, useState } from 'react';
import { Button, Input, InputNumber, Modal, Popconfirm, Select, Space, message } from 'antd';
import { DeleteOutlined, PlusOutlined, SwapOutlined } from '@ant-design/icons';
import {
  addDispatchTrain,
  clearDispatchTrains,
  removeDispatchTrain,
  setDispatchTrainRoutePattern,
} from '../../../api/dispatch';
import type { TrainState } from '../../../types/signal';

interface Props {
  stationId: string;
  trains: TrainState[];
  onChanged: () => void;
}

const ROUTES = [
  { value: 'FULL', label: '全程交路 · 郭公庄 ⇆ 国家图书馆' },
  { value: 'SHORT_S', label: '南段小交路 · 郭公庄 ⇆ 六里桥' },
  { value: 'SHORT_N', label: '北段小交路 · 六里桥 ⇆ 国家图书馆' },
  { value: 'EXPRESS', label: '快车交路' },
];

export default function StationTrainOperations({ stationId, trains, onChanged }: Props) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [trainId, setTrainId] = useState('T1');
  const [linkId, setLinkId] = useState(1);
  const [direction, setDirection] = useState<'UP' | 'DOWN'>('UP');
  const [routePattern, setRoutePattern] = useState('FULL');
  const [selectedTrain, setSelectedTrain] = useState<string>();
  const trainOptions = useMemo(
    () => trains.map((train) => ({ value: train.trainId, label: train.trainId })),
    [trains],
  );

  const run = async (action: () => Promise<unknown>, success: string): Promise<boolean> => {
    setBusy(true);
    try {
      await action();
      message.success(success);
      onChanged();
      return true;
    } catch (error: any) {
      message.error(error?.message || '操作失败');
      return false;
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Space size={6} wrap>
        <Button size="small" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          加车
        </Button>
        <Select
          size="small"
          style={{ width: 96 }}
          placeholder="选择列车"
          options={trainOptions}
          value={selectedTrain}
          onChange={setSelectedTrain}
        />
        <Select
          size="small"
          style={{ width: 150 }}
          options={ROUTES}
          value={routePattern}
          onChange={setRoutePattern}
        />
        <Button
          size="small"
          icon={<SwapOutlined />}
          disabled={!selectedTrain}
          loading={busy}
          onClick={() => selectedTrain && void run(
            () => setDispatchTrainRoutePattern(selectedTrain, routePattern),
            `${selectedTrain} 交路已更新`,
          )}
        >
          设置交路
        </Button>
        <Popconfirm
          title="删除所选列车？"
          onConfirm={() => selectedTrain && void run(
            () => removeDispatchTrain(selectedTrain),
            `${selectedTrain} 已删除`,
          )}
        >
          <Button size="small" danger icon={<DeleteOutlined />} disabled={!selectedTrain}>删车</Button>
        </Popconfirm>
        <Popconfirm
          title="清空全部列车？"
          description="同时清除车辆占用和 MA 状态。"
          onConfirm={() => void run(clearDispatchTrains, '全部列车已清空')}
        >
          <Button size="small" danger disabled={!trains.length}>清车</Button>
        </Popconfirm>
      </Space>

      <Modal
        title="添加仿真列车"
        open={open}
        okText="确认加车"
        cancelText="取消"
        confirmLoading={busy}
        onCancel={() => setOpen(false)}
        onOk={() => void (async () => {
          const added = await run(
            () => addDispatchTrain({
              trainId,
              headLinkId: linkId,
              direction,
              stationId: Number(stationId),
              routePattern: routePattern as 'FULL' | 'SHORT_N' | 'SHORT_S' | 'EXPRESS',
            }),
            `${trainId} 已加入站场`,
          );
          if (added) setOpen(false);
        })()}
      >
        <div className="grid grid-cols-[96px_1fr] items-center gap-3 py-3 text-sm">
          <span className="text-slate-400">列车编号</span>
          <Input value={trainId} onChange={(event) => setTrainId(event.target.value)} />
          <span className="text-slate-400">车头 LK_ID</span>
          <InputNumber min={1} value={linkId} onChange={(value) => setLinkId(value || 1)} className="w-full" />
          <span className="text-slate-400">运行方向</span>
          <Select value={direction} onChange={setDirection} options={[
            { value: 'UP', label: '上行' },
            { value: 'DOWN', label: '下行' },
          ]} />
          <span className="text-slate-400">初始交路</span>
          <Select value={routePattern} onChange={setRoutePattern} options={ROUTES} />
        </div>
      </Modal>
    </>
  );
}
