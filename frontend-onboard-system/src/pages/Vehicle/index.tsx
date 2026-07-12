// 郭逸晨车载模块（成员三）—— Vehicle 页面
// 本轮改造：
// 1. 单页面多车载实例管理，无需打开多个浏览器 tab
// 2. 顶部训练号标签可切换不同实例，点击"＋"添加新车载
// 3. 每实例独立运行仿真、独立与总控通信

import { useCallback, useEffect, useRef, useState } from "react";
import {
  callVehicleControl,
  getAllSidingStatuses,
  reportOnboardEvent,
  runVehicleSimulation,
} from "../../api/vehicle";
import { disconnectProtocol704, resetProtocol704 } from "../../api/protocol704";
import type {
  DrivingMode,
  SafetyEvent,
  SidingStatus,
  SimulationResult,
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
const SPEED_MULTIPLIER_OPTIONS = [0.5, 1, 2, 4, 8] as const;
type SpeedMultiplier = (typeof SPEED_MULTIPLIER_OPTIONS)[number];
type PageStatus = "idle" | "loading" | "playing" | "finished" | "error";
type ControlSourceMode = "SIMULATION" | "LAB_DRIVER_DESK";

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

/** 单个车载实例的全部状态 */
interface InstanceState {
  trainId: string;
  status: PageStatus;
  errorMessage: string | null;
  result: SimulationResult | null;
  /** Increments whenever a continuation replaces the remaining trajectory. */
  trajectoryVersion: number;
  frameIndex: number;
  timerId: number | null; // setInterval ID
  isPaused: boolean;
  /** A dispatch HOLD is braking the train; pause only after its speed reaches zero. */
  holdAfterBraking: boolean;
  departureAuthorized: boolean;
  speedMultiplier: SpeedMultiplier;
  fromStationId: number;
  toStationId: number;
  drivingMode: DrivingMode;
  controlSourceMode: ControlSourceMode;
  allSafetyEvents: SafetyEvent[];
  handleResetToken: number;
  /** useRef 等效保持引用 (不触发重渲染) */
  stateRef: TrainState | null;
  frameIndexRef: number;
  fromStationIdRef: number;
  toStationIdRef: number;
  resultRef: SimulationResult | null;
  drivingModeRef: DrivingMode;
}

interface InstanceApi {
  state: InstanceState;
  setState: React.Dispatch<React.SetStateAction<InstanceState>>;
}

let nextAutoId = 1;

/** 从训练号解析数字后缀，如 "OB3" → 3；失败返回 null */
function parseTrainIdSuffix(trainId: string): number | null {
  const match = trainId.match(/^OB(\d+)$/);
  return match ? parseInt(match[1], 10) : null;
}

function createInstance(trainId: string): InstanceState {
  return {
    trainId,
    status: "idle",
    errorMessage: null,
    result: null,
    trajectoryVersion: 0,
    frameIndex: 0,
    timerId: null,
    isPaused: false,
    holdAfterBraking: false,
    departureAuthorized: false,
    speedMultiplier: 1,
    fromStationId: 1,
    toStationId: 2,
    drivingMode: "ato",
    controlSourceMode: "SIMULATION",
    allSafetyEvents: [],
    handleResetToken: 0,
    stateRef: null,
    frameIndexRef: 0,
    fromStationIdRef: 1,
    toStationIdRef: 2,
    resultRef: null,
    drivingModeRef: "ato",
  };
}

/** 获取唯一 ID，确保不与已有实例冲突 */
function getTrainId(existingTrainIds: string[]): string {
  while (true) {
    const candidate = `OB${nextAutoId++}`;
    if (!existingTrainIds.includes(candidate)) return candidate;
  }
}

function Vehicle() {
  // URL 参数作为初始 trainId
  const urlParam = new URLSearchParams(window.location.search).get("trainId");
  const urlTrainId = urlParam || getTrainId([]);

  // 若 URL 提供了 trainId，同步计数器避免后续冲突
  if (urlParam) {
    const suffix = parseTrainIdSuffix(urlParam);
    if (suffix !== null && suffix >= nextAutoId) {
      nextAutoId = suffix + 1;
    }
  }

  // 多实例存储
  const [instances, setInstances] = useState<Map<string, InstanceState>>(() => {
    const m = new Map<string, InstanceState>();
    m.set(urlTrainId, createInstance(urlTrainId));
    return m;
  });
  const [activeTrainId, setActiveTrainId] = useState<string>(urlTrainId);
  const [displayState, setDisplayState] = useState<TrainState | null>(null);
  const [rightPanelTab, setRightPanelTab] = useState<
    "lineRun" | "tractionCurve" | "timetable"
  >("lineRun");
  const [sidingStatuses, setSidingStatuses] = useState<SidingStatus[]>([]);

  // 父组件统一轮询，保证 2D/3D 视图消费同一份侧线快照。
  // 请求失败时保留上次已知数据；递归 setTimeout 避免慢请求重叠。
  useEffect(() => {
    let cancelled = false;
    let timer: number | null = null;

    const tick = async () => {
      try {
        const data = await getAllSidingStatuses();
        if (!cancelled && data.length > 0) {
          setSidingStatuses(data);
        }
      } catch {
        // 降级策略：保留上次成功快照，不清空视图状态。
      } finally {
        if (!cancelled) {
          timer = window.setTimeout(tick, 4000);
        }
      }
    };

    void tick();
    return () => {
      cancelled = true;
      if (timer !== null) {
        window.clearTimeout(timer);
      }
    };
  }, []);

  // 用 ref 跟踪最新 instances，供 unmount cleanup 使用
  const instancesRef = useRef(instances);
  instancesRef.current = instances;

  // ── 每实例可变引用（render 时同步，handler 中通过此 ref 获取最新值）──
  const instanceRefs = useRef<
    Map<
      string,
      {
        stateRef: TrainState | null;
        frameIndexRef: number;
        fromStationIdRef: number;
        toStationIdRef: number;
        resultRef: SimulationResult | null;
        drivingModeRef: DrivingMode;
      }
    >
  >(new Map());

  // 在 render 阶段同步所有实例的最新 ref 值
  {
    const nextRefs = new Map<
      string,
      typeof instanceRefs.current extends Map<any, infer V> ? V : never
    >();
    instances.forEach((inst, tid) => {
      const cs: TrainState | null =
        inst.result && inst.result.states.length > 0
          ? inst.result.states[inst.frameIndex]
          : null;
      nextRefs.set(tid, {
        stateRef: cs,
        frameIndexRef: inst.frameIndex,
        fromStationIdRef: inst.fromStationId,
        toStationIdRef: inst.toStationId,
        resultRef: inst.result,
        drivingModeRef: inst.drivingMode,
      });
    });
    instanceRefs.current = nextRefs;
  }

  // 获取当前活跃实例
  const activeInstance = instances.get(activeTrainId);

  // ── 实例状态更新辅助 ──
  const updateInstance = useCallback(
    (trainId: string, updater: (prev: InstanceState) => InstanceState) => {
      setInstances((prev) => {
        const next = new Map(prev);
        const current = next.get(trainId);
        if (current) next.set(trainId, updater(current));
        return next;
      });
    },
    [],
  );

  // ── 停止活跃实例的定时器 ──
  const stopTimer = useCallback((inst: InstanceState) => {
    if (inst.timerId !== null) {
      window.clearInterval(inst.timerId);
    }
  }, []);

  // ── 启动活跃实例的定时器 ──
  const startTimer = useCallback(
    (trainId: string, inst: InstanceState) => {
      stopTimer(inst);
      const dtPerFrame = inst.result?.summary.dtPerFrame ?? 0.5;
      const intervalMs = (dtPerFrame * 1000) / inst.speedMultiplier;
      const totalFrames = inst.result?.states.length ?? 0;

      const timerId = window.setInterval(() => {
        setInstances((prev) => {
          const next = new Map(prev);
          const cur = next.get(trainId);
          if (!cur) return prev;

          const nextIdx = cur.frameIndex + 1;
          if (nextIdx >= totalFrames - 1) {
            window.clearInterval(timerId);
            // A safety HOLD first plays the ATP braking trajectory.  It becomes a
            // held train only after the simulated speed has reached zero.
            if (cur.holdAfterBraking) {
              next.set(trainId, {
                ...cur,
                frameIndex: totalFrames - 1,
                isPaused: true,
                holdAfterBraking: false,
                timerId: null,
              });
            } else {
              next.set(trainId, {
                ...cur,
                frameIndex: totalFrames - 1,
                status: "finished",
                timerId: null,
              });
            }
            return next;
          }
          next.set(trainId, {
            ...cur,
            frameIndex: nextIdx,
            timerId: timerId as unknown as number,
          });
          return next;
        });
      }, intervalMs);

      updateInstance(trainId, (cur) => ({
        ...cur,
        timerId: timerId as unknown as number,
      }));
    },
    [stopTimer, updateInstance],
  );

  // ── 切换活跃车载：仅切换视图，不干涉其他实例的定时器 ──
  const switchToTrain = useCallback((newId: string) => {
    setActiveTrainId(newId);
  }, []);

  // ── 添加新实例 ──
  const addInstance = useCallback(() => {
    setInstances((prev) => {
      const existingIds = Array.from(prev.keys());
      const newId = getTrainId(existingIds);
      const next = new Map(prev);
      next.set(newId, createInstance(newId));
      // 需要在 setState 外设置 activeTrainId，用 setTimeout 保证在渲染后切换
      setTimeout(() => setActiveTrainId(newId), 0);
      return next;
    });
  }, []);

  // ── 删除实例（至少保留一个） ──
  const removeInstance = useCallback(
    (trainId: string) => {
      if (instances.size <= 1) return;
      const inst = instances.get(trainId);
      if (inst) stopTimer(inst);
      void disconnectProtocol704(trainId).catch(() => undefined);
      setInstances((prev) => {
        const next = new Map(prev);
        next.delete(trainId);
        return next;
      });
      if (activeTrainId === trainId) {
        const remaining = Array.from(instances.keys()).filter(
          (id) => id !== trainId,
        );
        setActiveTrainId(remaining[0] || activeTrainId);
      }
    },
    [instances, activeTrainId, stopTimer],
  );

  // ── 组件卸载清理所有定时器 ──
  useEffect(() => {
    return () => {
      instancesRef.current.forEach((inst) => {
        if (inst.timerId !== null) window.clearInterval(inst.timerId);
      });
    };
  }, []);

  // ── 定时器管理：检查所有实例，确保该跑的跑、该停的停 ──
  // 序列化与定时器相关的字段（不含 timerId 本身），避免 restart loop
  const timerDigest = Array.from(instances.entries())
    .map(
      ([id, inst]) =>
        `${id}:${inst.status}:${inst.isPaused}:${inst.speedMultiplier}:${inst.trajectoryVersion}`,
    )
    .join("|");

  useEffect(() => {
    instances.forEach((inst, trainId) => {
      const shouldRun =
        inst.status === "playing" &&
        inst.controlSourceMode === "SIMULATION" &&
        !inst.isPaused &&
        inst.result &&
        inst.result.states.length > 0;

      if (inst.timerId !== null && !shouldRun) {
        // 该停：timer 还在跑但实例已暂停/结束
        window.clearInterval(inst.timerId);
        updateInstance(trainId, (cur) => ({ ...cur, timerId: null }));
      } else if (inst.timerId === null && shouldRun) {
        // 该启：timer 未跑但实例应该播放
        startTimer(trainId, inst);
      } else if (inst.timerId !== null && shouldRun) {
        // 该跑但倍速可能变了 → 重启以应用新 interval
        window.clearInterval(inst.timerId);
        startTimer(trainId, { ...inst, timerId: null });
      }
    });
    // timerDigest 变化时重新评估所有实例
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timerDigest]);

  // ═══════════════════════════════════════════
  // 实例操作（handleStart / handleReset / 等）
  // 通过 trainId 操作指定实例
  // ═══════════════════════════════════════════

  const handleStart = useCallback(
    async (trainId: string) => {
      const inst = instances.get(trainId);
      if (!inst) return;

      updateInstance(trainId, (cur) => ({
        ...cur,
        status: "loading",
        errorMessage: null,
        result: null,
        trajectoryVersion: 0,
        frameIndex: 0,
        isPaused: true,
        holdAfterBraking: false,
        departureAuthorized: false,
        speedMultiplier: 1,
        drivingMode: "ato",
        allSafetyEvents: [],
        handleResetToken: cur.handleResetToken + 1,
      }));

      try {
        const simulationResult = await runVehicleSimulation({
          trainId,
          fromStationId: inst.fromStationId,
          toStationId: inst.toStationId,
          hardwareControlEnabled: inst.controlSourceMode === "LAB_DRIVER_DESK",
        });
        if (!simulationResult.states || simulationResult.states.length === 0) {
          throw new Error("后端返回的仿真结果不包含任何 states 数据");
        }
        updateInstance(trainId, (cur) => ({
          ...cur,
          result: {
            ...simulationResult,
            states: simulationResult.states.map((state) => ({
              ...state,
              trainId,
            })),
          },
          allSafetyEvents: simulationResult.safetyEvents ?? [],
          // A physical desk owns the clock. Keep the prepared train at its
          // start state until a valid PLC frame advances it.
          status: "playing",
          frameIndex: 0,
          isPaused: cur.controlSourceMode === "LAB_DRIVER_DESK",
        }));
      } catch (err) {
        updateInstance(trainId, (cur) => ({
          ...cur,
          status: "error",
          errorMessage: err instanceof Error ? err.message : String(err),
        }));
      }
    },
    [instances, updateInstance],
  );

  const handleControl = useCallback(
    async (
      trainId: string,
      command: string,
      targetDecel: number,
      mode: DrivingMode,
      levelPercent = 0,
    ) => {
      const refs = instanceRefs.current.get(trainId);
      if (!refs) return;
      const {
        stateRef: cs,
        frameIndexRef: fi,
        fromStationIdRef: fromId,
        toStationIdRef: toId,
        resultRef: res,
      } = refs;
      if (!cs || !res) return;

      const totalTarget = res.stopResult?.targetStopPosition ?? 0;

      try {
        const controlResult = await callVehicleControl({
          trainId,
          fromStationId: fromId,
          toStationId: toId,
          currentState: cs,
          currentMode: mode,
          controlCommand: { command, targetDecel, levelPercent },
          totalTargetPosition: totalTarget,
        });

        updateInstance(trainId, (cur) => {
          if (!cur.result) return cur;
          const before = cur.result.states.slice(0, fi + 1);
          return {
            ...cur,
            result: {
              ...cur.result,
              states: [
                ...before,
                ...controlResult.states.map((s) => ({ ...s, trainId })),
              ],
              stopResult: controlResult.stopResult,
              safetyEvents: [
                ...(cur.result.safetyEvents ?? []),
                ...(controlResult.safetyEvents ?? []),
              ],
              stationStops: controlResult.stationStops,
              summary: {
                ...cur.result.summary,
                currentMode:
                  controlResult.summary.currentMode ??
                  cur.result.summary.currentMode,
                nextMode:
                  controlResult.summary.nextMode ?? cur.result.summary.nextMode,
              },
            },
            allSafetyEvents: [
              ...cur.allSafetyEvents,
              ...(controlResult.safetyEvents ?? []),
            ],
            trajectoryVersion: cur.trajectoryVersion + 1,
            drivingMode: controlResult.summary.currentMode ?? cur.drivingMode,
            handleResetToken:
              command === "resume_ato" || command === "reset_emergency"
                ? cur.handleResetToken + 1
                : cur.handleResetToken,
            status: "playing",
          };
        });
      } catch (err) {
        updateInstance(trainId, (cur) => ({
          ...cur,
          errorMessage: err instanceof Error ? err.message : String(err),
        }));
      }
    },
    [updateInstance],
  );

  const handleReset = useCallback(
    (trainId: string) => {
      void resetProtocol704(trainId).catch(() => undefined);
      updateInstance(trainId, (cur) => ({
        ...cur,
        status: "idle",
        errorMessage: null,
        result: null,
        trajectoryVersion: 0,
        frameIndex: 0,
        isPaused: false,
        holdAfterBraking: false,
        departureAuthorized: false,
        speedMultiplier: 1,
        drivingMode: "ato",
        controlSourceMode: "SIMULATION",
        allSafetyEvents: [],
        handleResetToken: cur.handleResetToken + 1,
      }));
    },
    [updateInstance],
  );

  const setControlSourceMode = useCallback(
    (trainId: string, mode: ControlSourceMode) => {
      const current = instances.get(trainId);
      if (!current || current.controlSourceMode === mode) return;
      if (mode === "SIMULATION") {
        // Stop sockets and erase the server-side PLC context before web controls
        // become available again.
        void resetProtocol704(trainId).catch(() => undefined);
      }
      updateInstance(trainId, (cur) => ({
        ...cur,
        controlSourceMode: mode,
        isPaused: mode === "LAB_DRIVER_DESK" ? true : cur.isPaused,
        departureAuthorized: mode === "LAB_DRIVER_DESK" ? false : cur.departureAuthorized,
        handleResetToken: cur.handleResetToken + 1,
      }));
      setDisplayState((state) => state?.trainId === trainId ? null : state);
    },
    [instances, updateInstance],
  );

  const handleTogglePause = useCallback(
    (trainId: string) => {
      updateInstance(trainId, (cur) => {
        if (cur.controlSourceMode === "LAB_DRIVER_DESK") return cur;
        if (cur.status !== "playing" || !cur.departureAuthorized) return cur;
        return { ...cur, isPaused: !cur.isPaused };
      });
    },
    [updateInstance],
  );

  const handleDepartAuthorized = useCallback(
    (trainId: string) => {
      updateInstance(trainId, (cur) => ({
        ...cur,
        departureAuthorized: true,
        isPaused: false,
      }));
    },
    [updateInstance],
  );

  const handleDispatchHold = useCallback(
    (trainId: string) => {
      const refs = instanceRefs.current.get(trainId);
      if (!refs?.stateRef || refs.stateRef.velocity <= 0.05) {
        updateInstance(trainId, (cur) => ({
          ...cur,
          isPaused: true,
          holdAfterBraking: false,
        }));
        return;
      }

      // Do not freeze a moving train.  The safety command replaces the remaining
      // local trajectory with an ATP emergency-brake curve, then holds at zero.
      updateInstance(trainId, (cur) => ({
        ...cur,
        holdAfterBraking: true,
        isPaused: false,
      }));
      handleControl(trainId, "atp_emergency_brake", 0, refs.drivingModeRef);
    },
    [updateInstance, handleControl],
  );

  const handleDispatchRecovery = useCallback(
    (trainId: string) => {
      updateInstance(trainId, (cur) => ({
        ...cur,
        drivingMode: "ato",
        isPaused: false,
        holdAfterBraking: false,
        status: "playing",
      }));
      handleControl(trainId, "traction", 0, "ato");
    },
    [updateInstance, handleControl],
  );

  const handleRequestManual = useCallback(
    (trainId: string) => {
      updateInstance(trainId, (cur) => ({ ...cur, drivingMode: "manual" }));
    },
    [updateInstance],
  );

  const handleTractionLevel = useCallback(
    (trainId: string, _level: number, levelPercent: number) => {
      const refs = instanceRefs.current.get(trainId);
      handleControl(
        trainId,
        "traction",
        0,
        refs?.drivingModeRef ?? "manual",
        levelPercent,
      );
    },
    [handleControl],
  );

  const handleBrakeLevel = useCallback(
    (
      trainId: string,
      _level: number,
      targetDecel: number,
      levelPercent: number,
    ) => {
      const refs = instanceRefs.current.get(trainId);
      handleControl(
        trainId,
        "brake",
        targetDecel,
        refs?.drivingModeRef ?? "manual",
        levelPercent,
      );
    },
    [handleControl],
  );

  const handleCoast = useCallback(
    (trainId: string) => {
      const refs = instanceRefs.current.get(trainId);
      handleControl(trainId, "coast", 0, refs?.drivingModeRef ?? "manual");
    },
    [handleControl],
  );

  const handleRequestAto = useCallback(
    (trainId: string) => {
      handleControl(trainId, "resume_ato", 0, "manual");
    },
    [handleControl],
  );

  const handleResetEmergency = useCallback(
    (trainId: string) => {
      handleControl(trainId, "reset_emergency", 0, "emergency");
    },
    [handleControl],
  );

  const handleEmergencyBrake = useCallback(
    (trainId: string) => {
      const refs = instanceRefs.current.get(trainId);
      if (!refs) return;
      const {
        drivingModeRef: prevMode,
        stateRef: state,
        resultRef: res,
      } = refs;
      updateInstance(trainId, (cur) => ({ ...cur, drivingMode: "emergency" }));
      handleControl(trainId, "emergency_brake", 0, prevMode);
      if (state) {
        const lineStart = res?.summary.lineStartPosition ?? 0;
        void reportOnboardEvent({
          trainId,
          eventType: "ATP_EB_TRIGGERED",
          timestampSeconds: state.time,
          positionMeters: state.absolutePosition ?? lineStart + state.position,
          speedKmh: state.velocity * 3.6,
          severity: "CRITICAL",
          details: "车载端触发紧急制动，请求总控保持停车并重新评估运行策略",
        });
      }
    },
    [updateInstance, handleControl],
  );

  const ai = activeInstance ?? createInstance(activeTrainId);
  const currentState: TrainState | null =
    ai.result && ai.result.states.length > 0
      ? ai.result.states[ai.frameIndex]
      : null;
  const viewState =
    displayState?.trainId === activeTrainId ? displayState : currentState;
  const canResetEmergency =
    ai.drivingMode === "emergency" && ai.result?.summary?.nextMode === "manual";
  const targetStopPosition = ai.result?.stopResult?.targetStopPosition ?? 1200;
  const speedLimitValue =
    ai.result?.summary?.speedLimit ?? DEMO_SPEED_LIMIT_FALLBACK_MS;
  const lineStartPosition = ai.result?.summary?.lineStartPosition ?? 0;
  const fromStationName = ai.result?.summary?.fromStationName ?? null;
  const toStationName = ai.result?.summary?.toStationName ?? null;
  const isLoading = ai.status === "loading";
  const isPlaying = ai.status === "playing";
  const canReset = ai.result !== null || ai.status === "error";
  const labDriverDeskMode = ai.controlSourceMode === "LAB_DRIVER_DESK";
  const toStationOptions = STATIONS.filter(
    (s) => s.stationId > ai.fromStationId,
  );
  const totalStations = ai.result?.summary?.totalStations;
  const completedStops = ai.result?.summary?.completedStops;

  const trainIds = Array.from(instances.keys());

  useEffect(() => {
    const states = ai.result?.states;
    if (!states || states.length === 0) {
      setDisplayState(null);
      return undefined;
    }

    const frame = states[ai.frameIndex];
    if (
      ai.status !== "playing" ||
      ai.isPaused ||
      ai.frameIndex >= states.length - 1
    ) {
      setDisplayState(frame);
      return undefined;
    }

    const nextFrame = states[ai.frameIndex + 1];
    const span = nextFrame.time - frame.time;
    const startedAt = performance.now();
    let rafId = 0;

    const tick = () => {
      const elapsedSeconds = (performance.now() - startedAt) / 1000;
      const fraction = clamp(
        span > 0 ? (elapsedSeconds * ai.speedMultiplier) / span : 1,
        0,
        1,
      );
      setDisplayState({
        time: frame.time + span * fraction,
        position:
          frame.position + (nextFrame.position - frame.position) * fraction,
        velocity:
          frame.velocity + (nextFrame.velocity - frame.velocity) * fraction,
        acceleration:
          frame.acceleration +
          (nextFrame.acceleration - frame.acceleration) * fraction,
        phase: frame.phase,
        trainId: frame.trainId,
        absolutePosition:
          frame.absolutePosition !== undefined &&
          nextFrame.absolutePosition !== undefined
            ? frame.absolutePosition +
              (nextFrame.absolutePosition - frame.absolutePosition) * fraction
            : frame.absolutePosition,
        tractionForce:
          (frame.tractionForce ?? 0) +
          ((nextFrame.tractionForce ?? 0) - (frame.tractionForce ?? 0)) *
            fraction,
        brakeForce:
          (frame.brakeForce ?? 0) +
          ((nextFrame.brakeForce ?? 0) - (frame.brakeForce ?? 0)) * fraction,
        availableMotors: frame.availableMotors,
      });
      if (fraction < 1) {
        rafId = window.requestAnimationFrame(tick);
      }
    };

    rafId = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(rafId);
  }, [
    activeTrainId,
    ai.result,
    ai.frameIndex,
    ai.status,
    ai.isPaused,
    ai.speedMultiplier,
    ai.trajectoryVersion,
  ]);

  if (!activeInstance) {
    return (
      <div className="vehicle-page">
        <div className="vehicle-empty">暂无车载实例，请点击"＋"添加</div>
      </div>
    );
  }

  return (
    <div className="vehicle-page">
      {/* ═══ 多实例标签栏 ═══ */}
      <div className="vehicle-tab-bar">
        <div className="vehicle-tab-list">
          {trainIds.map((tid) => {
            const inst = instances.get(tid);
            const isActive = tid === activeTrainId;
            const statusIcon =
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
                  type="button"
                  className="vehicle-tab-btn"
                  onClick={() => switchToTrain(tid)}
                  title={`切换到 ${tid}`}
                >
                  <span className="vehicle-tab-dot">{statusIcon}</span>
                  <span>{tid}</span>
                </button>
                {trainIds.length > 1 && (
                  <button
                    type="button"
                    className="vehicle-tab-close"
                    onClick={(e) => {
                      e.stopPropagation();
                      removeInstance(tid);
                    }}
                    title={`关闭 ${tid}`}
                  >
                    ×
                  </button>
                )}
              </div>
            );
          })}
        </div>
        <button
          type="button"
          className="vehicle-tab-add"
          onClick={addInstance}
          title="添加新车载实例"
        >
          ＋
        </button>
      </div>

      {/* ═══ Header（活跃实例的操作栏）═══ */}
      <header className="vehicle-page__header">
        <div>
          <p className="vehicle-page__eyebrow">Onboard cab HMI</p>
          <h2>车载驾驶台系统 · {activeTrainId}</h2>
        </div>
        <div className="vehicle-page__actions">
          {/* 起止站选择器 */}
          <div className="vehicle-station-selector" aria-label="起止站选择">
            <label
              htmlFor="from-station-select"
              className="vehicle-station-label"
            >
              出发站
            </label>
            <select
              id="from-station-select"
              className="vehicle-station-select"
              value={ai.fromStationId}
              disabled={isLoading || isPlaying}
              onChange={(e) => {
                const newFrom = Number(e.target.value);
                updateInstance(activeTrainId, (cur) => {
                  let newTo = cur.toStationId;
                  if (newTo <= newFrom) {
                    const nextId = STATIONS.find(
                      (s) => s.stationId > newFrom,
                    )?.stationId;
                    if (nextId !== undefined) newTo = nextId;
                  }
                  return { ...cur, fromStationId: newFrom, toStationId: newTo };
                });
              }}
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

            <label
              htmlFor="to-station-select"
              className="vehicle-station-label"
            >
              目标站
            </label>
            <select
              id="to-station-select"
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

          <div className="vehicle-control-mode" role="group" aria-label="控制来源">
            <span>控制来源</span>
            <button
              type="button"
              className={!labDriverDeskMode ? "is-active" : ""}
              disabled={isLoading || isPlaying}
              onClick={() => setControlSourceMode(activeTrainId, "SIMULATION")}
            >
              软件仿真
            </button>
            <button
              type="button"
              className={labDriverDeskMode ? "is-active" : ""}
              disabled={isLoading || isPlaying}
              onClick={() => setControlSourceMode(activeTrainId, "LAB_DRIVER_DESK")}
            >
              实验室司机台
            </button>
          </div>

          <button
            className="vehicle-start-btn"
            onClick={() => handleStart(activeTrainId)}
            disabled={isLoading || isPlaying}
            type="button"
          >
            {isLoading
              ? "准备中..."
              : ai.status === "finished" || ai.status === "error"
                ? "重新上线"
                : "上线并等待发车"}
          </button>
          <button
            className="vehicle-secondary-btn"
            onClick={() => handleTogglePause(activeTrainId)}
            disabled={labDriverDeskMode || !isPlaying || !ai.departureAuthorized}
            type="button"
          >
            {!ai.departureAuthorized && isPlaying
              ? "等待调度发车"
              : ai.isPaused
                ? "继续"
                : "暂停"}
          </button>
          <button
            className="vehicle-ghost-btn"
            onClick={() => handleReset(activeTrainId)}
            disabled={!canReset || isLoading}
            type="button"
          >
            复位
          </button>

          <div className="vehicle-speed-group" aria-label="播放倍速">
            {SPEED_MULTIPLIER_OPTIONS.map((m) => (
              <button
                key={m}
                type="button"
                className={`vehicle-speed-btn${ai.speedMultiplier === m ? " is-active" : ""}`}
                onClick={() =>
                  updateInstance(activeTrainId, (cur) => ({
                    ...cur,
                    speedMultiplier: m,
                  }))
                }
                disabled={labDriverDeskMode || (!ai.result && ai.status !== "playing")}
                aria-pressed={ai.speedMultiplier === m}
              >
                {m}x
              </button>
            ))}
          </div>

          {/* 区间 / 多站信息 */}
          {fromStationName && toStationName ? (
            <span className="vehicle-section-label">
              {fromStationName} → {toStationName}
              {totalStations && totalStations > 2
                ? ` (${completedStops}/${totalStations - 1}站)`
                : ""}
            </span>
          ) : (
            <span className="vehicle-section-label vehicle-section-label--pending">
              {(STATIONS.find((s) => s.stationId === ai.fromStationId)
                ?.displayNameOverride ??
                STATIONS.find((s) => s.stationId === ai.fromStationId)
                  ?.displayName) ||
                `站${ai.fromStationId}`}
              {" → "}
              {(STATIONS.find((s) => s.stationId === ai.toStationId)
                ?.displayNameOverride ??
                STATIONS.find((s) => s.stationId === ai.toStationId)
                  ?.displayName) ||
                `站${ai.toStationId}`}
            </span>
          )}

          {isPlaying && viewState && (
            <span className="vehicle-status-text">
              {labDriverDeskMode ? "司机台同步" : ai.isPaused ? "已暂停" : "播放中"}
              {!labDriverDeskMode && <> · {ai.frameIndex + 1}/{ai.result?.states.length}</>}
              {" · "}t={viewState.time.toFixed(1)}s{" · "}模式:
              {ai.drivingMode.toUpperCase()}
            </span>
          )}
          {ai.status === "finished" && (
            <span className="vehicle-status-text">仿真完成</span>
          )}
        </div>
      </header>

      {/* ═══ 所有实例的总控联调面板（隐藏非活跃实例，但保持挂载以维持通信）═══ */}
      {trainIds.map((tid) => {
        const inst = instances.get(tid)!;
        const instState: TrainState | null =
          inst.controlSourceMode === "LAB_DRIVER_DESK" && displayState?.trainId === tid
            ? displayState
            : inst.result && inst.result.states.length > 0
              ? inst.result.states[inst.frameIndex]
              : null;
        const instFromName =
          inst.result?.summary?.fromStationName ??
          STATIONS.find((s) => s.stationId === inst.fromStationId)
            ?.displayNameOverride ??
          STATIONS.find((s) => s.stationId === inst.fromStationId)
            ?.displayName ??
          String(inst.fromStationId);
        const instToName =
          inst.result?.summary?.toStationName ??
          STATIONS.find((s) => s.stationId === inst.toStationId)
            ?.displayNameOverride ??
          STATIONS.find((s) => s.stationId === inst.toStationId)?.displayName ??
          String(inst.toStationId);
        const instLineStart = inst.result?.summary?.lineStartPosition ?? 0;
        const isActive = tid === activeTrainId;

        return (
          <div
            key={`ip-${tid}`}
            style={{ display: isActive ? undefined : "none" }}
          >
            <IntegrationPanel
              trainId={tid}
              localState={instState}
              pageStatus={inst.status}
              paused={inst.isPaused}
              fromStationId={inst.fromStationId}
              toStationId={inst.toStationId}
              fromStationName={instFromName}
              toStationName={instToName}
              lineStartPosition={instLineStart}
              drivingMode={inst.drivingMode}
              departureState={inst.result?.summary?.departureState}
              departureAuthorized={inst.departureAuthorized}
              onDepartAuthorized={() => handleDepartAuthorized(tid)}
              onDispatchHold={() => handleDispatchHold(tid)}
              onDispatchRecovery={() => handleDispatchRecovery(tid)}
              externalControl={inst.controlSourceMode === "LAB_DRIVER_DESK"}
            />
          </div>
        );
      })}

      {trainIds.map((tid) => (
        <div
          key={`p704-${tid}`}
          style={{ display: tid === activeTrainId ? undefined : "none" }}
        >
          <Protocol704Panel
            trainId={tid}
            enabled={instances.get(tid)?.controlSourceMode === "LAB_DRIVER_DESK"}
            onError={(message) => {
              updateInstance(tid, (cur) => ({ ...cur, errorMessage: message }));
            }}
            onExecutedState={(state, mode, departureState) => {
              setDisplayState({ ...state, trainId: tid });
              if (mode === "MANUAL" || mode === "EMERGENCY" || mode === "ATO") {
                updateInstance(tid, (cur) => ({
                  ...cur,
                  drivingMode: mode.toLowerCase() as DrivingMode,
                  // PLC frames are the only physical clock in laboratory mode.
                  // Keep the local trajectory paused even after departure is granted.
                  isPaused: cur.controlSourceMode === "LAB_DRIVER_DESK"
                    ? true
                    : departureState !== "RUNNING",
                  departureAuthorized: departureState === "RUNNING",
                }));
              }
            }}
          />
        </div>
      ))}

      {ai.status === "error" && (
        <div className="vehicle-error">
          <span>仿真失败：{ai.errorMessage}</span>
          <button onClick={() => handleStart(activeTrainId)} type="button">
            重试
          </button>
        </div>
      )}

      {/* SafetyEvent 紧凑展示区 */}
      {ai.allSafetyEvents.length > 0 && (
        <div className="vehicle-safety-bar" role="alert" aria-label="安全事件">
          <strong>⚠ 安全事件({ai.allSafetyEvents.length})：</strong>
          {ai.allSafetyEvents.slice(-3).map((ev, i) => (
            <span key={i} className="vehicle-safety-chip">
              {ev.reason} t={ev.time.toFixed(0)}s pos={ev.position.toFixed(0)}m
              v={ev.velocity.toFixed(1)}m/s [{ev.action}]
            </span>
          ))}
          {ai.allSafetyEvents.length > 3 && (
            <span className="vehicle-safety-chip">…</span>
          )}
        </div>
      )}

      {/* 主内容区 */}
      <div className="vehicle-main-area">
        <DriverCabView
          status={ai.status}
          currentState={viewState}
          startPosition={0}
          targetStopPosition={targetStopPosition}
          speedLimit={speedLimitValue}
          stopResult={ai.result?.stopResult ?? null}
          safetyEventCount={ai.allSafetyEvents.length}
          isPaused={labDriverDeskMode ? false : ai.isPaused}
          stationStops={ai.result?.stationStops}
          externalDriveMode={ai.drivingMode}
          onRequestManual={() => handleRequestManual(activeTrainId)}
          onTractionLevel={(level, percent) =>
            handleTractionLevel(activeTrainId, level, percent)
          }
          onBrakeLevel={(level, decel, percent) =>
            handleBrakeLevel(activeTrainId, level, decel, percent)
          }
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
              type="button"
              className={`vehicle-right-tab ${rightPanelTab === "lineRun" ? "is-active" : ""}`}
              onClick={() => setRightPanelTab("lineRun")}
            >
              线路运行
            </button>
            <button
              type="button"
              className={`vehicle-right-tab ${rightPanelTab === "tractionCurve" ? "is-active" : ""}`}
              onClick={() => setRightPanelTab("tractionCurve")}
            >
              牵引曲线
            </button>
            <button
              type="button"
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
              targetStopPosition={targetStopPosition}
              speedLimit={speedLimitValue}
              stopResult={ai.result?.stopResult ?? null}
              positionOffset={lineStartPosition}
              stationStops={ai.result?.stationStops}
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
              trainMass={ai.result?.summary?.trainMass ?? 225_000}
            />
          </div>
          {rightPanelTab === "timetable" && (
            <TimetableView
              currentState={viewState}
              status={ai.status}
              stationStops={ai.result?.stationStops}
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
