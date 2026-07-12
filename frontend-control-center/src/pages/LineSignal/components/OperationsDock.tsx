import { useMemo, useState } from 'react';
import { Button, Input, Space, Tag, Select, InputNumber, message } from 'antd';
import type {
  LineProfile,
  Route,
  SwitchState,
  SignalAspect,
  TemporarySpeedRestriction,
} from '../../../types/signal';

type MenuId = 'route' | 'switch' | 'signal' | 'tsr' | 'status';

interface Props {
  lineProfile: LineProfile;
  builtRouteIds: Set<number>;
  /** trainId → routeId 绑定关系 */
  routeBindings: Record<string, number>;
  /** 在线列车列表（用于选择绑定车） */
  trains: { trainId: string; positionM: number; speedKmh: number; direction: string }[];
  /** TSR 列表 */
  tsrs: TemporarySpeedRestriction[];
  activeMenu: MenuId;
  onMenuChange: (m: MenuId) => void;
  loading?: boolean;
  lastMessage?: string;
  onBuildRoute: (routeId: number, trainId?: string) => void;
  onCancelRoute: (routeId: number) => void;
  onAssignRoute: (trainId: string, routeId: number) => void;
  onUnassignRoute: (trainId: string) => void;
  onOperateSwitch: (switchId: string, position: SwitchState) => void;
  onSetSignal: (signalId: number, aspect: SignalAspect) => void;
  onSetTsr: (startM: number, endM: number, speedLimitKmh: number, active: boolean) => void;
  onCancelTsr: (tsrId: string) => void;
  onRefresh: () => void;
  /** top = 顶部横条布局；默认侧栏竖排 */
  layout?: 'side' | 'top';
}

const MENUS: { id: MenuId; label: string }[] = [
  { id: 'route', label: '进路办理' },
  { id: 'switch', label: '道岔控制' },
  { id: 'signal', label: '信号机' },
  { id: 'tsr', label: '临时限速' },
  { id: 'status', label: '状态' },
];

function routeNumericId(r: Route): number {
  return Number(r.id);
}

export default function OperationsDock({
  lineProfile,
  builtRouteIds,
  routeBindings,
  trains,
  tsrs,
  activeMenu,
  onMenuChange,
  loading,
  lastMessage,
  onBuildRoute,
  onCancelRoute,
  onAssignRoute,
  onUnassignRoute,
  onOperateSwitch,
  onSetSignal,
  onSetTsr,
  onCancelTsr,
  onRefresh,
  layout = 'side',
}: Props) {
  const [routeFilter, setRouteFilter] = useState('');
  const [selectedRouteId, setSelectedRouteId] = useState<number | undefined>();
  const [selectedSwitch, setSelectedSwitch] = useState<string | undefined>();
  const [selectedSignal, setSelectedSignal] = useState<number | undefined>();
  const [selectedTrainId, setSelectedTrainId] = useState<string | undefined>();
  const [tsrStart, setTsrStart] = useState<number | undefined>();
  const [tsrEnd, setTsrEnd] = useState<number | undefined>();
  const [tsrSpeed, setTsrSpeed] = useState<number | undefined>(40);

  const signalName = useMemo(() => {
    const m = new Map<number, string>();
    for (const s of lineProfile.signals || []) {
      m.set(s.id, s.name || String(s.id));
    }
    return m;
  }, [lineProfile.signals]);

  const routes = useMemo(() => {
    const list = (lineProfile.routes || []).slice();
    list.sort((a, b) => routeNumericId(a) - routeNumericId(b));
    const q = routeFilter.trim().toLowerCase();
    if (!q) return list;
    return list.filter((r) => {
      const name = (r.name || '').toLowerCase();
      const id = String(r.id);
      const sName = (signalName.get(r.startSignalId) || '').toLowerCase();
      const eName = (signalName.get(r.endSignalId) || '').toLowerCase();
      return name.includes(q) || id.includes(q) || sName.includes(q) || eName.includes(q);
    });
  }, [lineProfile.routes, routeFilter, signalName]);

  const routeOptions = useMemo(
    () =>
      routes.slice(0, 300).map((r) => ({
        value: routeNumericId(r),
        label: `${r.name || r.id}  (#${r.id}  ${signalName.get(r.startSignalId) || r.startSignalId}→${signalName.get(r.endSignalId) || r.endSignalId})`,
      })),
    [routes, signalName],
  );

  const switchOptions = useMemo(
    () =>
      (lineProfile.switches || []).map((s) => ({
        value: String(s.id),
        label: `道岔 ${s.id} (${s.state || '未接入'})`,
      })),
    [lineProfile.switches],
  );

  const signalOptions = useMemo(
    () =>
      (lineProfile.signals || []).map((s) => ({
        value: s.id,
        label: `${s.name || s.id} (#${s.id}) ${s.aspect || '—'}`,
      })),
    [lineProfile.signals],
  );

  const currentSwitch = useMemo(
    () => lineProfile.switches.find((s) => String(s.id) === selectedSwitch),
    [lineProfile.switches, selectedSwitch],
  );

  const builtList = useMemo(
    () => (lineProfile.routes || []).filter((r) => builtRouteIds.has(routeNumericId(r))),
    [lineProfile.routes, builtRouteIds],
  );

  const trainOptions = useMemo(
    () =>
      trains.map((t) => ({
        value: t.trainId,
        label: `${t.trainId} (${(t.positionM / 1000).toFixed(2)} km, ${t.speedKmh.toFixed(0)} km/h)`,
      })),
    [trains],
  );

  // routeId → trainId 反向查找
  const routeToTrain = useMemo(() => {
    const m = new Map<number, string>();
    for (const [tid, rid] of Object.entries(routeBindings)) {
      m.set(Number(rid), tid);
    }
    return m;
  }, [routeBindings]);

  return (
    <section
      className={
        layout === 'top'
          ? 'ops-dock-top flex max-h-[220px] min-h-0 flex-col overflow-hidden rounded-lg border border-amber-500/25 bg-slate-950/90 p-2'
          : 'flex h-full min-h-0 flex-col overflow-hidden rounded-xl border border-slate-700/60 bg-slate-900/50 p-3'
      }
    >
      <div className="mb-2 flex flex-wrap gap-1.5">
        {MENUS.map((m) => (
          <button
            key={m.id}
            type="button"
            onClick={() => onMenuChange(m.id)}
            className={
              activeMenu === m.id
                ? 'rounded-md border px-3 py-1 text-xs font-semibold'
                : 'rounded-md border px-3 py-1 text-xs font-medium'
            }
            style={
              activeMenu === m.id
                ? {
                    borderColor: 'rgba(212,175,55,0.55)',
                    background: 'rgba(212,175,55,0.16)',
                    color: '#F5E6B8',
                  }
                : {
                    borderColor: 'rgba(148,163,184,0.25)',
                    background: 'rgba(15,23,42,0.6)',
                    color: '#94a3b8',
                  }
            }
          >
            {m.label}
          </button>
        ))}
      </div>

      <div
        className={
          layout === 'top'
            ? 'min-h-0 flex-1 overflow-y-auto pr-1 text-sm text-slate-300'
            : 'min-h-0 flex-1 overflow-y-auto pr-1 text-sm text-slate-300'
        }
      >
        {activeMenu === 'route' && (
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="rounded-lg border border-slate-700 p-2">
                <div className="text-slate-500">线路进路</div>
                <div className="mt-1 font-medium text-slate-100">
                  {(lineProfile.routes || []).length} 条
                </div>
              </div>
              <div className="rounded-lg border border-slate-700 p-2">
                <div className="text-slate-500">已建立</div>
                <div className="mt-1 font-medium text-slate-100">{builtRouteIds.size} 条</div>
              </div>
            </div>

            <Input
              size="small"
              allowClear
              placeholder="筛选进路名/信号机 如 XQ1 Z5"
              value={routeFilter}
              onChange={(e) => setRouteFilter(e.target.value)}
            />

            <Select
              className="w-full"
              size="small"
              showSearch
              optionFilterProp="label"
              placeholder="选择进路办理"
              options={routeOptions}
              value={selectedRouteId}
              onChange={setSelectedRouteId}
            />

            <Select
              className="w-full"
              size="small"
              allowClear
              placeholder="选择列车绑定（可选，不选则仅建进路）"
              options={trainOptions}
              value={selectedTrainId}
              onChange={(v) => setSelectedTrainId(v || undefined)}
            />

            <Space wrap>
              <Button
                type="primary"
                size="small"
                loading={loading}
                disabled={selectedRouteId == null}
                onClick={() => {
                  if (selectedRouteId == null) {
                    message.warning('请选择进路');
                    return;
                  }
                  onBuildRoute(selectedRouteId, selectedTrainId);
                }}
              >
                {selectedTrainId ? '建进路并绑定' : '仅建进路'}
              </Button>
            </Space>

            <div>
              <div className="mb-1 text-xs font-semibold text-slate-400">已建立进路</div>
              {builtList.length === 0 ? (
                <div className="text-xs text-slate-600">暂无</div>
              ) : (
                <div className="max-h-40 space-y-1 overflow-y-auto">
                  {builtList.map((r) => {
                    const rid = routeNumericId(r);
                    const boundTrain = routeToTrain.get(rid);
                    return (
                      <div
                        key={String(r.id)}
                        className="flex flex-col gap-1 rounded bg-slate-800/60 px-2 py-1 text-xs"
                      >
                        <div className="flex items-center justify-between">
                          <span className="text-slate-200">
                            {r.name || r.id}{' '}
                            <Tag color="blue" className="text-[10px]">
                              #{r.id}
                            </Tag>
                          </span>
                          <Button
                            size="small"
                            type="link"
                            danger
                            onClick={() => onCancelRoute(rid)}
                          >
                            取消
                          </Button>
                        </div>
                        <div className="flex items-center gap-1">
                          {boundTrain ? (
                            <>
                              <Tag color="green" className="text-[10px]">
                                {boundTrain}
                              </Tag>
                              <Button
                                size="small"
                                type="link"
                                onClick={() => onUnassignRoute(boundTrain)}
                              >
                                解绑
                              </Button>
                            </>
                          ) : (
                            <>
                              <span className="text-slate-500">未绑定</span>
                              <Select
                                size="small"
                                style={{ width: 100 }}
                                placeholder="绑定车"
                                options={trainOptions}
                                onChange={(v) => onAssignRoute(String(v), rid)}
                                showSearch
                                optionFilterProp="label"
                              />
                            </>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        )}

        {activeMenu === 'switch' && (
          <div className="space-y-3">
            <div className="text-xs text-slate-500">
              道岔 {(lineProfile.switches || []).length} 组（真 line-profile）
            </div>
            <Select
              className="w-full"
              size="small"
              showSearch
              optionFilterProp="label"
              placeholder="选择道岔"
              options={switchOptions}
              value={selectedSwitch}
              onChange={setSelectedSwitch}
            />
            {currentSwitch && (
              <div className="text-xs text-slate-400">
                当前：
                <Tag
                  color={
                    currentSwitch.state === 'NORMAL'
                      ? 'blue'
                      : currentSwitch.state === 'REVERSE'
                        ? 'orange'
                        : 'default'
                  }
                >
                  {currentSwitch.state || '未接入'}
                </Tag>
                侧向限速 {currentSwitch.divergingSpeedLimitKmh ?? '—'} km/h
              </div>
            )}
            <Space>
              <Button
                size="small"
                type="primary"
                loading={loading}
                disabled={!selectedSwitch}
                onClick={() => selectedSwitch && onOperateSwitch(selectedSwitch, 'NORMAL')}
              >
                定位
              </Button>
              <Button
                size="small"
                danger
                loading={loading}
                disabled={!selectedSwitch}
                onClick={() => selectedSwitch && onOperateSwitch(selectedSwitch, 'REVERSE')}
              >
                反位
              </Button>
            </Space>
            <div className="max-h-48 space-y-1 overflow-y-auto">
              {(lineProfile.switches || []).slice(0, 40).map((sw) => (
                <div
                  key={String(sw.id)}
                  className="flex cursor-pointer items-center justify-between rounded bg-slate-800/40 px-2 py-1 text-xs hover:bg-slate-700/40"
                  onClick={() => setSelectedSwitch(String(sw.id))}
                >
                  <span>#{sw.id}</span>
                  <Tag
                    color={
                      sw.state === 'NORMAL' ? 'blue' : sw.state === 'REVERSE' ? 'orange' : 'default'
                    }
                    className="text-[10px]"
                  >
                    {sw.state || '—'}
                  </Tag>
                </div>
              ))}
            </div>
          </div>
        )}

        {activeMenu === 'signal' && (
          <div className="space-y-3">
            <div className="text-xs text-slate-500">
              信号机 {(lineProfile.signals || []).length} 架
            </div>
            <Select
              className="w-full"
              size="small"
              showSearch
              optionFilterProp="label"
              placeholder="选择信号机"
              options={signalOptions}
              value={selectedSignal}
              onChange={setSelectedSignal}
            />
            <Space wrap>
              <Button
                size="small"
                type="primary"
                loading={loading}
                disabled={selectedSignal == null}
                onClick={() => selectedSignal != null && onSetSignal(selectedSignal, 'GREEN')}
              >
                开放绿灯
              </Button>
              <Button
                size="small"
                danger
                loading={loading}
                disabled={selectedSignal == null}
                onClick={() => selectedSignal != null && onSetSignal(selectedSignal, 'RED')}
              >
                关闭红灯
              </Button>
            </Space>
          </div>
        )}

        {activeMenu === 'tsr' && (
          <div className="space-y-3">
            <div className="text-xs text-slate-500">
              临时限速 {(tsrs || []).length} 条
            </div>
            <div className="flex gap-2">
              <InputNumber
                placeholder="起点 m"
                size="small"
                value={tsrStart}
                onChange={(v) => setTsrStart(v ?? undefined)}
                className="w-full"
              />
              <InputNumber
                placeholder="终点 m"
                size="small"
                value={tsrEnd}
                onChange={(v) => setTsrEnd(v ?? undefined)}
                className="w-full"
              />
            </div>
            <InputNumber
              placeholder="限速 km/h"
              size="small"
              value={tsrSpeed}
              onChange={(v) => setTsrSpeed(v ?? undefined)}
              className="w-full"
            />
            <Button
              type="primary"
              size="small"
              loading={loading}
              disabled={tsrStart == null || tsrEnd == null || tsrSpeed == null}
              onClick={() => {
                if (tsrStart == null || tsrEnd == null || tsrSpeed == null) {
                  message.warning('请填写完整限速参数');
                  return;
                }
                if (tsrEnd <= tsrStart) {
                  message.warning('终点必须大于起点');
                  return;
                }
                onSetTsr(tsrStart, tsrEnd, tsrSpeed, true);
              }}
            >
              设置限速
            </Button>
            <div className="text-xs font-semibold text-slate-400">已生效限速</div>
            {(tsrs || []).filter((t) => t.active !== false).length === 0 ? (
              <div className="text-xs text-slate-600">暂无</div>
            ) : (
              <div className="max-h-32 space-y-1 overflow-y-auto">
                {(tsrs || []).filter((t) => t.active !== false).map((t) => (
                  <div
                    key={t.id || `${t.startM}-${t.endM}`}
                    className="flex items-center justify-between rounded bg-slate-800/60 px-2 py-1 text-xs"
                  >
                    <span className="text-slate-200">
                      {t.speedLimitKmh} km/h ({t.startM}-{t.endM}m)
                    </span>
                    {t.id && (
                      <Button
                        size="small"
                        type="link"
                        danger
                        onClick={() => onCancelTsr(t.id!)}
                      >
                        取消
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {activeMenu === 'status' && (
          <div className="space-y-3 text-xs">
            <div className="rounded-lg border border-slate-700 p-2">
              <div className="text-slate-500">最近操作</div>
              <div className="mt-1 text-slate-100">{lastMessage || '—'}</div>
            </div>
            <div className="rounded-lg border border-slate-700 p-2">
              <div className="text-slate-500">线路</div>
              <div className="mt-1 text-slate-100">
                {lineProfile.lineId || '—'} · {(lineProfile.totalLengthM / 1000).toFixed(1)} km
              </div>
              <div className="mt-1 text-slate-400">
                站 {lineProfile.stations?.length || 0} · 信号{' '}
                {lineProfile.signals?.length || 0} · 道岔 {lineProfile.switches?.length || 0} · 进路{' '}
                {lineProfile.routes?.length || 0}
              </div>
            </div>
            <Button size="small" onClick={onRefresh} loading={loading}>
              刷新 line / MA
            </Button>
          </div>
        )}
      </div>
    </section>
  );
}
