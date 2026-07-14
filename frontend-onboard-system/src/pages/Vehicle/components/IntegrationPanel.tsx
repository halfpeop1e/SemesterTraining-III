import { useEffect, useRef, useState } from "react";
import {
  ackDispatchCommand,
  applyManualDecision,
  confirmVehicleDeparture,
  getOnboardCommands,
  getOnboardSnapshot,
  reportOnboardEvent,
  reportOnboardStatus,
  type OnboardSnapshot,
} from "../../../api/vehicle";
import type { DrivingMode, TrainState } from "../../../types/vehicle";
import { STATIONS } from "../data/lineMap";

/** 根据绝对位置(米)推算当前站和下一站的索引 (0-based) */
function resolveStationIndices(absPosM: number): {
  current: number;
  next: number;
} {
  let current = 0;
  let next = 1;
  const count = STATIONS.length;
  for (let i = 0; i < count; i++) {
    const stationPos = (STATIONS[i].km ?? 0) * 1000;
    if (stationPos <= absPosM) {
      current = i;
      next = Math.min(i + 1, count - 1);
    } else {
      break;
    }
  }
  return { current, next };
}

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
  onDepartAuthorized: (departureState?: string) => void;
  departureState?: string;
  onDispatchHold: () => void;
  onDispatchRecovery: () => void;
  /** In laboratory mode the PLC bridge, rather than this web page, reports state. */
  externalControl?: boolean;
  onManualApproved?: () => void;
  onManualRejected?: () => void;
  /** Control centre removed this train; stop the local HMI instance as well. */
  onTrainOfflined?: (trainId: string) => void;
}

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
  departureState,
  onDispatchHold,
  onDispatchRecovery,
  externalControl = false,
  onManualApproved,
  onManualRejected,
  onTrainOfflined,
}: IntegrationPanelProps) {
  const [snapshot, setSnapshot] = useState<OnboardSnapshot | null>(null);
  const [message, setMessage] = useState("等待车载启动");
  const localStateRef = useRef(localState);
  const processedCommandsRef = useRef(new Set<string>());
  const wasRegisteredRef = useRef(false);
  const offlinedRef = useRef(false);
  const onTrainOfflinedRef = useRef(onTrainOfflined);
  localStateRef.current = localState;
  onTrainOfflinedRef.current = onTrainOfflined;

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
      // Snapshot already uses CommandBus.forOnboard() (increments deliveryAttempts).
      // Fall back to dedicated onboard commands API if snapshot fails partially.
      const snap = await getOnboardSnapshot(trainId);
      if (snap.train) {
        wasRegisteredRef.current = true;
      } else if (wasRegisteredRef.current && !offlinedRef.current) {
        // Do not remove a newly opened, not-yet-online tab. Only a train that was
        // previously confirmed by the control centre can be offlined remotely.
        offlinedRef.current = true;
        setMessage("总控已删除此车，车载实例正在下线");
        onTrainOfflinedRef.current?.(trainId);
        return;
      }
      try {
        const delivered = await getOnboardCommands(trainId);
        setSnapshot({ ...snap, commands: delivered });
      } catch {
        setSnapshot(snap);
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    }
  };

  useEffect(() => {
    wasRegisteredRef.current = false;
    offlinedRef.current = false;
    void refresh();
    const id = window.setInterval(refresh, 2000);
    return () => window.clearInterval(id);
  }, [trainId]);

  useEffect(() => {
    // In desk mode the dispatch command authorizes departure, but it must not
    // move the train. Motion still requires the physical ATO start button.
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
      .then(() => confirmVehicleDeparture(trainId))
      .then((confirmation) => {
        setMessage("调度已授权，车载已确认发车");
        onDepartAuthorized(confirmation.departureState);
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
    externalControl,
  ]);

  useEffect(() => {
    if (externalControl || !localState || !["playing", "finished"].includes(pageStatus)) return;
    const action = snapshot?.commands.find(
      (command) =>
        ["FORCE_HOLD", "HOLD", "ATP_EMERGENCY_BRAKE", "EMERGENCY_RECOVERY", "MANUAL_APPROVED", "MANUAL_REJECTED"].includes(
          command.commandType,
        ) && ["PENDING", "CONFIRMED"].includes(command.status),
    );
    if (!action || processedCommandsRef.current.has(action.commandId)) return;
    processedCommandsRef.current.add(action.commandId);
    void ackDispatchCommand(action.commandId, "EXECUTING")
      .then(async () => {
        if (action.commandType === "EMERGENCY_RECOVERY") {
          setMessage("总控已批准恢复ATO");
          onDispatchRecovery();
        } else if (action.commandType === "MANUAL_APPROVED") {
          await applyManualDecision(trainId, "MANUAL_APPROVED");
          setMessage("总控已批准人工接管");
          onManualApproved?.();
        } else if (action.commandType === "MANUAL_REJECTED") {
          await applyManualDecision(trainId, "MANUAL_REJECTED");
          setMessage("总控已拒绝人工接管，保持 ATO");
          onManualRejected?.();
        } else {
          setMessage("总控触发 ATP 制动：降至零速后保持停车");
          onDispatchHold();
        }
      })
      .catch((error) => {
        processedCommandsRef.current.delete(action.commandId);
        setMessage(error instanceof Error ? error.message : String(error));
      });
  }, [snapshot, localState, pageStatus, onDispatchHold, onDispatchRecovery,
    onManualApproved, onManualRejected, trainId, externalControl]);

  const reportCurrent = async () => {
    const state = localStateRef.current;
    if (externalControl || !state || !["playing", "finished"].includes(pageStatus)) return;
    const absPos = state.absolutePosition ?? lineStartPosition + state.position;
    const indices = resolveStationIndices(absPos);
    await reportOnboardStatus({
      trainId,
      deviceId: deviceIdRef.current,
      sourceType: "ONBOARD_SOFTWARE",
      timestampSeconds: state.time,
      positionMeters: absPos,
      speedKmh: state.velocity * 3.6,
      accelerationMps2: state.acceleration,
      direction: "UP",
      currentStationId: String(STATIONS[indices.current].stationId),
      nextStationId: String(STATIONS[indices.next].stationId),
      phase: paused
          ? "PAUSED"
          : departureAuthorized || state.velocity > 0.05
            ? state.phase.toUpperCase()
            : "READY_TO_DEPART",
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
    externalControl,
  ]);

  const train = localState;
  const absolutePosition = train
    ? (train.absolutePosition ?? lineStartPosition + train.position)
    : 0;

  return (
    <section className="integration-panel" aria-label="总控联调">
      <div>
        <strong>总控联调 · {trainId}</strong>　
        <span>{externalControl ? "实验室司机台状态由 704 控制桥同步" : message}</span>　
        <span>
          ATP {snapshot?.safetyStatus ?? "--"} · 发车状态{" "}
          {departureState ?? "READY_TO_DEPART"}
        </span>
      </div>
      <div>
        线路 {fromStationName} → {toStationName} · 速度{" "}
        {train ? (train.velocity * 3.6).toFixed(1) : "0.0"} km/h · 位置{" "}
        {absolutePosition.toFixed(0)} m · 限速{" "}
        {snapshot?.speedLimitKmh?.toFixed(0) ?? "0"} km/h · MA{" "}
        {snapshot?.movementAuthorityMeters?.toFixed(0) ?? "0"} m
      </div>
      <div>
        {!externalControl && snapshot?.commands.map((command) => (
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
        disabled={!train || externalControl}
        onClick={async () => {
          await reportCurrent();
          setMessage("状态已立即上报");
        }}
      >
        立即上报
      </button>
      <button
        type="button"
        disabled={externalControl}
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
    </section>
  );
}
