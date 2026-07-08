package com.bjtu.railtransit.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 列车运行状态 —— 完整状态机模型 (支持双向运行 + 折返)。
 *
 * 状态转移图:
 *   DEPOT_WAITING → DEPARTING → ACCELERATING → CRUISING → BRAKING → DWELLING
 *                                                                        ↓
 *                                                    (非终端) DEPARTING ←
 *                                                                        ↓
 *                                                    (终端站) TURNING_BACK → (反向)DEPARTING → ...循环
 *                                                                        ↓
 *                                                    (仿真结束) FINISHED
 *
 * 方向概念:
 *   UP   (上行) = 郭公庄 → 国家图书馆, position 递增, stationIndex 递增
 *   DOWN (下行) = 国家图书馆 → 郭公庄, position 递减, stationIndex 递减
 */
public class TrainState {

    private String trainId;            // 内部标识 (T1, T2, ...)
    private String trainName;          // 显示名
    private String trainNumber;        // 正式车次号 (如 90101 = 9号线01次上行)
    private String direction;          // UP | DOWN
    private String routePattern;       // 交路模式: FULL | SHORT_N | SHORT_S
    private String operationLevel;     // 运行等级: NORMAL | ENERGY_SAVE | EXPRESS | SLOW
    private boolean skipNextStation;   // 甩站标志 (下一站不停车通过)
    private int turnbackCount;         // 已完成折返次数
    private double positionMeters;
    private double speed;              // km/h
    private String status;             // DEPOT_WAITING | DEPARTING | ACCELERATING | CRUISING | BRAKING | DWELLING | TURNING_BACK | FINISHED
    private int currentStationIndex;   // 当前所在站 (DWELLING时为刚到达的站，运行中为已通过的最后站)
    private int nextStationIndex;      // 下一站索引
    private double nextStationKm;      // 下一站里程
    private List<TrainCar> cars;
    private int carCount = 6;
    private double carLength = 19.0;

    // ── 调度/晚点字段 ──
    private double delaySeconds;        // 累积晚点秒数
    private double plannedDwellSeconds; // 计划站停秒数
    private double actualDwellSeconds;  // 实际已停秒数（DWELLING时递增）

    // ── 时刻表字段（预计算）──
    private double plannedDepartureFromDepot;  // 计划从车辆段发车时刻
    private double plannedArrivalAtStation;    // 计划到达当前/下一站时刻
    private double actualArrivalAtStation;     // 实际到达时刻
    private double actualDepartureFromStation; // 实际出发时刻

    // ── 运动控制字段 ──
    private double maxSpeedLimit;       // 当前限速 km/h (受SLOW指令影响)
    private double targetSpeed;         // 目标速度 km/h
    private double acceleration;        // 当前加速度 km/h/s
    private double sectionDistance;     // 当前区间距离 m (站间)
    private double sectionProgress;     // 当前区间已行进 m

    // ── ATP/安全字段 ──
    private boolean emergencyBraking;   // 紧急制动标志
    private double movementAuthority;   // 移动授权终点(前车尾部-安全余量)

    public TrainState() {
        this.maxSpeedLimit = 60;
        this.targetSpeed = 60;
    }

    // ── Getters / Setters ──

    public String getTrainId() { return trainId; }
    public void setTrainId(String v) { this.trainId = v; }

    public String getTrainName() { return trainName; }
    public void setTrainName(String v) { this.trainName = v; }

    public String getTrainNumber() { return trainNumber; }
    public void setTrainNumber(String v) { this.trainNumber = v; }

    public String getDirection() { return direction; }
    public void setDirection(String v) { this.direction = v; }

    public String getRoutePattern() { return routePattern; }
    public void setRoutePattern(String v) { this.routePattern = v; }

    public String getOperationLevel() { return operationLevel; }
    public void setOperationLevel(String v) { this.operationLevel = v; }

    public boolean isSkipNextStation() { return skipNextStation; }
    public void setSkipNextStation(boolean v) { this.skipNextStation = v; }

    public int getTurnbackCount() { return turnbackCount; }
    public void setTurnbackCount(int v) { this.turnbackCount = v; }

    /** UP方向: position递增为正, DOWN方向: position递减(速度仍为正,delta取负) */
    public boolean isUpDirection() { return !"DOWN".equals(direction); }

    public double getPositionMeters() { return positionMeters; }
    public void setPositionMeters(double v) { this.positionMeters = v; }

    public double getSpeed() { return speed; }
    public void setSpeed(double v) { this.speed = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public int getCurrentStationIndex() { return currentStationIndex; }
    public void setCurrentStationIndex(int v) { this.currentStationIndex = v; }

    public int getNextStationIndex() { return nextStationIndex; }
    public void setNextStationIndex(int v) { this.nextStationIndex = v; }

    public double getNextStationKm() { return nextStationKm; }
    public void setNextStationKm(double v) { this.nextStationKm = v; }

    public List<TrainCar> getCars() { return cars; }
    public void setCars(List<TrainCar> v) { this.cars = v; }

    public int getCarCount() { return carCount; }
    public void setCarCount(int v) { this.carCount = v; }

    public double getCarLength() { return carLength; }
    public void setCarLength(double v) { this.carLength = v; }

    public double getDelaySeconds() { return delaySeconds; }
    public void setDelaySeconds(double v) { this.delaySeconds = v; }

    public double getPlannedDwellSeconds() { return plannedDwellSeconds; }
    public void setPlannedDwellSeconds(double v) { this.plannedDwellSeconds = v; }

    public double getActualDwellSeconds() { return actualDwellSeconds; }
    public void setActualDwellSeconds(double v) { this.actualDwellSeconds = v; }

    public double getPlannedDepartureFromDepot() { return plannedDepartureFromDepot; }
    public void setPlannedDepartureFromDepot(double v) { this.plannedDepartureFromDepot = v; }

    public double getPlannedArrivalAtStation() { return plannedArrivalAtStation; }
    public void setPlannedArrivalAtStation(double v) { this.plannedArrivalAtStation = v; }

    public double getActualArrivalAtStation() { return actualArrivalAtStation; }
    public void setActualArrivalAtStation(double v) { this.actualArrivalAtStation = v; }

    public double getActualDepartureFromStation() { return actualDepartureFromStation; }
    public void setActualDepartureFromStation(double v) { this.actualDepartureFromStation = v; }

    public double getMaxSpeedLimit() { return maxSpeedLimit; }
    public void setMaxSpeedLimit(double v) { this.maxSpeedLimit = v; }

    public double getTargetSpeed() { return targetSpeed; }
    public void setTargetSpeed(double v) { this.targetSpeed = v; }

    public double getAcceleration() { return acceleration; }
    public void setAcceleration(double v) { this.acceleration = v; }

    public double getSectionDistance() { return sectionDistance; }
    public void setSectionDistance(double v) { this.sectionDistance = v; }

    public double getSectionProgress() { return sectionProgress; }
    public void setSectionProgress(double v) { this.sectionProgress = v; }

    public boolean isEmergencyBraking() { return emergencyBraking; }
    public void setEmergencyBraking(boolean v) { this.emergencyBraking = v; }

    public double getMovementAuthority() { return movementAuthority; }
    public void setMovementAuthority(double v) { this.movementAuthority = v; }

    // ── 遗留兼容字段: departureTime (前端可能用到) ──
    public String getDepartureTime() {
        return String.valueOf(plannedDepartureFromDepot);
    }
    public void setDepartureTime(String v) {
        try { this.plannedDepartureFromDepot = Double.parseDouble(v); }
        catch (NumberFormatException e) { this.plannedDepartureFromDepot = 0; }
    }
}
