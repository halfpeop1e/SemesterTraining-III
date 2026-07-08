package com.bjtu.railtransit.domain.model;

/**
 * 单列车单时间步的仿真状态日志
 * 数据来源：总控数据库节点 → 信号系统 周期(100ms)发送的列车信息
 */
public class SimulationLog {
    private int trainId;            // 列车ID
    private long timestamp;          // 仿真时间戳 (ms)
    private double speed;            // 速度 (m/s)
    private double position;         // 累计走行距离 (m)
    private String direction;        // "up" 上行 / "down" 下行
    private double loadWeight;       // 载重 (kg)
    private double tractionForce;    // 牵引力 (N)，来自动力学模型
    private double brakeForce;       // 制动力 (N)，来自动力学模型
    private String tractiveBrakeCmd; // "traction" / "brake" / "coast"
    private double tractiveBrakePercent; // 牵引/制动百分比 0-100
    private boolean emergencyBrake;  // 紧急制动
    private int availableTractionCount;  // 可用牵引数量
    private int availableBrakeCount;     // 可用制动数量
    private double faultSpeedLimit;  // 故障限速 (m/s)
    private String drivingMode;      // RM/CM/AM/AR/EUM/CAM/FAM
    private int currentSegId;        // 当前所在Seg编号

    public SimulationLog() {}

    // Getters and Setters
    public int getTrainId() { return trainId; }
    public void setTrainId(int trainId) { this.trainId = trainId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public double getPosition() { return position; }
    public void setPosition(double position) { this.position = position; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public double getLoadWeight() { return loadWeight; }
    public void setLoadWeight(double loadWeight) { this.loadWeight = loadWeight; }

    public double getTractionForce() { return tractionForce; }
    public void setTractionForce(double tractionForce) { this.tractionForce = tractionForce; }

    public double getBrakeForce() { return brakeForce; }
    public void setBrakeForce(double brakeForce) { this.brakeForce = brakeForce; }

    public String getTractiveBrakeCmd() { return tractiveBrakeCmd; }
    public void setTractiveBrakeCmd(String tractiveBrakeCmd) { this.tractiveBrakeCmd = tractiveBrakeCmd; }

    public double getTractiveBrakePercent() { return tractiveBrakePercent; }
    public void setTractiveBrakePercent(double tractiveBrakePercent) { this.tractiveBrakePercent = tractiveBrakePercent; }

    public boolean isEmergencyBrake() { return emergencyBrake; }
    public void setEmergencyBrake(boolean emergencyBrake) { this.emergencyBrake = emergencyBrake; }

    public int getAvailableTractionCount() { return availableTractionCount; }
    public void setAvailableTractionCount(int availableTractionCount) { this.availableTractionCount = availableTractionCount; }

    public int getAvailableBrakeCount() { return availableBrakeCount; }
    public void setAvailableBrakeCount(int availableBrakeCount) { this.availableBrakeCount = availableBrakeCount; }

    public double getFaultSpeedLimit() { return faultSpeedLimit; }
    public void setFaultSpeedLimit(double faultSpeedLimit) { this.faultSpeedLimit = faultSpeedLimit; }

    public String getDrivingMode() { return drivingMode; }
    public void setDrivingMode(String drivingMode) { this.drivingMode = drivingMode; }

    public int getCurrentSegId() { return currentSegId; }
    public void setCurrentSegId(int currentSegId) { this.currentSegId = currentSegId; }
}
