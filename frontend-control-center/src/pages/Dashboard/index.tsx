import { useEffect, useState, useMemo } from 'react';
import { Card, Row, Col, Statistic, List, Tag, Button, Space, Spin, Empty } from 'antd';
import { AimOutlined, NodeIndexOutlined, ThunderboltOutlined, WarningOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { getStatus, getEvents } from '../../api/signal';
import type { SignalStatus, SignalEventItem } from '../../types/signal';

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

const HEALTH_COLOR: Record<SignalStatus['health'], string> = {
  HEALTHY: '#22c55e',
  DEGRADED: '#f59e0b',
  FAULT: '#ef4444',
};

const HEALTH_LABEL: Record<SignalStatus['health'], string> = {
  HEALTHY: '健康',
  DEGRADED: '降级',
  FAULT: '故障',
};

const fmtTime = (s: number) => {
  const t = Math.max(0, s) % 86400;
  return [Math.floor(t / 3600), Math.floor((t % 3600) / 60), t % 60]
    .map((n) => String(n).padStart(2, '0')).join(':');
};

export default function Dashboard() {
  const [status, setStatus] = useState<SignalStatus | null>(null);
  const [events, setEvents] = useState<SignalEventItem[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const [s, e] = await Promise.all([getStatus(), getEvents()]);
      setStatus(s);
      setEvents(e.slice(0, 5));
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    const id = setInterval(load, 5000);
    return () => clearInterval(id);
  }, []);

  const quickCards = useMemo(() => [
    { title: '线路信号', icon: <AimOutlined />, desc: '信号平面图、MA、道岔控制', color: '#3b82f6', key: 'linesignal' },
    { title: '调度控制', icon: <NodeIndexOutlined />, desc: '列车运行图、到站时刻', color: '#06d6a0', key: 'dispatch' },
    { title: '能源评估', icon: <ThunderboltOutlined />, desc: '能耗、功率、安全事件', color: '#f59e0b', key: 'energy' },
  ], []);

  if (loading && !status) {
    return (
      <div className="h-full flex items-center justify-center">
        <Spin tip="加载总控状态..." />
      </div>
    );
  }

  return (
    <div className="p-6 space-y-5">
      <div>
        <h2 className="text-[17px] font-semibold text-slate-100 m-0">系统仪表盘</h2>
        <p className="text-sm text-slate-500 mt-1 m-0">全局运行状态总览，实时监控列车位置、信号事件与系统健康度</p>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={12} sm={6}>
          <Card bordered={false} bodyStyle={{ padding: '20px 24px' }}>
            <Statistic
              title={<span className="text-slate-400 text-xs">在线列车</span>}
              value={status?.onlineTrains ?? 0}
              suffix="列"
              valueStyle={{ color: '#38bdf8', fontSize: 28, fontWeight: 700, fontFamily: "'JetBrains Mono', monospace" }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card bordered={false} bodyStyle={{ padding: '20px 24px' }}>
            <Statistic
              title={<span className="text-slate-400 text-xs">当前告警</span>}
              value={status?.alertCount ?? 0}
              suffix="条"
              valueStyle={{ color: (status?.alertCount ?? 0) > 0 ? '#ef4444' : '#22c55e', fontSize: 28, fontWeight: 700, fontFamily: "'JetBrains Mono', monospace" }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card bordered={false} bodyStyle={{ padding: '20px 24px' }}>
            <div className="text-xs text-slate-400 mb-2">系统健康度</div>
            <div className="flex items-center gap-3">
              <div className="w-12 h-12 rounded-full flex items-center justify-center text-sm font-bold"
                style={{ background: `${HEALTH_COLOR[status?.health ?? 'HEALTHY']}18`, color: HEALTH_COLOR[status?.health ?? 'HEALTHY'] }}>
                {HEALTH_LABEL[status?.health ?? 'HEALTHY']}
              </div>
              <div className="text-xs text-slate-500">
                {(status?.health ?? 'HEALTHY') === 'HEALTHY' ? '所有子系统运行正常' : '存在需要关注的信号事件'}
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card bordered={false} bodyStyle={{ padding: '20px 24px' }}>
            <Statistic
              title={<span className="text-slate-400 text-xs">仿真时间</span>}
              value={fmtTime(status?.simulationTime ?? 0)}
              valueStyle={{ color: '#f1f5f9', fontSize: 26, fontWeight: 700, fontFamily: "'JetBrains Mono', monospace" }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card bordered={false} title="最近信号事件" extra={<Button size="small" onClick={load}>刷新</Button>} bodyStyle={{ padding: 0 }}>
            {events.length === 0 ? (
              <div className="p-6">
                <Empty description="暂无信号事件" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              </div>
            ) : (
              <List
                size="small"
                dataSource={events}
                renderItem={(item) => (
                  <List.Item className="px-5 py-3 !border-b-slate-800/40">
                    <div className="w-full">
                      <div className="flex items-center gap-2 mb-1">
                        <Tag color={LEVEL_COLOR[item.level]} className="text-[10px] leading-4 px-1">{item.level}</Tag>
                        <Tag className="text-[10px] leading-4 px-1">{CATEGORY_LABEL[item.category]}</Tag>
                        <span className="text-[11px] text-slate-500 ml-auto">{new Date(item.timestamp).toLocaleTimeString('zh-CN')}</span>
                      </div>
                      <div className="text-sm text-slate-200">{item.message}</div>
                    </div>
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card bordered={false} title="快捷入口" bodyStyle={{ padding: '20px 24px' }}>
            <Space direction="vertical" className="w-full">
              {quickCards.map((c) => (
                <Button
                  key={c.key}
                  block
                  size="large"
                  icon={c.icon}
                  style={{ borderColor: 'rgba(148,163,184,0.1)', color: c.color, textAlign: 'left', height: 64 }}
                >
                  <div className="text-left leading-tight">
                    <div className="text-sm font-semibold" style={{ color: c.color }}>{c.title}</div>
                    <div className="text-[11px] text-slate-500 font-normal">{c.desc}</div>
                  </div>
                </Button>
              ))}
            </Space>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
