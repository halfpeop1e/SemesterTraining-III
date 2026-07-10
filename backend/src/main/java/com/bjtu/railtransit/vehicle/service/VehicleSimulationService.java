package com.bjtu.railtransit.vehicle.service;

import com.bjtu.railtransit.vehicle.dto.SafetyEvent;
import com.bjtu.railtransit.vehicle.dto.SimulationControlRequest;
import com.bjtu.railtransit.vehicle.dto.SimulationResult;
import com.bjtu.railtransit.vehicle.dto.SimulationSummary;
import com.bjtu.railtransit.vehicle.dto.StopResult;
import com.bjtu.railtransit.vehicle.dto.TrainState;
import com.bjtu.railtransit.vehicle.enums.DrivingMode;
import com.bjtu.railtransit.vehicle.enums.SimulationPhase;
import com.bjtu.railtransit.vehicle.enums.StopWindowState;
import com.bjtu.railtransit.vehicle.model.LineProfile;
import com.bjtu.railtransit.vehicle.model.ScenarioConfig;
import com.bjtu.railtransit.vehicle.model.TrainModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class VehicleSimulationService {

    private static final double VELOCITY_EPSILON = 1.0e-3;
    private static final double STOP_POSITION_TOLERANCE = 0.5;
    private static final double STOP_VELOCITY_TOLERANCE = 0.1;
    private static final int MAX_STEPS = 100_000;
    private static final double GRAVITY = 9.80665;
    private static final double KMH_PER_MS = 3.6;
    private static final int SUB_STEPS_PER_SAMPLE = 20;
    private static final int MAX_PREDICTION_STEPS = 20_000;

    private final DemoScenarioProvider demoScenarioProvider;

    public VehicleSimulationService(DemoScenarioProvider demoScenarioProvider) {
        this.demoScenarioProvider = demoScenarioProvider;
    }

    public SimulationResult runDemoSimulation() {
        return run(demoScenarioProvider.getDemoScenario());
    }

    public SimulationResult run(ScenarioConfig scenario) {
        LineProfile line = scenario.getLineProfile();
        TrainModel train = scenario.getTrainModel();
        double dt = scenario.getDt();
        double dtSub = dt / SUB_STEPS_PER_SAMPLE;

        double targetStopPosition = line.getTargetStopPosition();
        double speedLimit = line.getSpeedLimit();

        List<TrainState> states = new ArrayList<>();

        double t = 0.0;
        double pos = line.getStartPosition();
        double v = 0.0;

        boolean brakingTriggered = false;
        boolean coastEntered = false;
        double brakeResponseRemaining = 0.0;
        double brakeTriggerPosition = 0.0;
        double predictedStopPositionAtTrigger = 0.0;

        for (int step = 0; step < MAX_STEPS; step++) {
            if (brakingTriggered && v <= VELOCITY_EPSILON) {
                states.add(new TrainState(t, pos, 0.0, 0.0, SimulationPhase.STOPPED, "T1"));
                break;
            }

            double sampleT = t;
            double samplePos = pos;
            double sampleV = v;
            SimulationPhase samplePhase = null;
            double sampleAcceleration = 0.0;

            for (int sub = 0; sub < SUB_STEPS_PER_SAMPLE; sub++) {
                SimulationPhase phase;
                double acceleration;
                double vNext;
                double posNext;

                if (brakingTriggered && v <= VELOCITY_EPSILON) {
                    phase = SimulationPhase.STOPPED;
                    acceleration = 0.0;
                    vNext = 0.0;
                    posNext = pos;
                } else if (!brakingTriggered) {
                    double predictedStop = predictStopPosition(pos, v, train, line, dtSub);
                    if (predictedStop >= targetStopPosition) {
                        brakingTriggered = true;
                        brakeTriggerPosition = pos;
                        predictedStopPositionAtTrigger = predictedStop;
                        brakeResponseRemaining = train.getBrakeResponseTime();
                    }

                    if (brakingTriggered) {
                        phase = SimulationPhase.BRAKING;
                        double drag = computeNetDrag(pos, v, train, line);
                        if (brakeResponseRemaining > 0) {
                            acceleration = -drag;
                            brakeResponseRemaining -= dtSub;
                        } else {
                            acceleration = -train.getNormalBrakeDeceleration() - drag;
                        }
                    } else if (!coastEntered && v < speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.TRACTION;
                        double drag = computeNetDrag(pos, v, train, line);
                        acceleration = train.getMaxAcceleration() - drag;
                    } else {
                        coastEntered = true;
                        phase = SimulationPhase.COAST;
                        double drag = computeNetDrag(pos, v, train, line);
                        acceleration = -drag;
                    }

                    vNext = v + acceleration * dtSub;
                    if (vNext < 0.0) {
                        vNext = 0.0;
                    }
                    if (phase == SimulationPhase.TRACTION && vNext >= speedLimit) {
                        vNext = speedLimit;
                        coastEntered = true;
                    }
                    posNext = pos + 0.5 * (v + vNext) * dtSub;
                } else {
                    phase = SimulationPhase.BRAKING;
                    double drag = computeNetDrag(pos, v, train, line);
                    if (brakeResponseRemaining > 0) {
                        acceleration = -drag;
                        brakeResponseRemaining -= dtSub;
                    } else {
                        acceleration = -train.getNormalBrakeDeceleration() - drag;
                    }

                    vNext = v + acceleration * dtSub;
                    if (vNext < 0.0) {
                        vNext = 0.0;
                    }
                    posNext = pos + 0.5 * (v + vNext) * dtSub;
                }

                if (sub == 0) {
                    samplePhase = phase;
                    sampleAcceleration = acceleration;
                }

                t += dtSub;
                pos = posNext;
                v = vNext;
            }

            states.add(new TrainState(sampleT, samplePos, sampleV, sampleAcceleration, samplePhase, "T1"));
        }

        if (states.isEmpty() || states.get(states.size() - 1).getPhase() != SimulationPhase.STOPPED) {
            throw new IllegalStateException("车辆仿真未在最大步数内收敛到停车状态，请检查演示配置参数");
        }

        return buildResult(states, targetStopPosition, speedLimit, dt, brakeTriggerPosition, predictedStopPositionAtTrigger);
    }

    private double predictStopPosition(double pos, double v, TrainModel train, LineProfile line, double dtPredict) {
        double p = pos;
        double vel = v;
        double responseRemaining = train.getBrakeResponseTime();
        for (int i = 0; i < MAX_PREDICTION_STEPS; i++) {
            if (vel <= VELOCITY_EPSILON) {
                return p;
            }
            double drag = computeNetDrag(p, vel, train, line);
            double a;
            if (responseRemaining > 0) {
                a = -drag;
                responseRemaining -= dtPredict;
            } else {
                a = -train.getNormalBrakeDeceleration() - drag;
            }
            double velNext = vel + a * dtPredict;
            if (velNext < 0.0) {
                velNext = 0.0;
            }
            double pNext = p + 0.5 * (vel + velNext) * dtPredict;
            p = pNext;
            vel = velNext;
        }
        return p;
    }

    private double computeNetDrag(double pos, double v, TrainModel train, LineProfile line) {
        double vKmh = v * KMH_PER_MS;
        double w0 = train.getDavisA() + train.getDavisB() * vKmh + train.getDavisC() * vKmh * vKmh;
        double resistanceDecel = w0 * GRAVITY / 1000.0;
        double gradeDecel = GRAVITY * line.gradientAt(pos);
        return resistanceDecel + gradeDecel;
    }

    private SimulationResult buildResult(List<TrainState> states, double targetStopPosition, double speedLimit,
                                           double dtPerFrame,
                                           double brakeTriggerPosition, double predictedStopPosition) {
        double maxVelocity = 0.0;
        for (TrainState state : states) {
            if (state.getVelocity() > maxVelocity) {
                maxVelocity = state.getVelocity();
            }
        }

        TrainState last = states.get(states.size() - 1);
        SimulationSummary summary = new SimulationSummary(maxVelocity, last.getTime(), last.getPosition(), speedLimit, dtPerFrame);

        double actualStopPosition = last.getPosition();
        double stopError = actualStopPosition - targetStopPosition;
        boolean success = Math.abs(stopError) <= STOP_POSITION_TOLERANCE
                && last.getVelocity() <= STOP_VELOCITY_TOLERANCE;
        String reason = success
                ? null
                : String.format(
                        "停车误差 %.3fm 或末速度 %.3fm/s 超出参考阈值（位置容差 %.1fm，速度容差 %.1fm/s）。"
                                + "阶段2引入真实阻力/坡度后停站误差如实报告，重新收敛到该阈值留待阶段3实现",
                        stopError, last.getVelocity(), STOP_POSITION_TOLERANCE, STOP_VELOCITY_TOLERANCE);

        StopWindowState stopWindowState = deriveStopWindowState(stopError, last.getVelocity());

        StopResult stopResult = new StopResult(
                targetStopPosition, actualStopPosition, stopError, success, reason, stopWindowState,
                brakeTriggerPosition, predictedStopPosition);

        List<SafetyEvent> safetyEvents = Collections.emptyList();

        return new SimulationResult(states, summary, stopResult, safetyEvents);
    }

    private StopWindowState deriveStopWindowState(double stopError, double finalVelocity) {
        if (finalVelocity > STOP_VELOCITY_TOLERANCE) {
            return StopWindowState.NOT_ACCURATE;
        }
        if (Math.abs(stopError) <= STOP_POSITION_TOLERANCE) {
            return StopWindowState.IN_WINDOW;
        }
        if (stopError > STOP_POSITION_TOLERANCE) {
            return StopWindowState.OVERSHOOT;
        }
        return StopWindowState.UNDERSHOOT;
    }

    static final double DEFAULT_DWELL_TIME_SECONDS = 30.0;

    public SimulationResult runMultiStation(
            java.util.List<LineProfileJsonLoader.StationEntry> stationEntries,
            Double dwellTimeSeconds,
            DemoScenarioProvider demoProvider) {

        if (stationEntries == null || stationEntries.size() < 2) {
            throw new IllegalArgumentException("多站仿真至少需要 2 个站点");
        }

        double dwell = dwellTimeSeconds != null ? dwellTimeSeconds : DEFAULT_DWELL_TIME_SECONDS;
        double dt = 0.5;

        java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> allStates = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.SafetyEvent> allSafetyEvents = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.StationStop> stationStops = new java.util.ArrayList<>();

        double lineStartAbsM = stationEntries.get(0).km * 1000.0;
        double timeOffset = 0.0;
        double maxVelocityAll = 0.0;
        com.bjtu.railtransit.vehicle.dto.StopResult finalStopResult = null;

        double[] scheduledCumPos = new double[stationEntries.size()];
        scheduledCumPos[0] = 0.0;
        for (int i = 1; i < stationEntries.size(); i++) {
            scheduledCumPos[i] = (stationEntries.get(i).km - stationEntries.get(0).km) * 1000.0;
        }
        double totalTargetPosition = scheduledCumPos[stationEntries.size() - 1];

        double actualCumPosition = 0.0;

        for (int seg = 0; seg < stationEntries.size() - 1; seg++) {
            LineProfileJsonLoader.StationEntry fromSt = stationEntries.get(seg);
            LineProfileJsonLoader.StationEntry toSt = stationEntries.get(seg + 1);
            boolean isLastSeg = (seg == stationEntries.size() - 2);

            double scheduledTargetCum = scheduledCumPos[seg + 1];
            double segDistM = scheduledTargetCum - actualCumPosition;
            if (segDistM <= 0) {
                throw new IllegalArgumentException(
                        "区间 " + fromSt.name + " → " + toSt.name
                        + " 计划距离为 0 或负值（scheduledTargetCum=" + scheduledTargetCum
                        + " actualCumPosition=" + actualCumPosition + "）");
            }

            com.bjtu.railtransit.vehicle.model.LineProfile segLine =
                    new com.bjtu.railtransit.vehicle.model.LineProfile(
                            0.0, segDistM,
                            LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS,
                            java.util.Collections.emptyList());
            com.bjtu.railtransit.vehicle.model.ScenarioConfig segScenario =
                    demoProvider.buildScenario(segLine);

            SimulationResult segResult = run(segScenario);
            java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> segStates = segResult.getStates();

            for (com.bjtu.railtransit.vehicle.dto.TrainState st : segStates) {
                double globalPos = actualCumPosition + st.getPosition();
                double absPos = lineStartAbsM + globalPos;
                com.bjtu.railtransit.vehicle.dto.TrainState adjusted = new com.bjtu.railtransit.vehicle.dto.TrainState(
                        st.getTime() + timeOffset,
                        globalPos,
                        st.getVelocity(),
                        st.getAcceleration(),
                        st.getPhase(),
                        "T1"
                );
                adjusted.setAbsolutePosition(absPos);
                allStates.add(adjusted);
                if (st.getVelocity() > maxVelocityAll) maxVelocityAll = st.getVelocity();
            }

            for (com.bjtu.railtransit.vehicle.dto.SafetyEvent se : segResult.getSafetyEvents()) {
                allSafetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                        se.getReason(),
                        se.getTime() + timeOffset,
                        actualCumPosition + se.getPosition(),
                        se.getVelocity(),
                        se.getAction()
                ));
            }

            com.bjtu.railtransit.vehicle.dto.TrainState lastSeg = segStates.get(segStates.size() - 1);
            double arrivalTime = lastSeg.getTime() + timeOffset;
            double actualStopCum = actualCumPosition + lastSeg.getPosition();
            double stopErr = actualStopCum - scheduledTargetCum;
            boolean inWindow = Math.abs(stopErr) <= STOP_POSITION_TOLERANCE
                    && lastSeg.getVelocity() <= STOP_VELOCITY_TOLERANCE;

            stationStops.add(new com.bjtu.railtransit.vehicle.dto.StationStop(
                    toSt.id, toSt.name,
                    scheduledCumPos[seg],
                    scheduledTargetCum,
                    actualStopCum,
                    stopErr,
                    inWindow,
                    arrivalTime, isLastSeg ? 0.0 : dwell
            ));

            timeOffset += lastSeg.getTime();

            if (isLastSeg) {
                double finalStopErr = actualStopCum - totalTargetPosition;
                com.bjtu.railtransit.vehicle.enums.StopWindowState ws =
                        deriveStopWindowState(finalStopErr, lastSeg.getVelocity());
                boolean finalSuccess = Math.abs(finalStopErr) <= STOP_POSITION_TOLERANCE
                        && lastSeg.getVelocity() <= STOP_VELOCITY_TOLERANCE;
                finalStopResult = new StopResult(
                        totalTargetPosition,
                        actualStopCum,
                        finalStopErr,
                        finalSuccess,
                        finalSuccess ? null : String.format(
                                "停车误差 %.3fm 或末速度 %.3fm/s 超出阈值", finalStopErr, lastSeg.getVelocity()),
                        ws,
                        actualCumPosition + (segResult.getStopResult() != null
                                ? segResult.getStopResult().getBrakeTriggerPosition() : 0.0),
                        actualCumPosition + (segResult.getStopResult() != null
                                ? segResult.getStopResult().getPredictedStopPosition() : 0.0)
                );
            } else {
                double dwellPos = actualStopCum;
                double dwellAbsPos = lineStartAbsM + dwellPos;
                int dwellFrames = (int) Math.round(dwell / dt);
                for (int f = 1; f <= dwellFrames; f++) {
                    com.bjtu.railtransit.vehicle.dto.TrainState dSt =
                            new com.bjtu.railtransit.vehicle.dto.TrainState(
                                    timeOffset + f * dt,
                                    dwellPos, 0.0, 0.0,
                                    SimulationPhase.DWELL, "T1");
                    dSt.setAbsolutePosition(dwellAbsPos);
                    allStates.add(dSt);
                }

                timeOffset += dwell;
                actualCumPosition = actualStopCum;
            }
        }

        com.bjtu.railtransit.vehicle.dto.TrainState lastAll = allStates.get(allStates.size() - 1);
        SimulationSummary summary = new SimulationSummary(
                maxVelocityAll, lastAll.getTime(), lastAll.getPosition(),
                LineProfileJsonLoader.ASSUMED_SPEED_LIMIT_MPS, dt);
        summary.setLineStartPosition(lineStartAbsM);
        summary.setLineTargetPosition(stationEntries.get(stationEntries.size() - 1).km * 1000.0);
        summary.setFromStationName(stationEntries.get(0).name);
        summary.setToStationName(stationEntries.get(stationEntries.size() - 1).name);
        summary.setTotalStations(stationEntries.size());
        summary.setCompletedStops(stationStops.size());

        SimulationResult result = new SimulationResult(allStates, summary, finalStopResult, allSafetyEvents);
        result.setStationStops(stationStops);
        return result;
    }

    public SimulationResult runContinuation(SimulationControlRequest request, ScenarioConfig scenario) {
        if (request.getCurrentState() == null) throw new IllegalArgumentException("currentState 不能为空");
        if (request.getCurrentMode() == null) throw new IllegalArgumentException("currentMode 不能为空");
        if (request.getControlCommand() == null) throw new IllegalArgumentException("controlCommand 不能为空");

        LineProfile line = scenario.getLineProfile();
        TrainModel train = scenario.getTrainModel();
        double dt = scenario.getDt();
        double dtSub = dt / SUB_STEPS_PER_SAMPLE;
        double speedLimit = line.getSpeedLimit();

        double currentCumulativePos = request.getCurrentState().getPosition();
        double totalTarget = request.getTotalTargetPosition();
        double localTarget = totalTarget - currentCumulativePos;

        com.bjtu.railtransit.vehicle.dto.ControlCommand cmd = request.getControlCommand();
        DrivingMode inputMode = request.getCurrentMode();
        String commandStr = cmd.getCommand() != null ? cmd.getCommand().toLowerCase() : "";
        boolean isEB = "emergency_brake".equals(commandStr);
        boolean isAtpEB = "atp_emergency_brake".equals(commandStr);
        boolean isResumeAto = "resume_ato".equals(commandStr);
        boolean isResetEmergency = "reset_emergency".equals(commandStr);

        double levelPercent = Math.min(100.0, Math.max(0.0, cmd.getLevelPercent()));

        // reset_emergency：紧急制动停稳后复位到 MANUAL，不重新积分，提前返回。
        // 不走主积分循环，不进入 effectiveMode 判断与后续 EB/MANUAL/ATO 分支。
        if (isResetEmergency) {
            com.bjtu.railtransit.vehicle.dto.TrainState cs = request.getCurrentState();
            boolean stopped = cs.getVelocity() <= STOP_VELOCITY_TOLERANCE
                    || cs.getPhase() == SimulationPhase.STOPPED;
            if (!stopped) {
                throw new IllegalArgumentException("紧急制动未停稳，不能复位，请等待列车停稳后再操作");
            }
            com.bjtu.railtransit.vehicle.dto.TrainState stoppedState =
                    new com.bjtu.railtransit.vehicle.dto.TrainState(
                            cs.getTime(), cs.getPosition(), 0.0, 0.0,
                            SimulationPhase.STOPPED, "T1");
            stoppedState.setAbsolutePosition(cs.getAbsolutePosition());
            java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> stoppedStates =
                    new java.util.ArrayList<>();
            stoppedStates.add(stoppedState);

            double actualStop = cs.getPosition();
            double stopError = actualStop - totalTarget;
            boolean success = Math.abs(stopError) <= STOP_POSITION_TOLERANCE;
            StopWindowState ws = deriveStopWindowState(stopError, 0.0);
            StopResult stopResult = new StopResult(
                    totalTarget, actualStop, stopError, success,
                    success ? null : String.format("复位时停车误差 %.3fm 超出阈值（±%.1fm）",
                            stopError, STOP_POSITION_TOLERANCE),
                    ws, actualStop, actualStop);

            SimulationSummary summary = new SimulationSummary(
                    0.0, cs.getTime(), cs.getPosition(), speedLimit, dt);
            summary.setCurrentMode(DrivingMode.MANUAL);
            // nextMode 保持 null（已复位，无后续建议模式）

            SimulationResult resetResult = new SimulationResult(
                    stoppedStates, summary, stopResult, java.util.Collections.emptyList());
            resetResult.setStationStops(java.util.Collections.emptyList());
            return resetResult;
        }

        DrivingMode effectiveMode;
        if (isEB || isAtpEB) {
            effectiveMode = DrivingMode.EMERGENCY;
        } else if (isResumeAto) {
            // resume_ato：MANUAL/ATO → ATO；EMERGENCY 不能直接恢复 ATO，需先停稳复位到人工。
            if (inputMode == DrivingMode.EMERGENCY) {
                throw new IllegalArgumentException("紧急模式不能直接恢复 ATO，需停稳复位到人工");
            }
            effectiveMode = DrivingMode.ATO;
        } else if (inputMode == DrivingMode.ATO
                && ("traction".equals(commandStr) || "coast".equals(commandStr) || "brake".equals(commandStr))) {
            effectiveMode = DrivingMode.ATO;
        } else {
            effectiveMode = inputMode;
        }

        double startT = request.getCurrentState().getTime();
        double localPos = 0.0;
        double v = request.getCurrentState().getVelocity();
        double t = startT;

        java.util.List<com.bjtu.railtransit.vehicle.dto.TrainState> states = new java.util.ArrayList<>();
        java.util.List<com.bjtu.railtransit.vehicle.dto.SafetyEvent> safetyEvents = new java.util.ArrayList<>();

        boolean brakingTriggered = false;
        double brakeResponseRemaining = 0.0;
        double brakeTriggerPosition = 0.0;
        double predictedStopPositionAtTrigger = 0.0;

        double manualTractionAccel = 0.0;
        double manualTargetDecel = 0.0;

        if (effectiveMode == DrivingMode.MANUAL) {
            if ("traction".equals(commandStr) && levelPercent > 0.0) {
                manualTractionAccel = (levelPercent / 100.0) * train.getMaxAcceleration();
            } else if ("brake".equals(commandStr)) {
                double reqDecel = cmd.getTargetDecel();
                if (reqDecel <= 0.0 && levelPercent > 0.0) {
                    reqDecel = (levelPercent / 100.0) * train.getNormalBrakeDeceleration();
                }
                if (reqDecel > 0.0) {
                    manualTargetDecel = Math.min(reqDecel, train.getNormalBrakeDeceleration());
                    brakingTriggered = true;
                    brakeTriggerPosition = localPos;
                    brakeResponseRemaining = train.getBrakeResponseTime();
                }
            }
        }

        if (effectiveMode == DrivingMode.EMERGENCY) {
            brakingTriggered = true;
            brakeTriggerPosition = localPos;
            brakeResponseRemaining = train.getBrakeResponseTime();
            String reason = isAtpEB ? "ATP_EMERGENCY_BRAKE"
                    : isEB ? "DRIVER_EMERGENCY_BRAKE" : "EMERGENCY_MODE_ENGAGED";
            safetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                    reason, t, currentCumulativePos, v, "emergency_brake"));
        }

        final double fManualTractionAccel = manualTractionAccel;
        final double fManualDecel = manualTargetDecel;
        final DrivingMode fMode = effectiveMode;
        final boolean fManualTractionActive = manualTractionAccel > 0.0;

        for (int step = 0; step < MAX_STEPS; step++) {
            double dragAtZero = computeNetDrag(localPos, 0.0, train, line);
            boolean canMoveForward = fMode == DrivingMode.MANUAL && fManualTractionActive
                    && fManualTractionAccel > dragAtZero;
            if (brakingTriggered && v <= VELOCITY_EPSILON) {
                states.add(makeGlobal(t, localPos, 0.0, 0.0,
                        SimulationPhase.STOPPED, currentCumulativePos,
                        request.getCurrentState().getAbsolutePosition()));
                break;
            }
            if (!brakingTriggered && v <= VELOCITY_EPSILON && !canMoveForward) {
                states.add(makeGlobal(t, localPos, 0.0, 0.0,
                        SimulationPhase.STOPPED, currentCumulativePos,
                        request.getCurrentState().getAbsolutePosition()));
                break;
            }

            double sampleT = t;
            double sampleLocalPos = localPos;
            double sampleV = v;
            SimulationPhase samplePhase = null;
            double sampleAccel = 0.0;

            for (int sub = 0; sub < SUB_STEPS_PER_SAMPLE; sub++) {
                if ((brakingTriggered || !canMoveForward) && v <= VELOCITY_EPSILON) {
                    if (samplePhase == null) { samplePhase = SimulationPhase.STOPPED; sampleAccel = 0.0; }
                    break;
                }

                SimulationPhase phase;
                double acceleration;
                double drag = computeNetDrag(localPos, v, train, line);

                if (brakingTriggered) {
                    phase = SimulationPhase.BRAKING;
                    if (brakeResponseRemaining > 0) {
                        acceleration = -drag;
                        brakeResponseRemaining -= dtSub;
                    } else {
                        double brakeDecel = (fMode == DrivingMode.EMERGENCY)
                                ? train.getEmergencyBrakeDeceleration()
                                : (fMode == DrivingMode.MANUAL && fManualDecel > 0.0) ? fManualDecel
                                : train.getNormalBrakeDeceleration();
                        acceleration = -brakeDecel - drag;
                    }
                } else if (fMode == DrivingMode.ATO) {
                    double predicted = predictStopPosition(localPos, v, train, line, dtSub);
                    if (predicted >= localTarget) {
                        brakingTriggered = true;
                        brakeTriggerPosition = localPos;
                        predictedStopPositionAtTrigger = predicted;
                        brakeResponseRemaining = train.getBrakeResponseTime();
                        phase = SimulationPhase.BRAKING;
                        acceleration = -drag;
                    } else if (v < speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.TRACTION;
                        acceleration = train.getMaxAcceleration() - drag;
                    } else {
                        phase = SimulationPhase.COAST;
                        acceleration = -drag;
                    }
                } else if (fMode == DrivingMode.MANUAL) {
                    boolean safetyStopTriggered = localPos >= localTarget;
                    if (safetyStopTriggered) {
                        brakingTriggered = true;
                        brakeTriggerPosition = localPos;
                        predictedStopPositionAtTrigger = predictStopPosition(localPos, v, train, line, dtSub);
                        brakeResponseRemaining = 0.0;
                        phase = SimulationPhase.BRAKING;
                        acceleration = -train.getNormalBrakeDeceleration() - drag;
                    } else if (fManualTractionActive && v < speedLimit - VELOCITY_EPSILON) {
                        phase = SimulationPhase.TRACTION;
                        acceleration = fManualTractionAccel - drag;
                    } else {
                        phase = SimulationPhase.COAST;
                        acceleration = -drag;
                    }
                } else {
                    phase = SimulationPhase.COAST;
                    acceleration = -drag;
                }

                double vNext = v + acceleration * dtSub;
                if (vNext < 0.0) vNext = 0.0;
                if (phase == SimulationPhase.TRACTION && vNext >= speedLimit) {
                    vNext = speedLimit;
                }
                double posNext = localPos + 0.5 * (v + vNext) * dtSub;

                if (vNext > speedLimit + VELOCITY_EPSILON && !brakingTriggered) {
                    safetyEvents.add(new com.bjtu.railtransit.vehicle.dto.SafetyEvent(
                            "OVERSPEED",
                            t + (sub + 1) * dtSub,
                            currentCumulativePos + posNext,
                            vNext,
                            "emergency_brake"
                    ));
                    brakingTriggered = true;
                    brakeTriggerPosition = posNext;
                    predictedStopPositionAtTrigger = predictStopPosition(posNext, vNext, train, line, dtSub);
                    brakeResponseRemaining = 0.0;
                    phase = SimulationPhase.BRAKING;
                }

                if (sub == 0) { samplePhase = phase; sampleAccel = acceleration; }
                t += dtSub; localPos = posNext; v = vNext;
            }

            if (samplePhase == null) samplePhase = SimulationPhase.STOPPED;
            states.add(makeGlobal(sampleT, sampleLocalPos, sampleV, sampleAccel,
                    samplePhase, currentCumulativePos,
                    request.getCurrentState().getAbsolutePosition()));
        }

        if (states.isEmpty() || states.get(states.size() - 1).getPhase() != SimulationPhase.STOPPED) {
            throw new IllegalStateException("控制续算未在最大步数内收敛到停车状态");
        }

        com.bjtu.railtransit.vehicle.dto.TrainState lastState = states.get(states.size() - 1);
        double actualCumPos = lastState.getPosition();
        double stopError = actualCumPos - totalTarget;
        boolean success = Math.abs(stopError) <= STOP_POSITION_TOLERANCE
                && lastState.getVelocity() <= STOP_VELOCITY_TOLERANCE;
        StopWindowState ws = deriveStopWindowState(stopError, lastState.getVelocity());
        StopResult stopResult = new StopResult(
                totalTarget, actualCumPos, stopError, success,
                success ? null : String.format("续算停车误差 %.3fm 或末速 %.3fm/s 超出阈值",
                        stopError, lastState.getVelocity()),
                ws, currentCumulativePos + brakeTriggerPosition,
                currentCumulativePos + predictedStopPositionAtTrigger);

        double maxV = states.stream().mapToDouble(com.bjtu.railtransit.vehicle.dto.TrainState::getVelocity).max().orElse(0);
        SimulationSummary summary = new SimulationSummary(
                maxV, lastState.getTime(), lastState.getPosition(),
                speedLimit, dt);
        summary.setCurrentMode(fMode);
        if (fMode == DrivingMode.EMERGENCY && lastState.getPhase() == SimulationPhase.STOPPED) {
            summary.setNextMode(DrivingMode.MANUAL);
        }

        SimulationResult result = new SimulationResult(states, summary, stopResult, safetyEvents);
        result.setStationStops(java.util.Collections.emptyList());
        return result;
    }

    private com.bjtu.railtransit.vehicle.dto.TrainState makeGlobal(
            double t, double localPos, double velocity, double acceleration,
            SimulationPhase phase,
            double cumulativeOffset, Double baseAbsolutePos) {
        double globalPos = cumulativeOffset + localPos;
        com.bjtu.railtransit.vehicle.dto.TrainState st =
                new com.bjtu.railtransit.vehicle.dto.TrainState(
                        t, globalPos, velocity, acceleration, phase, "T1");
        if (baseAbsolutePos != null) {
            st.setAbsolutePosition(baseAbsolutePos + localPos);
        }
        return st;
    }
}
