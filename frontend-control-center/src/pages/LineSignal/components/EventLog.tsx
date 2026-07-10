import { Card, List, Tag, Empty } from 'antd';
import type { SignalEventItem } from '../../../types/signal';

interface EventLogProps {
  events: SignalEventItem[];
  loading?: boolean;
}

const LEVEL_COLOR: Record<SignalEventItem['level'], string> = {
  INFO: 'blue',
  WARN: 'orange',
  ERROR: 'red',
};

const CATEGORY_LABEL: Record<SignalEventItem['category'], string> = {
  MA: 'MA',
  SWITCH: '道岔',
  SIGNAL: '信号',
  TSR: '限速',
  TRAIN: '列车',
  SYSTEM: '系统',
};

export default function EventLog({ events, loading }: EventLogProps) {
  return (
    <Card size="small" title="实时事件" className="h-full" styles={{ body: { padding: 0 } }}>
      {events.length === 0 ? (
        <div className="p-4"><Empty description="暂无事件" image={Empty.PRESENTED_IMAGE_SIMPLE} /></div>
      ) : (
        <List
          size="small"
          loading={loading}
          dataSource={events.slice(0, 50)}
          renderItem={(item) => (
            <List.Item className="px-3 py-2 !border-b-slate-800/40">
              <div className="w-full">
                <div className="flex items-center gap-2 mb-0.5">
                  <Tag color={LEVEL_COLOR[item.level]} className="text-[10px] leading-4 px-1">{item.level}</Tag>
                  <Tag className="text-[10px] leading-4 px-1">{CATEGORY_LABEL[item.category]}</Tag>
                  <span className="text-[10px] text-slate-500 ml-auto">{new Date(item.timestamp).toLocaleTimeString('zh-CN')}</span>
                </div>
                <div className="text-xs text-slate-300">{item.message}</div>
              </div>
            </List.Item>
          )}
          style={{ maxHeight: 240, overflow: 'auto' }}
        />
      )}
    </Card>
  );
}
