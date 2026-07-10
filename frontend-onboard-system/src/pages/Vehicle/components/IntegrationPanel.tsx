import { useEffect, useRef, useState } from "react";
import {
  ackDispatchCommand,
  getOnboardSnapshot,
  reportOnboardEvent,
  reportOnboardStatus,
  type OnboardSnapshot,
  type TimetableEntry,
} from "../../../api/vehicle";
import type { DrivingMode, TrainState } from "../../../types/vehicle";

interface IntegrationPanelProps {
  trainId?: string;
  localState: TrainState | null;
  pageStatus: "idle" | "loading" | "playing" | "finished" | "error";
  paused: boolean;
  fromStationId: number;
  toStationId: number;
  fromStationName: string;
  toStationName: string;
  lineStartPosition: number;
  drivingMode: DrivingMode;
  departureAuthorized: boolean;
  onDepartAuthorized: () => void;
  onDispatchHold: () => void;
  onDispatchRecovery: () => void;
}

const fmtOnboardTime = (s: number) =>
  [Math.floor(s / 3600), Math.floor((s % 3600) / 60), Math.floor(s % 60)]
    .map((n) => String(n).padStart(2, "0"))
    .join(":");

export default function IntegrationPanel({
  trainId = "T1",
  localState,
  pageStatus,
  paused,
  fromStationId,
  toStationId,
  fromStationName,
  toStationName,
  lineStartPosition,
  drivingMode,
  departureAuthorized,
  onDepartAuthorized,
  onDispatchHold,
  onDispatchRecovery,
}: IntegrationPanelProps) {
  const [snapshot, setSnapshot] = useState<OnboardSnapshot | null>(null);
  const [message, setMessage] = useState("等待车载启动");
  const localStateRef = useRef(localState);
  const processedCommandsRef = useRef(new Set<string>());
  localStateRef.current = localState;

  const deviceIdRef = useRef("");
  if (!deviceIdRef.current) {
    const key = `onboard-device-${trainId}`;
    deviceIdRef.current =
      sessionStorage.getItem(key) ||
      `HMI-${trainId}-${crypto.randomUUID().slice(0, 8)}`;
    sessionStorage.setItem(key, deviceIdRef.current);
  }

  const refresh = async () => {
    try {
      setSnapshot(await getOnboardSnapshot(trainId));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  useEffect(() => {
    void refresh();
    const id = window.setInterval(refresh, 2000);
    return () => window.clearInterval(id);
  }, [trainId]);

  useEffect(() => {
    if (!localState || pageStatus !== "playing" || departureAuthorized) return;
    const depart = snapshot?.commands.find(
      (command) =>
        command.commandType === "DEPART" &&
        ["PENDING", "CONFIRMED"].includes(command.status),
    );
    if (!depart || processedCommandsRef.current.has(depart.commandId)) return;
    const scheduledEpochSeconds = depart.targetValue || 0;
    const remainingSeconds = scheduledEpochSeconds - Date.now() / 1000;
    if (remainingSeconds > 0) {
      setMessage(`在线 · 已排定 ${Math.ceil(remainingSeconds)} 秒后发车`);
      return;
    }
    processedCommandsRef.current.add(depart.commandId);
    void ackDispatchCommand(depart.commandId, "EXECUTING")
      .then(() => {
        setMessage("调度已授权发车");
        onDepartAuthorized();
      })
      .catch((error) => {
        processedCommandsRef.current.delete(depart.commandId);
        setMessage(error instanceof Error ? error.message : String(error));
      });
  }, [
    snapshot,
    localState,
    pageStatus,
    departureAuthorized,
    onDepartAuthorized,
  ]);

  useEffect(() => {
    if (!localState || !["playing", "finished"].includes(pageStatus)) return;
    const action = snapshot?.commands.find(
      (command) =>
        ["FORCE_HOLD", "HOLD", "EMERGENCY_RECOVERY"].includes(
          command.commandType,
        ) && ["PENDING", "CONFIRMED"].includes(command.status),
    );
    if (!action || processedCommandsRef.current.has(action.commandId)) return;
    processedCommandsRef.current.add(action.commandId);
    void ackDispatchCommand(action.commandId, "EXECUTING")
      .then(() => {
        if (action.commandType === "EMERGENCY_RECOVERY") {
          setMessage("总控已批准恢复ATO");
          onDispatchRecovery();
        } else {
          setMessage("总控触发 ATP 制动：降至零速后保持停车");
          onDispatchHold();
        }
      })
      .catch((error) => {
        processedCommandsRef.current.delete(action.commandId);
        setMessage(error instanceof Error ? error.message : String(error));
      });
  }, [snapshot, localState, pageStatus, onDispatchHold, onDispatchRecovery]);

  const reportCurrent = async () => {
    const state = localStateRef.current;
    if (!state || !["playing", "finished"].includes(pageStatus)) return;
    await reportOnboardStatus({
      trainId,
      deviceId: deviceIdRef.current,
      sourceType: "ONBOARD_SOFTWARE",
      timestampSeconds: state.time,
      positionMeters:
        state.absolutePosition ?? lineStartPosition + state.position,
      speedKmh: state.velocity * 3.6,
      accelerationMps2: state.acceleration,
      direction: "UP",
      currentStationId: String(fromStationId),
      nextStationId: String(toStationId),
      phase: !departureAuthorized
        ? "READY_TO_DEPART"
        : paused
          ? "PAUSED"
          : state.phase.toUpperCase(),
      delaySeconds: 0,
      health: "HEALTHY",
      lineId: "BJ-L9",
      routeId: `${fromStationId}-${toStationId}`,
      fromStationId: String(fromStationId),
      toStationId: String(toStationId),
      fromStationName,
      toStationName,
      operatingMode: drivingMode.toUpperCase(),
      paused,
      authoritative: true,
    });
    setMessage(
      !departureAuthorized
        ? "在线 · 等待调度发车"
        : paused
          ? "在线 · 车辆暂停"
          : "在线 · 状态自动上报",
    );
  };

  useEffect(() => {
    if (!localState || !["playing", "finished"].includes(pageStatus))
      return undefined;
    void reportCurrent();
    const id = window.setInterval(() => {
      void reportCurrent().catch((error) => {
        setMessage(error instanceof Error ? error.message : String(error));
      });
    }, 1000);
    return () => window.clearInterval(id);
  }, [
    trainId,
    pageStatus,
    paused,
    fromStationId,
    toStationId,
    lineStartPosition,
    drivingMode,
    departureAuthorized,
  ]);

  const train = localState;
  const absolutePosition = train
    ? (train.absolutePosition ?? lineStartPosition + train.position)
    : 0;

  return (
    <section className="integration-panel" aria-label="总控联调">
      <div>
        <strong>总控联调 · {trainId}</strong>　<span>{message}</span>　
        <span>ATP {snapshot?.safetyStatus ?? "--"}</span>
      </div>
      <div>
        线路 {fromStationName} → {toStationName} · 速度{" "}
        {train ? (train.velocity * 3.6).toFixed(1) : "0.0"} km/h · 位置{" "}
        {absolutePosition.toFixed(0)} m · 限速{" "}
        {snapshot?.speedLimitKmh?.toFixed(0) ?? "0"} km/h · MA{" "}
        {snapshot?.movementAuthorityMeters?.toFixed(0) ?? "0"} m
      </div>
      <div>
        {snapshot?.commands.map((command) => (
          <button
            key={command.commandId}
            type="button"
            onClick={async () => {
              await ackDispatchCommand(command.commandId);
              await refresh();
            }}
          >
            ACK {command.commandType} · {command.status}
          </button>
        ))}
      </div>
      <button
        type="button"
        disabled={!train}
        onClick={async () => {
          await reportCurrent();
          setMessage("状态已立即上报");
        }}
      >
        立即上报
      </button>
      <button
        type="button"
        onClick={async () => {
          await reportOnboardEvent({
            trainId,
            eventType: "STOP_PRECISION_WARNING",
            timestampSeconds: train?.time ?? 0,
            positionMeters: absolutePosition,
            speedKmh: train ? train.velocity * 3.6 : 0,
            severity: "WARNING",
            details: "HMI 模拟事件",
          });
          setMessage("事件已上报");
        }}
      >
        模拟事件
      </button>

      {/* 时刻表 */}
      {snapshot?.timetable && snapshot.timetable.length > 0 && (
        <div className="onboard-timetable">
          <div className="onboard-timetable-title">时刻表</div>
          <table className="onboard-timetable-table">
            <thead>
              <tr>
                <th>车站</th>
                <th>里程(km)</th>
                <th>计划到站</th>
                <th>计划发车</th>
                <th>停站(s)</th>
              </tr>
            </thead>
            <tbody>
              {snapshot.timetable.map((entry: TimetableEntry) => (
                <tr key={entry.stationIndex}>
                  <td>{entry.stationName}</td>
                  <td>{entry.stationKm.toFixed(1)}</td>
                  <td>{fmtOnboardTime(entry.plannedArrival)}</td>
                  <td>{fmtOnboardTime(entry.plannedDeparture)}</td>
                  <td>{entry.plannedDwell.toFixed(0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
