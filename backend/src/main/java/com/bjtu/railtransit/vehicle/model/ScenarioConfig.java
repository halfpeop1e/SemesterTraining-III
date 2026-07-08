package com.bjtu.railtransit.vehicle.model;

/**
 * 一次仿真所需的静态配置组合：线路 + 车型 + 时间步长。
 *
 * <p>阶段 1A 请求体暂不生效，实际使用的都是
 * {@link com.bjtu.railtransit.vehicle.service.DemoScenarioProvider} 提供的内置演示配置。</p>
 */
public class ScenarioConfig {

    private final LineProfile lineProfile;
    private final TrainModel trainModel;

    /** 仿真步长，单位 s。 */
    private final double dt;

    public ScenarioConfig(LineProfile lineProfile, TrainModel trainModel, double dt) {
        this.lineProfile = lineProfile;
        this.trainModel = trainModel;
        this.dt = dt;
    }

    public LineProfile getLineProfile() {
        return lineProfile;
    }

    public TrainModel getTrainModel() {
        return trainModel;
    }

    public double getDt() {
        return dt;
    }
}
