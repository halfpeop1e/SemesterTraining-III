import { useState, useMemo, useEffect } from 'react';
import { Card, Select, Button, InputNumber, Tabs, Tag, message, Space } from 'antd';
import type { LineProfile, SwitchState } from '../../../types/signal';
import type { SelectedEntity } from './TrackDiagram';

interface ControlPanelProps {
  lineProfile: LineProfile;
  selectedEntity: SelectedEntity | null;
  loading: boolean;
  onOperateSwitch: (switchId: string, position: SwitchState) => void;
  onBuildRoute: (routeId: number) => void;
  onCancelRoute: (routeId: string) => void;
  onOpenSignal: (signalId: number) => void;
  onSetTsr: (startM: number, endM: number, speedLimitKmh: number, active: boolean) => void;
  onCancelTsr: (tsrId: string) => void;
}

export default function ControlPanel({
  lineProfile,
  selectedEntity,
  loading,
  onOperateSwitch,
  onBuildRoute,
  onCancelRoute,
  onOpenSignal,
  onSetTsr,
  onCancelTsr,
}: ControlPanelProps) {
  const [activeTab, setActiveTab] = useState('switch');
  const [selectedSwitch, setSelectedSwitch] = useState<string | undefined>(undefined);
  const [startSignal, setStartSignal] = useState<number | undefined>(undefined);
  const [endSignal, setEndSignal] = useState<number | undefined>(undefined);
  const [selectedSignal, setSelectedSignal] = useState<number | undefined>(undefined);
  const [tsrStart, setTsrStart] = useState<number | undefined>(undefined);
  const [tsrEnd, setTsrEnd] = useState<number | undefined>(undefined);
  const [tsrSpeed, setTsrSpeed] = useState<number | undefined>(40);

  const switchOptions = useMemo(() => lineProfile.switches.map((s) => ({ label: `道岔 ${s.id} (${s.state || '未接入'})`, value: String(s.id) })), [lineProfile.switches]);
  const signalOptions = useMemo(() => lineProfile.signals.map((s) => ({ label: `${s.name} (#${s.id})`, value: s.id })), [lineProfile.signals]);

  useEffect(() => {
    if (selectedEntity?.type === 'switch') {
      setActiveTab('switch');
      setSelectedSwitch(String(selectedEntity.id));
    } else if (selectedEntity?.type === 'signal') {
      setActiveTab('signal');
      setSelectedSignal(Number(selectedEntity.id));
    }
  }, [selectedEntity]);

  const currentSwitch = useMemo(() => lineProfile.switches.find((s) => s.id === selectedSwitch), [lineProfile.switches, selectedSwitch]);

  const handleOperate = (position: SwitchState) => {
    if (!selectedSwitch) { message.warning('请先选择道岔'); return; }
    onOperateSwitch(selectedSwitch, position);
  };

  const handleBuildRoute = () => {
    if (!startSignal || !endSignal) { message.warning('请选择始端和终端信号机'); return; }
    if (startSignal === endSignal) { message.warning('始端与终端不能相同'); return; }
    // 旧面板：按始终端找真 routeId
    const hit = lineProfile.routes.find(
      (r) => r.startSignalId === startSignal && r.endSignalId === endSignal,
    );
    if (!hit) {
      message.warning('无匹配进路（请用左侧联锁操作台按 routeId 办理）');
      return;
    }
    onBuildRoute(Number(hit.id));
  };

  const handleOpenSignal = () => {
    if (selectedSignal === undefined) { message.warning('请选择信号机'); return; }
    onOpenSignal(selectedSignal);
  };

  const handleSetTsr = () => {
    if (tsrStart === undefined || tsrEnd === undefined || !tsrSpeed) { message.warning('请填写完整限速参数'); return; }
    if (tsrEnd <= tsrStart) { message.warning('终点必须大于起点'); return; }
    onSetTsr(tsrStart, tsrEnd, tsrSpeed, true);
  };

  const switchTab = (
    <Space orientation="vertical" className="w-full">
      <Select placeholder="选择道岔" options={switchOptions} value={selectedSwitch} onChange={setSelectedSwitch} className="w-full" size="small" />
      {currentSwitch && (
        <div className="text-xs text-slate-400">当前状态：
          <Tag color={currentSwitch.state === 'NORMAL' ? 'blue' : currentSwitch.state === 'REVERSE' ? 'orange' : 'default'}>
            {currentSwitch.state || '未接入'}
          </Tag>
        </div>
      )}
      <div className="flex gap-2">
        <Button size="small" type="primary" onClick={() => handleOperate('NORMAL')} loading={loading} disabled={!selectedSwitch}>定位</Button>
        <Button size="small" danger onClick={() => handleOperate('REVERSE')} loading={loading} disabled={!selectedSwitch}>反位</Button>
      </div>
    </Space>
  );

  const routeTab = (
    <Space orientation="vertical" className="w-full">
      <Select placeholder="始端信号机" options={signalOptions} value={startSignal} onChange={setStartSignal} className="w-full" size="small" />
      <Select placeholder="终端信号机" options={signalOptions} value={endSignal} onChange={setEndSignal} className="w-full" size="small" />
      <Button size="small" type="primary" onClick={handleBuildRoute} loading={loading} disabled={!startSignal || !endSignal}>办理进路</Button>
      <div className="text-xs text-slate-500 font-semibold mt-1">已办理进路</div>
      <div className="max-h-24 overflow-y-auto space-y-1">
        {lineProfile.routes.length === 0 ? <div className="text-xs text-slate-600">暂无</div> : lineProfile.routes.map((r) => (
          <div key={r.id} className="flex items-center justify-between text-xs bg-slate-800/40 rounded px-2 py-1">
            <span className="text-slate-300">{r.id}</span>
            <Button size="small" type="link" danger onClick={() => onCancelRoute(String(r.id))}>取消</Button>
          </div>
        ))}
      </div>
    </Space>
  );

  const signalTab = (
    <Space orientation="vertical" className="w-full">
      <Select placeholder="选择信号机" options={signalOptions} value={selectedSignal} onChange={setSelectedSignal} className="w-full" size="small" />
      <Button size="small" type="primary" onClick={handleOpenSignal} loading={loading} disabled={selectedSignal === undefined}>重开绿灯</Button>
    </Space>
  );

  const tsrTab = (
    <Space orientation="vertical" className="w-full">
      <div className="flex gap-2">
        <InputNumber placeholder="起点 m" size="small" value={tsrStart} onChange={(v) => setTsrStart(v ?? undefined)} className="w-full" />
        <InputNumber placeholder="终点 m" size="small" value={tsrEnd} onChange={(v) => setTsrEnd(v ?? undefined)} className="w-full" />
      </div>
      <InputNumber placeholder="限速 km/h" size="small" value={tsrSpeed} onChange={(v) => setTsrSpeed(v ?? undefined)} className="w-full" />
      <Button size="small" type="primary" onClick={handleSetTsr} loading={loading} disabled={tsrStart === undefined || tsrEnd === undefined || !tsrSpeed}>设置限速</Button>
      <div className="text-xs text-slate-500 font-semibold mt-1">已生效限速</div>
      <div className="max-h-24 overflow-y-auto space-y-1">
        {lineProfile.tsrs.filter(t => t.active !== false).length === 0 ? <div className="text-xs text-slate-600">暂无</div> : lineProfile.tsrs.filter(t => t.active !== false).map((t) => (
          <div key={t.id} className="flex items-center justify-between text-xs bg-slate-800/40 rounded px-2 py-1">
            <span className="text-slate-300">{t.speedLimitKmh}km/h ({t.startM}-{t.endM}m)</span>
            <Button size="small" type="link" danger onClick={() => t.id && onCancelTsr(t.id)}>取消</Button>
          </div>
        ))}
      </div>
    </Space>
  );

  return (
    <Card size="small" title="控制面板" className="h-full" styles={{ body: { padding: 12 } }}>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        size="small"
        items={[
          { key: 'switch', label: '道岔', children: switchTab },
          { key: 'route', label: '进路', children: routeTab },
          { key: 'signal', label: '信号', children: signalTab },
          { key: 'tsr', label: '限速', children: tsrTab },
        ]}
      />
    </Card>
  );
}
