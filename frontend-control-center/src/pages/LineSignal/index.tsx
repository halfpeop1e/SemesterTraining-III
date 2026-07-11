import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Button,
  Spin,
  Statistic,
  Row,
  Col,
  message,
  Alert,
  Card,
  Select,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import {
  getLine,
  computeMa,
  getLatestMa,
  getEvents,
  operateSwitch,
  buildRoute,
  cancelRoute,
  openSignal,
  getBuiltRoutes,
  assignRoute,
  unassignRoute,
  getRouteBindings,
  setTsr,
  cancelTsr,
  getTsrs,
  patchSignalAspect,
  patchSwitchState,
  patchRouteBuilt,
} from '../../api/signal';
import { useSimulation } from '../../context/SimulationContext';
import type {
  LineProfile,
  MovingAuthority,
  TrainState,
  SignalEventItem,
  SwitchState,
  SignalAspect,
  TemporarySpeedRestriction,
  EventLevel,
  EventCategory,
} from '../../types/signal';
import type { TrainState as DispatchTrainState } from '../../types/dispatch';
import type { SelectedEntity } from './components/TrackDiagram';
import RealStationDiagram from './components/RealStationDiagram';
import OperationsDock from './components/OperationsDock';
import MaPanel from './components/MaPanel';
import EventLog from './components/EventLog';
import { pickDefaultStationId, sortedStations, stationIdForRoute, stationIdForSignal, nearestStationId } from './data/mileage';

function mapDispatchTrains(src: DispatchTrainState[], simTime: number): TrainState[] {
  return src
    .filter((t) => t.status !== 'FINISHED')
    .map((t) => ({
      trainId: t.trainId,
      positionM: t.positionMeters,
      speedKmh: t.speed,
      accelerationMps2: 0,
      lengthM: t.carCount && t.carLength ? t.carCount * t.carLength : 140,
      direction: t.direction,
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
  const [stationId, setStationId] = useState<string | null>(null);
  const [leftMenu, setLeftMenu] = useState<'route' | 'switch' | 'signal' | 'tsr' | 'status'>('route');
  const [builtRouteIds, setBuiltRouteIds] = useState<Set<number>>(new Set());
  const [routeBindings, setRouteBindings] = useState<Record<string, number>>({});
  const [tsrs, setTsrs] = useState<TemporarySpeedRestriction[]>([]);
  const [lastMessage, setLastMessage] = useState('系统待命');

  const pushLocalEvent = useCallback((msg: string, level: EventLevel = 'INFO') => {
    const item: SignalEventItem = {
      id: `local-${Date.now()}`,
      timestamp: Date.now(),
      level,
      category: 'SIGNAL' as EventCategory,
      message: msg,
    };
    setEvents((prev) => [item, ...prev].slice(0, 100));
    setLastMessage(msg);
  }, []);

  const loadLine = useCallback(async () => {
    try {
      const lp = await getLine();
      setLineProfile(lp);
      setStationId((prev) => prev || pickDefaultStationId(lp));
      return lp;
    } catch (e: any) {
      message.error('加载线路失败: ' + e.message);
      return null;
    }
  }, []);

  const loadBuilt = useCallback(async () => {
    try {
      const list = await getBuiltRoutes();
      setBuiltRouteIds((prev) => {
        if (list.length > 0) {
          return new Set(list.map((r) => Number(r.id)));
        }
        // 远程空：保留本地已建（乐观/mock），避免闪回 0
        return prev.size > 0 ? prev : new Set();
      });
    } catch {
      /* keep */
    }
  }, []);

  const loadBindings = useCallback(async () => {
    try {
      const bindings = await getRouteBindings();
      setRouteBindings(bindings || {});
    } catch {
      /* keep */
    }
  }, []);

  const loadTsrs = useCallback(async () => {
    try {
      const list = await getTsrs();
      setTsrs(list || []);
    } catch {
      /* keep */
    }
  }, []);

  const loadEvents = useCallback(async () => {
    try {
      const remote = await getEvents();
      if (remote?.length) {
        setEvents((prev) => {
          const local = prev.filter((e) => String(e.id).startsWith('local-'));
          return [...local, ...remote].slice(0, 100);
        });
      }
    } catch (e: any) {
      console.error(e);
    }
  }, []);

  useEffect(() => {
    void (async () => {
      await loadLine();
      await loadBuilt();
      await loadBindings();
      await loadTsrs();
      await loadEvents();
    })();
  }, [loadLine, loadBuilt, loadBindings, loadTsrs, loadEvents]);

  const { snapshot, isRunning } = useSimulation();
  const [simTime, setSimTime] = useState(0);
  const [simNotStarted, setSimNotStarted] = useState(false);
  const maInflight = useRef(false);
  const lastMaAt = useRef(0);
  const lastFollowKey = useRef('');

  const contextTrains = useMemo(() => {
    if (!snapshot?.trains?.length) return [] as TrainState[];
    return mapDispatchTrains(snapshot.trains, snapshot.simulationTime ?? 0);
  }, [snapshot]);

  // 快照列车立刻上图（不等 MA 请求）
  useEffect(() => {
    setTrains(contextTrains);
    setSimTime(snapshot?.simulationTime ?? 0);
    setSimNotStarted(!isRunning && contextTrains.length === 0 && (snapshot?.simulationTime ?? 0) <= 1);
  }, [contextTrains, snapshot?.simulationTime, isRunning]);

  // 仿真中：优先跟「速度>0 或位置最靠前」的车切站，避免卡在空窗
  // 用 effectiveRunning：后端已在跑但本地未点启动时也要跟站
  useEffect(() => {
    if (!lineProfile || contextTrains.length === 0) return;
    const simActive =
      isRunning ||
      snapshot?.running === true ||
      (contextTrains.length > 0 && (snapshot?.simulationTime ?? 0) > 1);
    if (!simActive) return;
    const ranked = contextTrains
      .filter((x) => Number.isFinite(x.positionM) && x.direction !== 'INVALID')
      .slice()
      .sort((a, b) => {
        const ma = (b.speedKmh || 0) - (a.speedKmh || 0);
        if (Math.abs(ma) > 0.1) return ma;
        return b.positionM - a.positionM;
      });
    const t = ranked[0];
    if (!t) return;
    const sid = nearestStationId(lineProfile, t.positionM);
    if (!sid) return;
    // 按站切，不绑死 trainId@sid，避免同一站多车不更新
    const key = `${sid}@${Math.round(t.positionM / 50)}`;
    if (lastFollowKey.current === key) return;
    lastFollowKey.current = key;
    setStationId((prev) => (prev === sid ? prev : sid));
  }, [lineProfile, isRunning, contextTrains, snapshot?.running, snapshot?.simulationTime]);

  // 顶栏/绿条用的有效运行态
  const simLive =
    isRunning ||
    snapshot?.running === true ||
    (trains.length > 0 && simTime > 1);

  const recomputeMa = useCallback(async (opts?: { quiet?: boolean }) => {
    if (!lineProfile) return;
    const quiet = opts?.quiet ?? false;
    const now = Date.now();
    // 仿真高频：节流 400ms + 防重入
    if (quiet && (maInflight.current || now - lastMaAt.current < 400)) return;
    maInflight.current = true;
    if (!quiet) setLoading(true);
    try {
      const trainsNow = contextTrains;
      let result: Record<string, MovingAuthority> = {};
      // 仿真运行：优先后端权威 registry（SignalCycle 每 tick 写入）
      if (isRunning || trainsNow.length > 0) {
        try {
          result = await getLatestMa();
        } catch {
          result = {};
        }
      }
      if (Object.keys(result).length === 0 && trainsNow.length > 0) {
        result = await computeMa({
          lineProfile,
          trains: trainsNow,
          nowSec: snapshot?.simulationTime ?? simTime,
        });
      }
      setMaMap(result);
      lastMaAt.current = Date.now();
      if (!quiet) await loadEvents();
    } catch {
      // 不把 trains 清空：车位仍可显示
      if (!quiet) setMaMap({});
    } finally {
      maInflight.current = false;
      if (!quiet) setLoading(false);
    }
  }, [lineProfile, contextTrains, snapshot?.simulationTime, simTime, isRunning, loadEvents]);

  // 线路变化：完整重算
  useEffect(() => {
    void recomputeMa({ quiet: false });
  }, [lineProfile]); // eslint-disable-line react-hooks/exhaustive-deps

  // 仿真快照节流刷新 MA
  useEffect(() => {
    if (!lineProfile) return;
    if (!isRunning && contextTrains.length === 0) {
      setMaMap({});
      return;
    }
    void recomputeMa({ quiet: true });
  }, [snapshot?.simulationTime, contextTrains.length, isRunning, lineProfile]); // eslint-disable-line react-hooks/exhaustive-deps

  const refreshAll = useCallback(async () => {
    setActionLoading(true);
    await loadLine();
    await loadBuilt();
    await loadBindings();
    await loadTsrs();
    await recomputeMa({ quiet: false });
    setActionLoading(false);
  }, [loadLine, loadBuilt, loadBindings, loadTsrs, recomputeMa]);

  const handleBuildRoute = useCallback(
    async (routeId: number, trainId?: string) => {
      setActionLoading(true);
      try {
        const res = await buildRoute({ routeId }, trainId);
        message.success(res.message || `进路 ${routeId} 已办理`);
        pushLocalEvent(res.message || `进路 #${routeId} 办理`, 'INFO');
        setLineProfile((lp) => {
          if (!lp) return lp;
          const next = patchRouteBuilt(lp, routeId, true);
          const jump = stationIdForRoute(next, routeId);
          if (jump) setStationId(jump);
          return next;
        });
        setBuiltRouteIds((prev) => new Set(prev).add(routeId));
        // 绑定结果刷新
        if (trainId) {
          await loadBindings();
        }
        await refreshAll();
      } catch (e: any) {
        message.error(e.message || '办理失败');
        pushLocalEvent(e.message || '办理失败', 'ERROR');
      }
      setActionLoading(false);
    },
    [pushLocalEvent, refreshAll, loadBindings],
  );

  const handleAssignRoute = useCallback(
    async (trainId: string, routeId: number) => {
      setActionLoading(true);
      try {
        const res = await assignRoute(trainId, routeId);
        if (res.success) {
          message.success(res.message || `列车 ${trainId} 已绑定进路 ${routeId}`);
          pushLocalEvent(res.message || `列车 ${trainId} 绑定进路 ${routeId}`, 'INFO');
        } else {
          message.warning(res.message || '绑定失败');
          pushLocalEvent(res.message || `绑定失败: ${trainId} → ${routeId}`, 'WARN');
        }
        await loadBindings();
        await refreshAll();
      } catch (e: any) {
        message.error(e.message || '绑定失败');
        pushLocalEvent(e.message || '绑定失败', 'ERROR');
      }
      setActionLoading(false);
    },
    [pushLocalEvent, refreshAll, loadBindings],
  );

  const handleUnassignRoute = useCallback(
    async (trainId: string) => {
      setActionLoading(true);
      try {
        const res = await unassignRoute(trainId);
        if (res.success) {
          message.success(res.message || `列车 ${trainId} 已解绑`);
          pushLocalEvent(res.message || `列车 ${trainId} 解绑`, 'INFO');
        } else {
          message.warning(res.message || '解绑失败');
        }
        await loadBindings();
        await refreshAll();
      } catch (e: any) {
        message.error(e.message || '解绑失败');
        pushLocalEvent(e.message || '解绑失败', 'ERROR');
      }
      setActionLoading(false);
    },
    [pushLocalEvent, refreshAll, loadBindings],
  );

  const handleSetTsr = useCallback(
    async (startM: number, endM: number, speedLimitKmh: number, active: boolean) => {
      setActionLoading(true);
      try {
        const res = await setTsr({ startM, endM, speedLimitKmh, active });
        if (res.success) {
          message.success(res.message || `限速已设置`);
          pushLocalEvent(res.message || `TSR ${speedLimitKmh}km/h ${startM}-${endM}m`, 'WARN');
        } else {
          message.warning(res.message || '设置限速失败');
          pushLocalEvent(res.message || '设置限速失败', 'WARN');
        }
        await loadTsrs();
        await refreshAll();
      } catch (e: any) {
        message.error(e.message || '设置限速失败');
        pushLocalEvent(e.message || '设置限速失败', 'ERROR');
      }
      setActionLoading(false);
    },
    [pushLocalEvent, refreshAll, loadTsrs],
  );

  const handleCancelTsr = useCallback(
    async (tsrId: string) => {
      setActionLoading(true);
      try {
        const res = await cancelTsr(tsrId);
        if (res.success) {
          message.success(res.message || `限速 ${tsrId} 已取消`);
          pushLocalEvent(res.message || `TSR ${tsrId} 取消`, 'INFO');
        } else {
          message.warning(res.message || '取消限速失败');
        }
        await loadTsrs();
        await refreshAll();
      } catch (e: any) {
        message.error(e.message || '取消限速失败');
        pushLocalEvent(e.message || '取消限速失败', 'ERROR');
      }
      setActionLoading(false);
    },
    [pushLocalEvent, refreshAll, loadTsrs],
  );

  const handleCancelRoute = useCallback(
    async (routeId: number) => {
      setActionLoading(true);
      try {
        const res = await cancelRoute(routeId);
        message.success(res.message || `进路 ${routeId} 已取消`);
        pushLocalEvent(res.message || `进路 #${routeId} 取消`, 'INFO');
        setLineProfile((lp) => (lp ? patchRouteBuilt(lp, routeId, false) : lp));
        setBuiltRouteIds((prev) => {
          const n = new Set(prev);
          n.delete(routeId);
          return n;
        });
        // cancel 会清服务端绑定，必须刷新
        await loadBindings();
        await refreshAll();
      } catch (e: any) {
        message.error(e.message || '取消失败');
        pushLocalEvent(e.message || '取消失败', 'ERROR');
      }
      setActionLoading(false);
    },
    [pushLocalEvent, refreshAll, loadBindings],
  );

  const handleOperateSwitch = useCallback(
    async (switchId: string, position: SwitchState) => {
      setActionLoading(true);
      try {
        const res = await operateSwitch({ switchId, position });
        message.success(res.message || `道岔 ${switchId} → ${position}`);
        pushLocalEvent(res.message || `道岔 ${switchId} → ${position}`, 'INFO');
        setLineProfile((lp) => (lp ? patchSwitchState(lp, switchId, position) : lp));
        await refreshAll();
      } catch (e: any) {
        message.error(e.message || '道岔操作失败');
        pushLocalEvent(e.message || '道岔操作失败', 'ERROR');
      }
      setActionLoading(false);
    },
    [pushLocalEvent, refreshAll],
  );

  const handleSetSignal = useCallback(
    async (signalId: number, aspect: SignalAspect) => {
      setActionLoading(true);
      try {
        const res = await openSignal({ signalId, aspect });
        message.success(res.message || `信号 ${signalId} → ${aspect}`);
        pushLocalEvent(res.message || `信号 #${signalId} → ${aspect}`, 'INFO');
        setLineProfile((lp) => {
          if (!lp) return lp;
          const next = patchSignalAspect(lp, signalId, aspect);
          const jump = stationIdForSignal(next, signalId);
          if (jump) setStationId(jump);
          return next;
        });
        setSelected({ type: 'signal', id: signalId });
        await refreshAll();
      } catch (e: any) {
        message.error(e.message || '信号设置失败');
        pushLocalEvent(e.message || '信号设置失败', 'ERROR');
      }
      setActionLoading(false);
    },
    [pushLocalEvent, refreshAll],
  );

  const handleSelect = useCallback((entity: SelectedEntity | null) => {
    setSelected(entity);
    if (!entity) return;
    if (entity.type === 'switch') setLeftMenu('switch');
    else if (entity.type === 'signal') setLeftMenu('signal');
    else if (entity.type === 'train') setLeftMenu('status');
  }, []);

  const stationOptions = useMemo(() => {
    if (!lineProfile) return [];
    return sortedStations(lineProfile).map((s) => ({
      value: String(s.id),
      label: `${s.name} (${(s.positionM / 1000).toFixed(2)} km)`,
    }));
  }, [lineProfile]);

  const builtRouteSignalIds = useMemo(() => {
    const ids = new Set<number>();
    if (!lineProfile) return ids;
    for (const r of lineProfile.routes || []) {
      if (!builtRouteIds.has(Number(r.id))) continue;
      ids.add(r.startSignalId);
      if (r.endSignalId) ids.add(r.endSignalId);
    }
    return ids;
  }, [lineProfile, builtRouteIds]);

  const hasDegradedMa = useMemo(
    () =>
      Object.values(maMap).some(
        (ma) =>
          ma.event === 'DEGRADED' ||
          ma.event === 'MA_EXPIRED' ||
          ma.event === 'POSITION_LOSS',
      ),
    [maMap],
  );

  const maSummary = useMemo(() => {
    const list = Object.values(maMap);
    if (!list.length) return { count: 0, degraded: 0, avgSpeed: 0 };
    const degraded = list.filter(
      (m) => m.event === 'DEGRADED' || m.event === 'MA_EXPIRED',
    ).length;
    const avgSpeed =
      list.reduce((s, m) => s + (Number.isFinite(m.maxSpeedKmh) ? m.maxSpeedKmh : 0), 0) /
      list.length;
    return { count: list.length, degraded, avgSpeed: Math.round(avgSpeed) };
  }, [maMap]);

  if (!lineProfile) {
    return (
      <div className="flex h-full items-center justify-center">
        <Spin description="加载线路数据..." />
      </div>
    );
  }

  const activeStationId = stationId || pickDefaultStationId(lineProfile);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-700/40 px-4 py-2">
        <div className="flex flex-wrap items-center gap-3">
          <h3 className="m-0 text-base text-slate-100">线路信号 · 联锁上位机</h3>
          <span className="font-mono text-[11px] text-slate-500">
            {lineProfile.lineId || 'line'} · {simLive ? '运行' : '停止'} · t=
            {Math.floor(simTime)}s · 车 {trains.length} · 已建 {builtRouteIds.size} · MA降级{' '}
            {maSummary.degraded}
          </span>
          <Select
            size="small"
            style={{ width: 160 }}
            value={activeStationId || undefined}
            options={stationOptions}
            onChange={(v) => setStationId(v)}
            placeholder="选择车站"
          />
        </div>
        <div className="flex gap-2">
          <Button
            type="primary"
            size="small"
            icon={<ReloadOutlined />}
            loading={loading || actionLoading}
            onClick={() => void refreshAll()}
          >
            刷新 line/MA
          </Button>
        </div>
      </div>

      {hasDegradedMa && (
        <Alert
          type="error"
          banner
          showIcon
          message="MA 降级告警"
          description="存在列车 MA 降级或过期，要求制动（fail-safe 收紧）"
        />
      )}

      {simNotStarted && trains.length === 0 && !simLive && (
        <Alert
          type="info"
          banner
          showIcon
          message="仿真未启动"
          description="请到「调度控制」点启动。启动后本页自动跟车、刷新 MA 与占用。"
        />
      )}
      {simLive && (
        <Alert
          type="success"
          banner
          showIcon
          message={`仿真运行中 · t=${Math.floor(simTime)}s · 在线车 ${trains.length} · MA ${maSummary.count}`}
        />
      )}

      <div className="px-4 py-2">
        <Row gutter={[12, 12]}>
          <Col xs={12} sm={6}>
            <Card size="small" variant="borderless">
              <Statistic title="MA 列车" value={maSummary.count} suffix="列" />
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card size="small" variant="borderless">
              <Statistic
                title="真进路/已建"
                value={(lineProfile.routes || []).length}
                suffix={`/ ${builtRouteIds.size}`}
              />
            </Card>
          </Col>
        </Row>
      </div>

      <div className="flex min-h-0 flex-1 gap-3 px-4 pb-4">
          <div className="w-[380px] shrink-0 min-h-0">
            <OperationsDock
              lineProfile={lineProfile}
              builtRouteIds={builtRouteIds}
              routeBindings={routeBindings}
              trains={trains}
              tsrs={tsrs}
              activeMenu={leftMenu}
              onMenuChange={setLeftMenu}
              loading={actionLoading || loading}
              lastMessage={lastMessage}
              onBuildRoute={(id, tid) => void handleBuildRoute(id, tid)}
              onCancelRoute={(id) => void handleCancelRoute(id)}
              onAssignRoute={(tid, rid) => void handleAssignRoute(tid, rid)}
              onUnassignRoute={(tid) => void handleUnassignRoute(tid)}
              onOperateSwitch={(id, pos) => void handleOperateSwitch(id, pos)}
              onSetSignal={(id, asp) => void handleSetSignal(id, asp)}
              onSetTsr={(s, e, sp, a) => void handleSetTsr(s, e, sp, a)}
              onCancelTsr={(id) => void handleCancelTsr(id)}
              onRefresh={() => void refreshAll()}
            />
          </div>
          <div className="flex min-w-0 min-h-0 flex-1 flex-col gap-2">
            <div className="min-h-0 flex-1">
              {activeStationId ? (
                <RealStationDiagram
                  lineProfile={lineProfile}
                  stationId={activeStationId}
                  trains={trains}
                  maMap={maMap}
                  selectedEntity={selected}
                  onSelect={handleSelect}
                  builtRouteSignalIds={builtRouteSignalIds}
                />
              ) : null}
            </div>
            <div className="h-24 shrink-0 overflow-hidden rounded-xl border border-slate-700/50">
              <EventLog events={events} loading={loading} />
            </div>
          </div>
          <div className="hidden w-56 shrink-0 overflow-y-auto xl:block">
            <Card size="small" title="MA 授权" className="h-full">
              {Object.keys(maMap).length === 0 ? (
                <div className="text-xs text-slate-500">暂无 MA（启动仿真后刷新）</div>
              ) : (
                <div className="space-y-2">
                  {Object.values(maMap).map((ma) => {
                    const bad =
                      ma.event === 'DEGRADED' ||
                      ma.event === 'MA_EXPIRED' ||
                      ma.event === 'POSITION_LOSS';
                    return (
                      <div
                        key={ma.trainId}
                        className={`cursor-pointer rounded border px-2 py-1.5 text-xs ${
                          bad
                            ? 'border-red-500/50 bg-red-500/10'
                            : 'border-slate-700 bg-slate-800/40'
                        }`}
                        onClick={() => handleSelect({ type: 'train', id: ma.trainId })}
                      >
                        <div className="font-medium text-slate-100">{ma.trainId}</div>
                        <div className="text-slate-400">
                          EoA{' '}
                          {Number.isFinite(ma.endOfAuthorityM)
                            ? ma.endOfAuthorityM.toFixed(0)
                            : '—'}{' '}
                          m
                        </div>
                        <div className="text-slate-400">
                          {ma.maxSpeedKmh} km/h · {ma.event}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>
          </div>
        </div>

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
