import { useEffect, useState } from 'react';
import {
  confirmIntegrationCommand,
  getDispatcherWorkstation,
  issueIntegrationCommand,
  type DispatcherWorkstation,
} from '../../api/dispatch';

export default function DispatcherWorkstationPanel() {
  const [data, setData] = useState<DispatcherWorkstation | null>(null);
  const refresh = async () => {
    try {
      setData(await getDispatcherWorkstation());
    } catch {
      // /api/dispatch/workstation 端点暂未实现，静默处理
    }
  };

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => void refresh(), 1000);
    return () => clearInterval(id);
  }, []);

  const authorizeDeparture = async (trainId: string, delaySeconds: number) => {
    const departureEpochSeconds = Date.now() / 1000 + delaySeconds;
    await issueIntegrationCommand(
      trainId,
      'DEPART',
      delaySeconds > 0 ? `调度排定${delaySeconds}秒后发车` : '调度立即发车授权',
      departureEpochSeconds,
    );
    await refresh();
  };

  const requestRecovery = async (trainId: string) => {
    await issueIntegrationCommand(
      trainId,
      'EMERGENCY_RECOVERY',
      '紧急制动后恢复ATO运行，需调度员人工确认',
    );
    await refresh();
  };

  const onboard = data?.onboardMonitoring ?? [];
  const onlineCount = onboard.filter((item) => item.online).length;

  return (
    <aside className="dispatcher-workstation">
      <header>
        <strong>独立车载联调调度台</strong>
        <span className={onlineCount > 0 ? 'online' : 'paused'}>
          车载在线 {onlineCount}
        </span>
      </header>

      <div className="workstation-metrics">
        <span>待确认 {data?.pendingManualConfirmations.length ?? 0}</span>
        <span>车载事件 {data?.onboardEvents.length ?? 0}</span>
        <span>通信 {onlineCount > 0 ? '正常' : '无在线车载'}</span>
        <span>区域 独立车载联调</span>
      </div>

      <div className="onboard-monitor-title">当前车载系统</div>
      {onboard.length === 0 && (
        <div className="onboard-monitor-empty">等待车载端启动并上报状态</div>
      )}
      {onboard.map((item) => {
        const report = item.report;
        const pendingSafetyHold = data?.pendingManualConfirmations.some(
          (command) =>
            command.trainId === item.trainId && command.commandType === 'FORCE_HOLD',
        );
        const duplicateClaim = onboard.filter((candidate) =>
          candidate.online && candidate.trainId === item.trainId,
        ).length > 1;
        return (
          <div className={`onboard-monitor-card ${item.online ? 'is-online' : 'is-offline'}`} key={item.deviceId ?? item.trainId}>
            <div className="onboard-monitor-head">
              <b>{item.trainId}</b>
              <span>{item.online ? '● 在线' : '○ 离线'}</span>
              <small>{report.operatingMode ?? '--'}{report.paused ? ' · 暂停' : ''}</small>
            </div>
            {duplicateClaim && <div className="onboard-monitor-conflict">同一列车ID被多个设备占用</div>}
            <div>
              {report.fromStationName ?? report.fromStationId ?? '?'} →
              {report.toStationName ?? report.toStationId ?? '?'} · {report.lineId ?? '未知线路'}
            </div>
            <div>
              位置 {report.positionMeters.toFixed(0)} m ·
              速度 {report.speedKmh.toFixed(1)} km/h ·
              {report.phase}
            </div>
            <div className="onboard-monitor-meta">
              {item.deviceId ?? '未登记设备'} · {item.sourceType} · {item.ageSeconds.toFixed(1)}s前
            </div>
            {item.online && report.phase === 'READY_TO_DEPART' && (
              <div className="onboard-depart-actions">
                <button type="button" onClick={() => void authorizeDeparture(item.trainId, 0)}>
                  立即发车
                </button>
                <button type="button" onClick={() => void authorizeDeparture(item.trainId, 30)}>
                  30秒后发车
                </button>
              </div>
            )}
            {item.online && report.operatingMode === 'EMERGENCY' && (
              <div className="onboard-emergency-actions">
                {pendingSafetyHold ? (
                  <span>请先确认下方“FORCE_HOLD”安全指令</span>
                ) : (
                  <button type="button" onClick={() => void requestRecovery(item.trainId)}>
                    申请恢复ATO
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
    </aside>
  );
}
