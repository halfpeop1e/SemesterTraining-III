// 车载模块 —— Vehicle 页面
// 重构：WebSocket 流式仿真（20Hz），替代 setInterval + 帧管理 + rAF 插值

import { useCallback, useEffect, useRef, useState } from "react";
import {
  callVehicleControl,
  reportOnboardEvent,
  resetVehicleSimulation,
  runVehicleSimulation,
  streamVehicleSimulation,
} from "../../api/vehicle";
import {
  disconnectProtocol704,
  getHilGatewayStatus,
  resetProtocol704,
} from "../../api/protocol704";
import type {
  DrivingMode,
  SafetyEvent,
  StationStop,
  TrainState,
} from "../../types/vehicle";
import { STATIONS } from "./data/lineMap";
import DriverCabView from "./components/DriverCabView";
import LineRunView from "./components/LineRunView";
import TractionCurveView from "./components/TractionCurveView";
import TimetableView from "./components/TimetableView";
import IntegrationPanel from "./components/IntegrationPanel";
import Protocol704Panel from "./components/Protocol704Panel";
import "./vehicle.css";

const DEMO_SPEED_LIMIT_FALLBACK_MS = 20;
type PageStatus = "idle" | "loading" | "playing" | "finished" | "error";
type ControlSourceMode = "SIMULATION" | "LAB_DRIVER_DESK";

// ── WS 消息类型 ──
interface OnboardWsFrame {
  type: "frame" | "finished" | "error";
  time: number;
  position: number;
  velocity: number;
  acceleration: number;
  phase?: string;
  trainId: string;
  absolutePosition?: number;
  tractionForce?: number;
  brakeForce?: number;
  availableMotors?: number;
  resistanceDecel?: number;
  // finished 帧
  speedLimit?: number;
  targetStopPosition?: number;
  departureState?: string;
  fromStationName?: string;
  toStationName?: string;
  lineStartPosition?: number;
  totalStations?: number;
  completedStops?: number;
  dtPerFrame?: number;
  stationStops?: StationStop[];
  safetyEvents?: SafetyEvent[];
  trainMass?: number;
}

interface InstanceState {
  trainId: string;
  status: PageStatus;
  errorMessage: string | null;
  fromStationId: number;
  toStationId: number;
  drivingMode: DrivingMode;
  controlSourceMode: ControlSourceMode;
  allSafetyEvents: SafetyEvent[];
  handleResetToken: number;
  departureAuthorized: boolean;
  // from finished WS frame
  speedLimit: number;
  targetStopPosition: number;
  lineStartPosition: number;
  fromStationName: string | null;
  toStationName: string | null;
  totalStations: number;
  completedStops: number;
  stationStops: StationStop[] | undefined;
  trainMass: number;
}

let nextAutoId = 1;
function parseTrainIdSuffix(s: string): number | null {
  const m = s.match(/^OB(\d+)$/);
  return m ? parseInt(m[1], 10) : null;
}

/** 从线路数据计算起止站间的相对目标距离和绝对起点位置 */
function getStationDistance(
  fromStationId: number,
  toStationId: number,
): {
  targetStopPosition: number;
  lineStartPosition: number;
} {
  const from = STATIONS.find((s) => s.stationId === fromStationId);
  const to = STATIONS.find((s) => s.stationId === toStationId);
  if (from && to && to.km > from.km) {
    return {
      targetStopPosition: Math.round((to.km - from.km) * 1000),
      lineStartPosition: Math.round(from.km * 1000),
    };
  }
  // 回退：默认 郭公庄(1) → 丰台科技园(2)
  return { targetStopPosition: 1348, lineStartPosition: 313 };
}

function createInstance(trainId: string): InstanceState {
  const dist = getStationDistance(1, 2);
  return {
    trainId,
    status: "idle",
    errorMessage: null,
    fromStationId: 1,
    toStationId: 2,
    drivingMode: "ato",
    controlSourceMode: "SIMULATION",
    allSafetyEvents: [],
    handleResetToken: 0,
    departureAuthorized: false,
    speedLimit: DEMO_SPEED_LIMIT_FALLBACK_MS,
    targetStopPosition: dist.targetStopPosition,
    lineStartPosition: dist.lineStartPosition,
    fromStationName: null,
    toStationName: null,
    totalStations: 0,
    completedStops: 0,
    stationStops: undefined,
    trainMass: 225_000,
  };
}
function getTrainId(existing: string[]): string {
  while (true) {
    const c = `OB${nextAutoId++}`;
    if (!existing.includes(c)) return c;
  }
}

function Vehicle() {
  const urlParam = new URLSearchParams(window.location.search).get("trainId");
  const urlTrainId = urlParam || getTrainId([]);
  if (urlParam) {
    const s = parseTrainIdSuffix(urlParam);
    if (s !== null && s >= nextAutoId) nextAutoId = s + 1;
  }

  const [instances, setInstances] = useState<Map<string, InstanceState>>(() => {
    const m = new Map<string, InstanceState>();
    m.set(urlTrainId, createInstance(urlTrainId));
    return m;
  });
  const [activeTrainId, setActiveTrainId] = useState(urlTrainId);
  const [rightPanelTab, setRightPanelTab] = useState<
    "lineRun" | "tractionCurve" | "timetable"
  >("lineRun");

  // WebSocket 驱动的显示帧（20Hz，后端推送）
  const [displayFrame, setDisplayFrame] = useState<TrainState | null>(null);

  // 帧间插值 refs（WS 20Hz → Canvas 60fps）
  const interpRef = useRef<{
    prev: TrainState | null;
    next: TrainState | null;
    arrivedAt: number;
  }>({
    prev: null,
    next: null,
    arrivedAt: 0,
  });

  // ── WebSocket ──
  const wsRef = useRef<WebSocket | null>(null);
  const wsReconnectTimerRef = useRef<number | null>(null);

  const connectWs = useCallback((trainId: string) => {
    // 断开旧连接
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    if (wsReconnectTimerRef.current !== null) {
      clearTimeout(wsReconnectTimerRef.current);
      wsReconnectTimerRef.current = null;
    }

    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${protocol}//${window.location.host}/ws/simulation`;
    const ws = new WebSocket(wsUrl);

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: "subscribe", trainId }));
    };

    ws.onmessage = (event) => {
      try {
        const f: OnboardWsFrame = JSON.parse(event.data as string);
        if (f.type === "frame") {
          const state: TrainState = {
            time: f.time,
            position: f.position,
            velocity: f.velocity,
            acceleration: f.acceleration,
            phase: f.phase ?? "",
            trainId: f.trainId,
            absolutePosition: f.absolutePosition,
            tractionForce: f.tractionForce,
            brakeForce: f.brakeForce,
            availableMotors: f.availableMotors,
            resistanceDecel: f.resistanceDecel,
          };
          // 推入插值缓冲（60fps rAF 消费，WS 20Hz 生产）
          interpRef.current.prev = interpRef.current.next;
          interpRef.current.next = state;
          interpRef.current.arrivedAt = performance.now();
          setInstances((prev) => {
            const next = new Map(prev);
            const cur = next.get(trainId);
            if (cur && cur.status === "loading")
              next.set(trainId, { ...cur, status: "playing" });
            return next;
          });
        } else if (f.type === "finished") {
          setDisplayFrame(null);
          setInstances((prev) => {
            const next = new Map(prev);
            const cur = next.get(trainId);
            if (cur) {
              next.set(trainId, {
                ...cur,
                status: "finished",
                speedLimit: f.speedLimit ?? cur.speedLimit,
                targetStopPosition:
                  f.targetStopPosition ?? cur.targetStopPosition,
                lineStartPosition: f.lineStartPosition ?? cur.lineStartPosition,
                fromStationName: f.fromStationName ?? cur.fromStationName,
                toStationName: f.toStationName ?? cur.toStationName,
                totalStations: f.totalStations ?? cur.totalStations,
                completedStops: f.completedStops ?? cur.completedStops,
                stationStops: f.stationStops ?? cur.stationStops,
                trainMass: f.trainMass ?? cur.trainMass,
                allSafetyEvents: (f.safetyEvents ?? []).filter(
                  (ev) => ev.reason !== "SPEED_WARNING",
                ),
              });
            }
            return next;
          });
        } else if (f.type === "error") {
          setInstances((prev) => {
            const next = new Map(prev);
            const cur = next.get(trainId);
            if (cur)
              next.set(trainId, {
                ...cur,
                status: "error",
                errorMessage: f.phase ?? "WS error",
              });
            return next;
          });
        }
      } catch {
        /* ignore malformed */
      }
    };

    ws.onclose = () => {
      // 自动重连
      wsReconnectTimerRef.current = window.setTimeout(
        () => connectWs(trainId),
        2000,
      );
    };

    ws.onerror = () => {
      ws.close();
    };
    wsRef.current = ws;
  }, []);

  const disconnectWs = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    if (wsReconnectTimerRef.current !== null) {
      clearTimeout(wsReconnectTimerRef.current);
      wsReconnectTimerRef.current = null;
    }
  }, []);

  // 帧间微插值：WS 20Hz → setDisplayFrame 60fps，消除离散跳跃
  useEffect(() => {
    let raf = 0;
    const tick = () => {
      const { prev, next, arrivedAt } = interpRef.current;
      if (prev && next) {
        const span = next.time - prev.time;
        const elapsed = (performance.now() - arrivedAt) / 1000;
        const t = span > 0 ? Math.min(elapsed / Math.max(span, 0.05), 1) : 1;
        setDisplayFrame({
          time: prev.time + (next.time - prev.time) * t,
          position: prev.position + (next.position - prev.position) * t,
          velocity: prev.velocity + (next.velocity - prev.velocity) * t,
          acceleration:
            prev.acceleration + (next.acceleration - prev.acceleration) * t,
          phase: next.phase,
          trainId: next.trainId,
          absolutePosition:
            prev.absolutePosition !== undefined &&
            next.absolutePosition !== undefined
              ? prev.absolutePosition +
                (next.absolutePosition - prev.absolutePosition) * t
              : next.absolutePosition,
          tractionForce:
            (prev.tractionForce ?? 0) +
            ((next.tractionForce ?? 0) - (prev.tractionForce ?? 0)) * t,
          brakeForce:
            (prev.brakeForce ?? 0) +
            ((next.brakeForce ?? 0) - (prev.brakeForce ?? 0)) * t,
          availableMotors: next.availableMotors,
          resistanceDecel: next.resistanceDecel,
        });
      } else if (next) {
        setDisplayFrame(next);
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, []);

  const instancesRef = useRef(instances);
  instancesRef.current = instances;
  const adoptedLaboratoryTrainRef = useRef<string | null>(null);

  // 实验室司机台实例采纳
  useEffect(() => {
    let cancelled = false;
    const f = async () => {
      try {
        const h = await getHilGatewayStatus();
        const tid = h.enabled ? h.trainId?.trim() : "";
        if (!tid || cancelled) return;
        setInstances((p) => {
          const n = new Map(p);
          const e = n.get(tid);
          if (e)
            n.set(tid, {
              ...e,
              controlSourceMode: "LAB_DRIVER_DESK",
              status: e.status === "idle" ? "playing" : e.status,
            });
          else
            n.set(tid, {
              ...createInstance(tid),
              status: "playing",
              controlSourceMode: "LAB_DRIVER_DESK",
              fromStationId: 1,
              toStationId: 13,
            });
          return n;
        });
        if (adoptedLaboratoryTrainRef.current !== tid) {
          adoptedLaboratoryTrainRef.current = tid;
          setActiveTrainId(tid);
        }
      } catch {}
    };
    void f();
    const t = window.setInterval(() => void f(), 1500);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, []);

  const activeInstance = instances.get(activeTrainId);

  const updateInstance = useCallback(
    (trainId: string, updater: (prev: InstanceState) => InstanceState) => {
      setInstances((p) => {
        const n = new Map(p);
        const c = n.get(trainId);
        if (c) n.set(trainId, updater(c));
        return n;
      });
    },
    [],
  );

  const switchToTrain = useCallback((id: string) => setActiveTrainId(id), []);
  const addInstance = useCallback(() => {
    setInstances((p) => {
      const ids = Array.from(p.keys());
      const newId = getTrainId(ids);
      const n = new Map(p);
      n.set(newId, createInstance(newId));
      setTimeout(() => setActiveTrainId(newId), 0);
      return n;
    });
  }, []);

  const removeInstance = useCallback(
    (trainId: string, allowEmpty = false) => {
      if (instances.size <= 1 && !allowEmpty) return;
      void disconnectProtocol704(trainId).catch(() => undefined);
      setInstances((p) => {
        const n = new Map(p);
        n.delete(trainId);
        return n;
      });
      if (activeTrainId === trainId) {
        const rem = Array.from(instances.keys()).filter((id) => id !== trainId);
        setActiveTrainId(rem[0] || activeTrainId);
      }
    },
    [instances, activeTrainId],
  );

  const handleTrainOfflined = useCallback(
    (tid: string) => removeInstance(tid, true),
    [removeInstance],
  );

  // ── 实例操作 ──

  const handleStart = useCallback(
    async (trainId: string) => {
      const inst = instances.get(trainId);
      if (!inst) return;
      const isLab = inst.controlSourceMode === "LAB_DRIVER_DESK";
      updateInstance(trainId, (cur) => ({
        ...cur,
        status: "loading",
        errorMessage: null,
        allSafetyEvents: [],
        handleResetToken: cur.handleResetToken + 1,
      }));
      setDisplayFrame(null);
      interpRef.current = { prev: null, next: null, arrivedAt: 0 };

      try {
        if (isLab) {
          await runVehicleSimulation({
            trainId,
            fromStationId: inst.fromStationId,
            toStationId: inst.toStationId,
            hardwareControlEnabled: true,
            labAutoDepartureEnabled: true,
          });
        } else {
          const resp = await streamVehicleSimulation({
            trainId,
            fromStationId: inst.fromStationId,
            toStationId: inst.toStationId,
          });
          if (!resp.success) throw new Error(resp.message || "启动失败");
        }
        // 统一通过 WS 接收数据（SIM 模式：预计算流式推送 / LAB 模式：PLC 实时推送）
        connectWs(trainId);
        updateInstance(trainId, (cur) => ({
          ...cur,
          status: "playing",
          departureAuthorized: isLab,
        }));
      } catch (err) {
        updateInstance(trainId, (cur) => ({
          ...cur,
          status: "error",
          errorMessage: err instanceof Error ? err.message : String(err),
        }));
      }
    },
    [instances, updateInstance, connectWs],
  );

  const handleControl = useCallback(
    async (
      trainId: string,
      command: string,
      targetDecel: number,
      mode: DrivingMode,
      levelPercent = 0,
    ) => {
      const curInst = instancesRef.current.get(trainId);
      if (!curInst) return;
      const cs = displayFrame;
      const fromId = curInst.fromStationId;
      const toId = curInst.toStationId;

      let totalTarget = curInst.targetStopPosition;
      let nextStationId: number | undefined;
      let nextStationName: string | undefined;
      const stops = curInst.stationStops;
      if (cs && stops?.length) {
        const ns = stops.find((s) => s.targetPosition > cs.position + 0.5);
        if (ns) {
          totalTarget = ns.targetPosition;
          nextStationId = ns.stationId;
          nextStationName = ns.stationName;
        }
      }

      try {
        const controlResult = await callVehicleControl({
          trainId,
          fromStationId: fromId,
          toStationId: toId,
          currentState: cs || {
            trainId,
            time: 0,
            position: 0,
            velocity: 0,
            acceleration: 0,
            phase: "",
          },
          currentMode: mode,
          controlCommand: { command, targetDecel, levelPercent },
          totalTargetPosition: totalTarget,
          nextStationId,
          nextStationName,
        });
        if (
          controlResult &&
          "status" in controlResult &&
          controlResult.status === "PENDING_APPROVAL"
        ) {
          updateInstance(trainId, (cur) => ({
            ...cur,
            errorMessage: controlResult.message || "已向中控发送人工接管请求",
          }));
          return;
        }
        if (
          !controlResult ||
          !("states" in controlResult) ||
          !Array.isArray(controlResult.states) ||
          controlResult.states.length === 0 ||
          !controlResult.summary
        ) {
          throw new Error("控制接口返回的仿真结果不完整");
        }
        // 控制后切换到 WS 流式推送
        const dist = getStationDistance(fromId, toId);
        updateInstance(trainId, (cur) => ({
          ...cur,
          status: "playing",
          errorMessage: null,
          drivingMode: controlResult.summary.currentMode ?? cur.drivingMode,
          targetStopPosition: dist.targetStopPosition,
          lineStartPosition: dist.lineStartPosition,
          handleResetToken:
            command === "resume_ato" || command === "reset_emergency"
              ? cur.handleResetToken + 1
              : cur.handleResetToken,
        }));
      } catch (err) {
        updateInstance(trainId, (cur) => ({
          ...cur,
          errorMessage: err instanceof Error ? err.message : String(err),
        }));
      }
    },
    [updateInstance, displayFrame],
  );

  const handleReset = useCallback(
    (trainId: string) => {
      void resetProtocol704(trainId).catch(() => undefined);
      disconnectWs();
      setDisplayFrame(null);
      interpRef.current = { prev: null, next: null, arrivedAt: 0 };
      const positionMeters = displayFrame?.position ?? 0;
      const curInst = instancesRef.current.get(trainId);
      const dist = getStationDistance(
        curInst?.fromStationId ?? 1,
        curInst?.toStationId ?? 2,
      );
      updateInstance(trainId, (cur) => ({
        ...cur,
        status: "idle",
        errorMessage: null,
        allSafetyEvents: [],
        handleResetToken: cur.handleResetToken + 1,
        drivingMode: "ato",
        departureAuthorized: false,
        speedLimit: DEMO_SPEED_LIMIT_FALLBACK_MS,
        targetStopPosition: dist.targetStopPosition,
        lineStartPosition: dist.lineStartPosition,
        stationStops: undefined,
        fromStationName: null,
        toStationName: null,
      }));
      void resetVehicleSimulation(trainId, positionMeters).catch(
        () => undefined,
      );
    },
    [instances, updateInstance, disconnectWs, displayFrame],
  );

  const setControlSourceMode = useCallback(
    (trainId: string, mode: ControlSourceMode) => {
      const cur = instances.get(trainId);
      if (!cur || cur.controlSourceMode === mode) return;
      if (mode === "SIMULATION")
        void resetProtocol704(trainId).catch(() => undefined);
      updateInstance(trainId, (c) => ({
        ...c,
        controlSourceMode: mode,
        departureAuthorized:
          mode === "LAB_DRIVER_DESK" ? false : c.departureAuthorized,
        handleResetToken: c.handleResetToken + 1,
      }));
    },
    [instances, updateInstance],
  );

  const handleDepartAuthorized = useCallback(
    (trainId: string) => {
      updateInstance(trainId, (cur) => ({ ...cur, departureAuthorized: true }));
    },
    [updateInstance],
  );

  const handleDispatchHold = useCallback(
    (trainId: string) => {
      updateInstance(trainId, (cur) => ({ ...cur, status: "playing" }));
      handleControl(trainId, "atp_emergency_brake", 0, "ato");
    },
    [updateInstance, handleControl],
  );
  const handleDispatchRecovery = useCallback(
    (trainId: string) => {
      updateInstance(trainId, (cur) => ({
        ...cur,
        drivingMode: "ato",
        status: "playing",
      }));
      handleControl(trainId, "traction", 0, "ato");
    },
    [updateInstance, handleControl],
  );
  const handleRequestManual = useCallback(
    (tid: string) => {
      void handleControl(tid, "set_manual", 0, "ato");
    },
    [handleControl],
  );
  const handleTractionLevel = useCallback(
    (tid: string, _l: number, pct: number) => {
      handleControl(
        tid,
        "traction",
        0,
        instancesRef.current.get(tid)?.drivingMode ?? "manual",
        pct,
      );
    },
    [handleControl],
  );
  const handleBrakeLevel = useCallback(
    (tid: string, _l: number, decel: number, pct: number) => {
      handleControl(
        tid,
        "brake",
        decel,
        instancesRef.current.get(tid)?.drivingMode ?? "manual",
        pct,
      );
    },
    [handleControl],
  );
  const handleCoast = useCallback(
    (tid: string) => {
      handleControl(
        tid,
        "coast",
        0,
        instancesRef.current.get(tid)?.drivingMode ?? "manual",
      );
    },
    [handleControl],
  );
  const handleRequestAto = useCallback(
    (tid: string) => {
      handleControl(tid, "resume_ato", 0, "manual");
    },
    [handleControl],
  );
  const handleResetEmergency = useCallback(
    (tid: string) => {
      handleControl(tid, "reset_emergency", 0, "emergency");
    },
    [handleControl],
  );
  const handleEmergencyBrake = useCallback(
    (tid: string) => {
      const cur = instancesRef.current.get(tid);
      if (!cur) return;
      updateInstance(tid, (inst) => ({ ...inst, drivingMode: "emergency" }));
      handleControl(tid, "emergency_brake", 0, cur.drivingMode);
      if (displayFrame) {
        void reportOnboardEvent({
          trainId: tid,
          eventType: "ATP_EB_TRIGGERED",
          timestampSeconds: displayFrame.time,
          positionMeters:
            displayFrame.absolutePosition ?? displayFrame.position,
          speedKmh: displayFrame.velocity * 3.6,
          severity: "CRITICAL",
          details: "车载端触发紧急制动",
        });
      }
    },
    [updateInstance, handleControl, displayFrame],
  );

  // ── 派生值 ──
  const ai = activeInstance ?? createInstance(activeTrainId);
  const viewState = displayFrame;
  const canResetEmergency = ai.drivingMode === "emergency";
  const isLoading = ai.status === "loading";
  const isPlaying = ai.status === "playing";
  const canReset =
    ai.status === "finished" || ai.status === "error" || isPlaying;
  const labDriverDeskMode = ai.controlSourceMode === "LAB_DRIVER_DESK";
  const toStationOptions = STATIONS.filter(
    (s) => s.stationId > ai.fromStationId,
  );
  const trainIds = Array.from(instances.keys());

  // 清理
  useEffect(() => () => disconnectWs(), [disconnectWs]);

  if (!activeInstance) {
    return (
      <div className="vehicle-page">
        <div className="vehicle-empty flex flex-col items-center gap-3">
          <span>暂无车载实例</span>
          <button className="vehicle-tab-add" onClick={addInstance}>
            ＋ 添加车辆
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="vehicle-page">
      {/* 标签栏 */}
      <div className="vehicle-tab-bar">
        <div className="vehicle-tab-list">
          {trainIds.map((tid) => {
            const inst = instances.get(tid);
            const isActive = tid === activeTrainId;
            const icon =
              inst?.status === "playing"
                ? "▶"
                : inst?.status === "finished"
                  ? "✓"
                  : "○";
            return (
              <div
                key={tid}
                className={`vehicle-tab ${isActive ? "is-active" : ""}`}
              >
                <button
                  className="vehicle-tab-btn"
                  onClick={() => switchToTrain(tid)}
                >
                  <span className="vehicle-tab-dot">{icon}</span>
                  <span>{tid}</span>
                </button>
                {trainIds.length > 1 && (
                  <button
                    className="vehicle-tab-close"
                    onClick={(e) => {
                      e.stopPropagation();
                      removeInstance(tid);
                    }}
                  >
                    ×
                  </button>
                )}
              </div>
            );
          })}
        </div>
        <button className="vehicle-tab-add" onClick={addInstance}>
          ＋
        </button>
      </div>

      {/* Header */}
      <header className="vehicle-page__header">
        <div>
          <p className="vehicle-page__eyebrow">Onboard cab HMI</p>
          <h2>车载驾驶台系统 · {activeTrainId}</h2>
        </div>
        <div className="vehicle-page__actions">
          <div className="vehicle-station-selector">
            <label htmlFor="fs">出发站</label>
            <select
              id="fs"
              className="vehicle-station-select"
              value={ai.fromStationId}
              disabled={isLoading || isPlaying}
              onChange={(e) =>
                updateInstance(activeTrainId, (cur) => {
                  const nf = Number(e.target.value);
                  let nt = cur.toStationId;
                  if (nt <= nf) {
                    const nx = STATIONS.find(
                      (s) => s.stationId > nf,
                    )?.stationId;
                    if (nx !== undefined) nt = nx;
                  }
                  return { ...cur, fromStationId: nf, toStationId: nt };
                })
              }
            >
              {STATIONS.filter(
                (s) => s.stationId < STATIONS[STATIONS.length - 1].stationId,
              ).map((s) => (
                <option key={s.stationId} value={s.stationId}>
                  {s.displayNameOverride ?? s.displayName}
                </option>
              ))}
            </select>
            <span className="vehicle-station-arrow">→</span>
            <label htmlFor="ts">目标站</label>
            <select
              id="ts"
              className="vehicle-station-select"
              value={ai.toStationId}
              disabled={isLoading || isPlaying}
              onChange={(e) =>
                updateInstance(activeTrainId, (cur) => ({
                  ...cur,
                  toStationId: Number(e.target.value),
                }))
              }
            >
              {toStationOptions.map((s) => (
                <option key={s.stationId} value={s.stationId}>
                  {s.displayNameOverride ?? s.displayName}
                </option>
              ))}
            </select>
          </div>
          <div className="vehicle-control-mode" role="group">
            <span>控制来源</span>
            <button
              className={!labDriverDeskMode ? "is-active" : ""}
              disabled={isLoading || isPlaying}
              onClick={() => setControlSourceMode(activeTrainId, "SIMULATION")}
            >
              软件仿真
            </button>
            <button
              className={labDriverDeskMode ? "is-active" : ""}
              disabled={isLoading || isPlaying}
              onClick={() =>
                setControlSourceMode(activeTrainId, "LAB_DRIVER_DESK")
              }
            >
              实验室司机台
            </button>
          </div>
          <button
            className="vehicle-start-btn"
            onClick={() => handleStart(activeTrainId)}
            disabled={isLoading || isPlaying}
          >
            {isLoading
              ? "准备中..."
              : ai.status === "finished" || ai.status === "error"
                ? "重新上线"
                : labDriverDeskMode
                  ? "上线并启用手柄"
                  : "上线并等待发车"}
          </button>
          <button
            className="vehicle-ghost-btn"
            onClick={() => handleReset(activeTrainId)}
            disabled={!canReset || isLoading}
          >
            复位
          </button>
          {isPlaying && viewState && (
            <span className="vehicle-status-text">
              {labDriverDeskMode ? "司机台同步" : "播放中"} · t=
              {viewState.time.toFixed(1)}s · 模式:{ai.drivingMode.toUpperCase()}
            </span>
          )}
          {ai.status === "finished" && (
            <span className="vehicle-status-text">仿真完成</span>
          )}
          {ai.fromStationName && ai.toStationName && (
            <span className="vehicle-section-label">
              {ai.fromStationName} → {ai.toStationName}
            </span>
          )}
        </div>
      </header>

      {/* IntegrationPanel */}
      {trainIds.map((tid) => {
        const inst = instances.get(tid)!;
        return (
          <div
            key={`ip-${tid}`}
            style={{ display: tid === activeTrainId ? undefined : "none" }}
          >
            <IntegrationPanel
              trainId={tid}
              localState={tid === activeTrainId ? displayFrame : null}
              pageStatus={inst.status}
              paused={false}
              fromStationId={inst.fromStationId}
              toStationId={inst.toStationId}
              fromStationName={
                inst.fromStationName ??
                STATIONS.find((s) => s.stationId === inst.fromStationId)
                  ?.displayNameOverride ??
                STATIONS.find((s) => s.stationId === inst.fromStationId)
                  ?.displayName ??
                String(inst.fromStationId)
              }
              toStationName={
                inst.toStationName ??
                STATIONS.find((s) => s.stationId === inst.toStationId)
                  ?.displayNameOverride ??
                STATIONS.find((s) => s.stationId === inst.toStationId)
                  ?.displayName ??
                String(inst.toStationId)
              }
              lineStartPosition={inst.lineStartPosition}
              drivingMode={inst.drivingMode}
              departureState="RUNNING"
              departureAuthorized={inst.departureAuthorized}
              onDepartAuthorized={() => handleDepartAuthorized(tid)}
              onDispatchHold={() => handleDispatchHold(tid)}
              onDispatchRecovery={() => handleDispatchRecovery(tid)}
              externalControl={inst.controlSourceMode === "LAB_DRIVER_DESK"}
              onManualApproved={() =>
                updateInstance(tid, (cur) => ({
                  ...cur,
                  drivingMode: "manual" as DrivingMode,
                }))
              }
              onManualRejected={() => console.log("人工接管被中控拒绝")}
              onTrainOfflined={handleTrainOfflined}
            />
          </div>
        );
      })}

      {/* Protocol704Panel */}
      {trainIds.map((tid) => (
        <div
          key={`p704-${tid}`}
          style={{ display: tid === activeTrainId ? undefined : "none" }}
        >
          <Protocol704Panel
            trainId={tid}
            enabled={true}
            onError={(msg) =>
              updateInstance(tid, (cur) => ({ ...cur, errorMessage: msg }))
            }
            onRealtimeState={(state, mode, ds) => {
              if (state.velocity == null || state.position == null) return;
              setDisplayFrame({ ...state, trainId: tid });
              updateInstance(tid, (cur) => ({
                ...cur,
                drivingMode: mode.toLowerCase() as DrivingMode,
                departureAuthorized: ds === "RUNNING",
              }));
            }}
            getCurrentState={() => {
              const cur = instancesRef.current.get(tid);
              const cs = displayFrame;
              if (!cs || !cur) return null;
              return {
                trainId: tid,
                fromStationId: cur.fromStationId,
                toStationId: cur.toStationId,
                currentState: cs,
                currentMode: cur.drivingMode,
                departureConfirmed: cur.departureAuthorized,
              };
            }}
            onExecutedState={(state, mode, ds, _latched) => {
              setDisplayFrame({ ...state, trainId: tid });
              updateInstance(tid, (cur) => ({
                ...cur,
                drivingMode: mode.toLowerCase() as DrivingMode,
                departureAuthorized: ds === "RUNNING",
              }));
            }}
          />
        </div>
      ))}

      {ai.status === "error" && (
        <div className="vehicle-error">
          <span>仿真失败：{ai.errorMessage}</span>
          <button onClick={() => handleStart(activeTrainId)}>重试</button>
        </div>
      )}

      {ai.allSafetyEvents.length > 0 && (
        <div className="vehicle-safety-bar" role="alert">
          <strong>⚠ 安全事件({ai.allSafetyEvents.length})：</strong>
          {ai.allSafetyEvents.slice(-3).map((ev, i) => (
            <span key={i} className="vehicle-safety-chip">
              {ev.reason} t={ev.time.toFixed(0)}s pos={ev.position.toFixed(0)}m
            </span>
          ))}
        </div>
      )}

      <div className="vehicle-main-area">
        <DriverCabView
          status={ai.status}
          currentState={viewState}
          startPosition={0}
          targetStopPosition={ai.targetStopPosition}
          speedLimit={ai.speedLimit}
          stopResult={null}
          safetyEventCount={ai.allSafetyEvents.length}
          isPaused={false}
          stationStops={ai.stationStops}
          externalDriveMode={ai.drivingMode}
          onRequestManual={() => handleRequestManual(activeTrainId)}
          onTractionLevel={(l, p) => handleTractionLevel(activeTrainId, l, p)}
          onBrakeLevel={(l, d, p) => handleBrakeLevel(activeTrainId, l, d, p)}
          onCoast={() => handleCoast(activeTrainId)}
          onEmergencyBrake={() => handleEmergencyBrake(activeTrainId)}
          onRequestAto={() => handleRequestAto(activeTrainId)}
          onResetEmergency={() => handleResetEmergency(activeTrainId)}
          canResetEmergency={canResetEmergency}
          handleResetToken={ai.handleResetToken}
          controlLocked={labDriverDeskMode}
          controlLockMessage="实验室司机台控制已启用，网页驾驶台只显示状态"
        />
        <div className="vehicle-right-panel">
          <div className="vehicle-right-tabs">
            <button
              className={`vehicle-right-tab ${rightPanelTab === "lineRun" ? "is-active" : ""}`}
              onClick={() => setRightPanelTab("lineRun")}
            >
              线路运行
            </button>
            <button
              className={`vehicle-right-tab ${rightPanelTab === "tractionCurve" ? "is-active" : ""}`}
              onClick={() => setRightPanelTab("tractionCurve")}
            >
              牵引曲线
            </button>
            <button
              className={`vehicle-right-tab ${rightPanelTab === "timetable" ? "is-active" : ""}`}
              onClick={() => setRightPanelTab("timetable")}
            >
              时刻表
            </button>
          </div>
          {rightPanelTab === "lineRun" && (
            <LineRunView
              trainId={activeTrainId}
              status={ai.status}
              currentState={viewState}
              startPosition={0}
              targetStopPosition={ai.targetStopPosition}
              speedLimit={ai.speedLimit}
              stopResult={null}
              positionOffset={ai.lineStartPosition}
              stationStops={ai.stationStops}
            />
          )}
          <div
            style={{
              display: rightPanelTab === "tractionCurve" ? undefined : "none",
              flex: 1,
              overflow: "auto",
            }}
          >
            <TractionCurveView
              currentState={viewState}
              status={ai.status}
              availableMotors={viewState?.availableMotors ?? 16}
              trainMass={ai.trainMass}
            />
          </div>
          {rightPanelTab === "timetable" && (
            <TimetableView
              currentState={viewState}
              status={ai.status}
              stationStops={ai.stationStops}
              fromStationId={ai.fromStationId}
              toStationId={ai.toStationId}
            />
          )}
        </div>
      </div>
    </div>
  );
}

export default Vehicle;
