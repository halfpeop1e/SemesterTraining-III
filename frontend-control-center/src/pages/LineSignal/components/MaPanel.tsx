import { Button, Descriptions, Drawer, Popconfirm, Tag } from 'antd';
import type { SelectedEntity } from './TrackDiagram';
import type { LineProfile, MovingAuthority, TrainState, SignalAspect, SignalEvent, AuthorityBasis } from '../../../types/signal';

interface MaPanelProps {
  entity: SelectedEntity | null;
  lineProfile: LineProfile;
  maMap: Record<string, MovingAuthority>;
  trains: TrainState[];
  deletingTrainId?: string | null;
  onDeleteTrain?: (trainId: string) => void;
  onClose: () => void;
}

const BASIS_LABEL: Record<AuthorityBasis, string> = {
  LINE_LIMIT: '线路限速',
  TSR: '临时限速',
  PRECEDING_TRAIN: '前车追踪',
  SWITCH: '道岔',
  TURNBACK_END: '折返点',
  SIGNAL: '信号机',
  ROUTE_END: '进路终点',
  OVERLAP_END: '保护区段',
  AXLE_OCCUPIED: '计轴占用',
};

const EVENT_TAG: Record<SignalEvent, { color: string; text: string }> = {
  NONE: { color: 'green', text: '正常' },
  PRECEDING_OCCUPATION: { color: 'blue', text: '前车占用' },
  SPEED_RESTRICTION: { color: 'blue', text: '限速' },
  SWITCH_ABNORMAL: { color: 'red', text: '道岔异常' },
  MA_EXPIRED: { color: 'red', text: 'MA过期' },
  DEGRADED: { color: 'red', text: '降级' },
  SIGNAL_BOUNDARY: { color: 'blue', text: '信号边界' },
  ROUTE_BLOCKED: { color: 'red', text: '进路阻塞' },
  AXLE_OCCUPIED: { color: 'blue', text: '计轴占用' },
  POSITION_LOSS: { color: 'red', text: '定位丢失' },
};

const ASPECT_LABEL: Record<SignalAspect, string> = {
  GREEN: '绿灯(放行)', RED: '红灯(停车)', YELLOW: '黄灯',
  RED_YELLOW: '红黄灯', YELLOW_DARK: '黄灭', RED_DARK: '红灭',
  GREEN_DARK: '绿灭', WHITE: '白灯', BLUE: '蓝灯',
  RED_BROKEN: '红断', GREEN_BROKEN: '绿断', YELLOW_BROKEN: '黄断', WHITE_BROKEN: '白断',
};

export default function MaPanel({
  entity,
  lineProfile,
  maMap,
  trains,
  deletingTrainId,
  onDeleteTrain,
  onClose,
}: MaPanelProps) {
  const open = entity !== null;

  const renderContent = () => {
    if (!entity) return null;

    if (entity.type === 'train') {
      const train = trains.find(t => t.trainId === entity.id);
      const ma = maMap[entity.id as string];
      if (!train) return <p>未找到列车</p>;
      return (
        <>
          <Descriptions title="列车状态" column={1} size="small" bordered>
            <Descriptions.Item label="列车ID">{train.trainId}</Descriptions.Item>
            <Descriptions.Item label="车头里程">{(train.positionM / 1000).toFixed(3)} km</Descriptions.Item>
            <Descriptions.Item label="当前速度">{train.speedKmh.toFixed(1)} km/h</Descriptions.Item>
            <Descriptions.Item label="方向">{train.direction}</Descriptions.Item>
            <Descriptions.Item label="列车长度">{train.lengthM.toFixed(1)} m</Descriptions.Item>
          </Descriptions>
          {ma && (
            <Descriptions title="移动授权 MA" column={1} size="small" bordered style={{ marginTop: 16 }}>
              <Descriptions.Item label="授权终点">{(ma.endOfAuthorityM / 1000).toFixed(3)} km</Descriptions.Item>
              <Descriptions.Item label="最大速度">{ma.maxSpeedKmh.toFixed(1)} km/h</Descriptions.Item>
              <Descriptions.Item label="约束来源">{BASIS_LABEL[ma.basis] || ma.basis}</Descriptions.Item>
              <Descriptions.Item label="事件状态">
                <Tag color={EVENT_TAG[ma.event]?.color || 'default'}>
                  {EVENT_TAG[ma.event]?.text || ma.event}
                </Tag>
              </Descriptions.Item>
              {ma.capSignalId !== null && (
                <Descriptions.Item label="截断信号机">#{ma.capSignalId}</Descriptions.Item>
              )}
            </Descriptions>
          )}
          {onDeleteTrain && (
            <div className="mt-5 border-t border-slate-200 pt-4">
              <Popconfirm
                title={`删除列车 ${train.trainId}？`}
                description="将解除进路、清除线路占用、MA 和 704 司机台连接。"
                okText="确认删除"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                onConfirm={() => onDeleteTrain(train.trainId)}
              >
                <Button danger block loading={deletingTrainId === train.trainId}>
                  删除车辆并解除线路占用
                </Button>
              </Popconfirm>
            </div>
          )}
        </>
      );
    }

    if (entity.type === 'signal') {
      const sig = lineProfile.signals?.find(s => s.id === entity.id);
      if (!sig) return <p>未找到信号机</p>;
      const aspectText = sig.aspect ? ASPECT_LABEL[sig.aspect] : '未接入(灰)';
      const isProceed = sig.aspect === 'GREEN';
      return (
        <Descriptions title="信号机详情" column={1} size="small" bordered>
          <Descriptions.Item label="编号">{sig.name} (#{sig.id})</Descriptions.Item>
          <Descriptions.Item label="类型">{sig.type === 1 ? '入站' : sig.type === 2 ? '出站' : '中间'}</Descriptions.Item>
          <Descriptions.Item label="显示状态">
            <Tag color={isProceed ? 'green' : 'red'}>{aspectText}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="所属区段">Seg #{sig.segId}</Descriptions.Item>
        </Descriptions>
      );
    }

    if (entity.type === 'switch') {
      const sw = lineProfile.switches?.find(s => s.id === entity.id);
      if (!sw) return <p>未找到道岔</p>;
      return (
        <Descriptions title="道岔详情" column={1} size="small" bordered>
          <Descriptions.Item label="编号">{sw.id}</Descriptions.Item>
          <Descriptions.Item label="中心里程">{(sw.positionM / 1000).toFixed(3)} km</Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={sw.state === 'NORMAL' ? 'blue' : sw.state === 'REVERSE' ? 'orange' : 'default'}>
              {sw.state || '未接入'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="侧向限速">{sw.divergingSpeedLimitKmh.toFixed(0)} km/h</Descriptions.Item>
        </Descriptions>
      );
    }

    if (entity.type === 'station') {
      const st = lineProfile.stations?.find(s => s.id === entity.id);
      if (!st) return <p>未找到站点</p>;
      return (
        <Descriptions title="站点详情" column={1} size="small" bordered>
          <Descriptions.Item label="站名">{st.name}</Descriptions.Item>
          <Descriptions.Item label="中心里程">{(st.positionM / 1000).toFixed(3)} km</Descriptions.Item>
          <Descriptions.Item label="终点站">{st.isTerminal ? '是' : '否'}</Descriptions.Item>
          <Descriptions.Item label="站台长度">{st.platformLengthM.toFixed(0)} m</Descriptions.Item>
        </Descriptions>
      );
    }

    return null;
  };

  return (
    <Drawer
      title="详情面板"
      placement="right"
      open={open}
      onClose={onClose}
      size="default"
    >
      {renderContent()}
    </Drawer>
  );
}
