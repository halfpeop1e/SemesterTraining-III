import { useEffect, useState } from 'react';
import {
  confirmIntegrationCommand,
  getDispatcherWorkstation,
  issueIntegrationCommand,
  type DispatcherWorkstation,
} from '../../api/dispatch';

export default function DispatcherWorkstationPanel() {
  const [data, setData] = useState<DispatcherWorkstation | null>(null);
  const [collapsed, setCollapsed] = useState(true);

  const refresh = async () => {
    try {
      setData(await getDispatcherWorkstation());
    } catch {
      // The rest of the dispatch page remains usable while the workstation API reconnects.
    }
  };

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => void refresh(), 1000);
    return () => clearInterval(id);
  }, []);

  const authorizeDeparture = async (trainId: string, delaySeconds: number) => {
    await issueIntegrationCommand(
      trainId,
      'DEPART',
      delaySeconds > 0 ? `调度排定 ${delaySeconds} 秒后发车` : '调度立即发车授权',
      Date.now() / 1000 + delaySeconds,
    );
    await refresh();
  };

  const requestRecovery = async (trainId: string) => {
    await issueIntegrationCommand(
      trainId,
      'EMERGENCY_RECOVERY',
      '紧急制动后恢复 ATO 运行，需调度员人工确认',
    );
    await refresh();
  };

  const onboard = data?.onboardMonitoring ?? [];
  const onlineCount = onboard.filter((item) => item.online).length;

  return (
    <aside className={`dispatcher-workstation ${collapsed ? 'is-collapsed' : ''}`}>
      <header>
        <div>
          <strong>独立车载联调</strong>
          <span className={onlineCount > 0 ? 'online' : 'paused'}>
            在线 {onlineCount}
          </span>
        </div>
        <button
          type="button"
          className="dispatcher-workstation__collapse"
          aria-label={collapsed ? '展开车辆状态面板' : '收起车辆状态面板'}
          title={collapsed ? '展开' : '收起'}
          onClick={() => setCollapsed((value) => !value)}
        >
          {collapsed ? '‹' : '›'}
        </button>
      </header>

      {!collapsed && <>
        <div className="workstation-metrics">
          <span><b>{data?.pendingManualConfirmations.length ?? 0}</b>待确认</span>
          <span><b>{data?.onboardEvents.length ?? 0}</b>车载事件</span>
          <span><i className={onlineCount > 0 ? 'signal-ok' : 'signal-wait'} />通信 {onlineCount > 0 ? '正常' : '等待车辆'}</span>
          <span>区域 <b>独立联调</b></span>
        </div>

        <div className="onboard-monitor-title">车辆状态</div>
        {onboard.length === 0 && (
          <div className="onboard-monitor-empty">等待车载端启动并上报状态</div>
        )}
        {onboard.map((item) => {
          const report = item.report;
          const pendingSafetyHold = data?.pendingManualConfirmations.some(
            (command) => command.trainId === item.trainId && command.commandType === 'FORCE_HOLD',
          );
          const duplicateClaim = onboard.filter((candidate) =>
            candidate.online && candidate.trainId === item.trainId,
          ).length > 1;
          const canAuthorizeDeparture = item.online
            && !report.paused
            && report.phase === 'READY_TO_DEPART'
            && report.speedKmh < 0.5;

          return (
            <div
              className={`onboard-monitor-card ${item.online ? 'is-online' : 'is-offline'}`}
              key={item.deviceId ?? item.trainId}
            >
              <div className="onboard-monitor-head">
                <b>{item.trainId}</b>
                <span>{item.online ? '在线' : '离线'}</span>
                <small>{report.operatingMode ?? '--'}{report.paused ? ' · 暂停' : ''}</small>
              </div>
              {duplicateClaim && <div className="onboard-monitor-conflict">同一列车 ID 被多个设备占用</div>}
              <div className="onboard-monitor-route">
                {report.fromStationName ?? report.fromStationId ?? '?'} → {report.toStationName ?? report.toStationId ?? '?'}
                <em>{report.lineId ?? '未知线路'}</em>
              </div>
              <div className="onboard-monitor-readings">
                <span><small>位置</small>{report.positionMeters.toFixed(0)} m</span>
                <span><small>速度</small>{report.speedKmh.toFixed(1)} km/h</span>
                <span><small>状态</small>{report.phase}</span>
              </div>
              <div className="onboard-monitor-meta">
                {item.deviceId ?? '未登记设备'} · {item.sourceType} · {item.ageSeconds.toFixed(1)}s 前
              </div>
              {canAuthorizeDeparture && (
                <div className="onboard-depart-actions">
                  <button type="button" onClick={() => void authorizeDeparture(item.trainId, 0)}>
                    立即发车
                  </button>
                  <button type="button" onClick={() => void authorizeDeparture(item.trainId, 30)}>
                    30 秒后发车
                  </button>
                </div>
              )}
              {item.online && report.operatingMode === 'EMERGENCY' && (
                <div className="onboard-emergency-actions">
                  {pendingSafetyHold ? (
                    <span>请先确认 FORCE_HOLD 安全指令</span>
                  ) : (
                    <button type="button" onClick={() => void requestRecovery(item.trainId)}>
                      申请恢复 ATO
                    </button>
                  )}
                </div>
              )}
            </div>
          );
        })}

        {data?.pendingManualConfirmations.map((command) => (
          <div className="pending-command" key={command.commandId}>
            <b>{command.trainId} · {command.commandType}</b>
            <span>{command.reason}</span>
            <button
              type="button"
              onClick={async () => {
                await confirmIntegrationCommand(command.commandId);
                await refresh();
              }}
            >
              人工确认
            </button>
          </div>
        ))}

        {data?.onboardEvents.slice(-4).map((event) => (
          <div className="onboard-event" key={event.eventId}>
            <b>{event.trainId} · {event.eventType} · {event.severity}</b>
            <div>{event.details}</div>
          </div>
        ))}
      </>}
    </aside>
  );
}
