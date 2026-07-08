import { useState, useEffect, useCallback, useMemo } from 'react';
import { Button, Spin, Statistic, Row, Col, message, Alert, Card, Segmented, Select } from 'antd';
import { ReloadOutlined, GlobalOutlined, AimOutlined } from '@ant-design/icons';
import {
  getLine,
  computeMa,
  getEvents,
  operateSwitch,
  buildRoute,
  cancelRoute,
  openSignal,
  setTsr,
  cancelTsr,
} from '../../api/signal';
import { useSimulation } from '../../context/SimulationContext';
import type { LineProfile, MovingAuthority, TrainState, SwitchState, SignalEventItem } from '../../types/signal';
import type { TrainState as DispatchTrainState } from '../../types/dispatch';
import TrackDiagram, { type SelectedEntity } from './components/TrackDiagram';
import MaPanel from './components/MaPanel';
import ControlPanel from './components/ControlPanel';
import EventLog from './components/EventLog';
import Legend from './components/Legend';

// ===== 降级测试用列车（NaN 位置，用于验证 fail-safe 收紧） =====
const DEGRADED_TRAIN: TrainState = {
  trainId: 'T_BAD', positionM: NaN, speedKmh: 0, accelerationMps2: 0, lengthM: 140, direction: 'INVALID', timestamp: 100,
};

// 调度仿真列车 → 信号模块列车（喂给后端 compute(...) 算真实 MA）
function mapDispatchTrains(src: DispatchTrainState[], simTime: number): TrainState[] {
  return src
    .filter((t) => t.status !== 'FINISHED')
    .map((t) => ({
      trainId: t.trainId,
      positionM: t.positionMeters,
      speedKmh: t.speed,
      accelerationMps2: 0,
      lengthM: t.carCount && t.carLength ? t.carCount * t.carLength : 140,
      direction: 'UP',
      timestamp: simTime,
    }));
}

function LineSignal() {
  const [lineProfile, setLineProfile] = useState<LineProfile | null>(null);
  const [maMap, setMaMap] = useState<Record<string, MovingAuthority>>({});
  const [trains, setTrains] = useState<TrainState[]>([]);
  const [events, setEvents] = useState<SignalEventItem[]>([]);
  const [selected, setSelected] = useState<SelectedEntity | null>(null);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showDegraded, setShowDegraded] = useState(false);
  const [viewMode, setViewMode] = useState<'overview' | 'station'>('overview');
  const [selectedStationId, setSelectedStationId] = useState<string | null>(null);

  const loadLine = useCallback(async () => {
    try {
      setLineProfile(await getLine());
    } catch (e: any) {
      setError(e.message);
      message.error('加载线路数据失败: ' + e.message);
    }
  }, []);

  const loadEvents = useCallback(async () => {
    try {
      setEvents(await getEvents());
    } catch (e: any) {
      console.error(e);
    }
  }, []);

  // 首次加载
  useEffect(() => {
    loadLine();
    loadEvents();
  }, [loadLine, loadEvents]);

  // 默认选中第一个车站作为站场详图
  useEffect(() => {
    if (lineProfile && !selectedStationId) {
      const first = lineProfile.stations.find(s => s.positionM > 0);
      if (first) setSelectedStationId(first.id);
    }
  }, [lineProfile, selectedStationId]);

  // 从共享 SimulationProvider 读取实时仿真快照（被动只读，不控制仿真）
  const { snapshot } = useSimulation();
  const [simTime, setSimTime] = useState(0);
  const [simNotStarted, setSimNotStarted] = useState(false);

  // 调度快照列车 → 信号模块列车（含降级注入），喂给后端 compute(...) 算真实 MA
  const contextTrains = useMemo(() => {
    if (!snapshot) return [];
    let mapped = mapDispatchTrains(snapshot.trains, snapshot.simulationTime);
    if (showDegraded) mapped = [...mapped, DEGRADED_TRAIN];
    return mapped;
  }, [snapshot, showDegraded]);

  // 快照/降级变化即重算 MA（仿真在调度页推进，这里随快照实时刷新）
  const recomputeMa = useCallback(async () => {
    if (!lineProfile) return;
    setLoading(true);
    try {
      setTrains(contextTrains);
      setSimTime(snapshot?.simulationTime ?? 0);
      setSimNotStarted(contextTrains.length === 0);
      const result = await computeMa({ lineProfile, trains: contextTrains });
      setMaMap(result);
      await loadEvents();
    } catch (e: any) {
      setTrains([]);
      setMaMap({});
      setSimNotStarted(true);
    }
    setLoading(false);
  }, [lineProfile, loadEvents, contextTrains, snapshot]);

  useEffect(() => {
    recomputeMa();
  }, [recomputeMa]);

  // 控制操作
  const handleOperateSwitch = useCallback(async (switchId: string, position: SwitchState) => {
    setActionLoading(true);
    try {
      const res = await operateSwitch({ switchId, position });
      message.success(res.message);
      await loadLine();
      await loadEvents();
    } catch (e: any) {
      message.error(e.message);
    }
    setActionLoading(false);
  }, [loadLine, loadEvents]);

  const handleBuildRoute = useCallback(async (startSignalId: number, endSignalId: number) => {
    setActionLoading(true);
    try {
      const res = await buildRoute({ startSignalId, endSignalId });
      message.success(res.message);
      await loadLine();
      await loadEvents();
    } catch (e: any) {
      message.error(e.message);
    }
    setActionLoading(false);
  }, [loadLine, loadEvents]);

  const handleCancelRoute = useCallback(async (routeId: string) => {
    setActionLoading(true);
    try {
      const res = await cancelRoute(routeId);
      message.success(res.message);
      await loadLine();
      await loadEvents();
    } catch (e: any) {
      message.error(e.message);
    }
    setActionLoading(false);
  }, [loadLine, loadEvents]);

  const handleOpenSignal = useCallback(async (signalId: number) => {
    setActionLoading(true);
    try {
      const res = await openSignal({ signalId });
      message.success(res.message);
      await loadLine();
      await loadEvents();
    } catch (e: any) {
      message.error(e.message);
    }
    setActionLoading(false);
  }, [loadLine, loadEvents]);

  const handleSetTsr = useCallback(async (startM: number, endM: number, speedLimitKmh: number) => {
    setActionLoading(true);
    try {
      const res = await setTsr({ startM, endM, speedLimitKmh, active: true });
      message.success(res.message);
      await loadLine();
      await loadEvents();
    } catch (e: any) {
      message.error(e.message);
    }
    setActionLoading(false);
  }, [loadLine, loadEvents]);

  const handleCancelTsr = useCallback(async (tsrId: string) => {
    setActionLoading(true);
    try {
      const res = await cancelTsr(tsrId);
      message.success(res.message);
      await loadLine();
      await loadEvents();
    } catch (e: any) {
      message.error(e.message);
    }
    setActionLoading(false);
  }, [loadLine, loadEvents]);

  const stationOptions = useMemo(() => {
    if (!lineProfile) return [];
    return lineProfile.stations
      .filter(s => s.positionM > 0)
      .sort((a, b) => a.positionM - b.positionM)
      .map(s => ({ label: s.name, value: s.id }));
  }, [lineProfile]);

  const activeStationName = useMemo(() => {
    return lineProfile?.stations.find(s => s.id === selectedStationId)?.name || '';
  }, [lineProfile, selectedStationId]);

  const hasDegradedMa = useMemo(() => Object.values(maMap).some(ma =>
    ma.event === 'DEGRADED' || ma.event === 'MA_EXPIRED' || ma.event === 'POSITION_LOSS'
  ), [maMap]);

  if (!lineProfile) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
        <Spin tip="加载线路数据..." />
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 16px', borderBottom: '1px solid rgba(148,163,184,0.08)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <h3 style={{ margin: 0, fontSize: 16, color: '#f1f5f9' }}>
            线路信号 - {viewMode === 'overview' ? '全线概览' : `${activeStationName} 站场详图`}
          </h3>
          <span style={{ fontSize: 11, color: '#64748b', fontFamily: 'JetBrains Mono, monospace', paddingLeft: 4 }}>
            仿真 {Math.floor(simTime)}s
          </span>
          <Segmented
            size="small"
            value={viewMode}
            onChange={(v) => setViewMode(v as 'overview' | 'station')}
            options={[
              { label: <span><GlobalOutlined /> 全线概览</span>, value: 'overview' },
              { label: <span><AimOutlined /> 站场详图</span>, value: 'station' },
            ]}
          />
          {viewMode === 'station' && (
            <Select
              size="small"
              value={selectedStationId}
              onChange={(v) => { setSelectedStationId(v); setSelected(null); }}
              options={stationOptions}
              style={{ width: 140 }}
              placeholder="选择车站"
            />
          )}
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <Button
            size="small"
            onClick={() => setShowDegraded((v) => !v)}
            type={showDegraded ? 'primary' : 'default'}
            danger={showDegraded}
          >
            {showDegraded ? '降级测试ON' : '降级测试OFF'}
          </Button>
          <Button
            type="primary"
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={recomputeMa}
            size="small"
          >
            刷新 MA
          </Button>
        </div>
      </div>

      {/* 降级告警条 */}
      {hasDegradedMa && (
        <Alert
          type="error"
          message="MA 降级告警"
          description="存在列车 MA 降级或过期，要求制动（fail-safe 收紧）"
          showIcon
          banner
        />
      )}

      {/* 仿真未启动提示（线路信号为只读视图） */}
      {simNotStarted && trains.length === 0 && (
        <Alert
          type="info"
          message="仿真未启动"
          description="线路信号页为只读视图，列车数据来自调度控制页的仿真。请先到「调度控制」页点击「启动」。"
          showIcon
          banner
        />
      )}

      {/* 统计栏 */}
      <div style={{ padding: '12px 16px' }}>
        <Row gutter={[16, 16]}>
          <Col xs={12} sm={6}>
            <Card bordered={false} bodyStyle={{ padding: '16px 20px' }}>
              <Statistic title="站点" value={lineProfile.stations?.length || 0} suffix="个" valueStyle={{ color: '#f1f5f9', fontSize: 24, fontWeight: 700 }} />
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card bordered={false} bodyStyle={{ padding: '16px 20px' }}>
              <Statistic title="信号机" value={lineProfile.signals?.length || 0} suffix="个" valueStyle={{ color: '#f1f5f9', fontSize: 24, fontWeight: 700 }} />
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card bordered={false} bodyStyle={{ padding: '16px 20px' }}>
              <Statistic title="道岔" value={lineProfile.switches?.length || 0} suffix="个" valueStyle={{ color: '#f1f5f9', fontSize: 24, fontWeight: 700 }} />
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card bordered={false} bodyStyle={{ padding: '16px 20px' }}>
              <Statistic title="总里程" value={(lineProfile.totalLengthM / 1000).toFixed(2)} suffix="km" valueStyle={{ color: '#f1f5f9', fontSize: 24, fontWeight: 700 }} />
            </Card>
          </Col>
        </Row>
      </div>

      {/* 主区：平面图 + 控制/事件 */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', gap: 12, padding: '0 16px 16px' }}>
        <div style={{ flex: 1, minWidth: 0, position: 'relative', borderRadius: 12, overflow: 'hidden', border: '1px solid rgba(148,163,184,0.08)' }}>
          <TrackDiagram
            lineProfile={lineProfile}
            maMap={maMap}
            trains={trains}
            selectedEntity={selected}
            onSelect={(entity) => {
              setSelected(entity);
              if (entity?.type === 'station' && viewMode === 'overview') {
                setViewMode('station');
                setSelectedStationId(String(entity.id));
              }
            }}
            mode={viewMode}
            stationId={selectedStationId}
          />
          <Legend />
        </div>
        <div style={{ width: 320, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ flexShrink: 0 }}>
            <ControlPanel
              lineProfile={lineProfile}
              selectedEntity={selected}
              loading={actionLoading}
              onOperateSwitch={handleOperateSwitch}
              onBuildRoute={handleBuildRoute}
              onCancelRoute={handleCancelRoute}
              onOpenSignal={handleOpenSignal}
              onSetTsr={handleSetTsr}
              onCancelTsr={handleCancelTsr}
            />
          </div>
          <div style={{ flex: 1, minHeight: 0 }}>
            <EventLog events={events} loading={loading} />
          </div>
        </div>
      </div>

      {/* 详情面板 */}
      <MaPanel
        entity={selected}
        lineProfile={lineProfile}
        maMap={maMap}
        trains={trains}
        onClose={() => setSelected(null)}
      />
    </div>
  );
}

export default LineSignal;
