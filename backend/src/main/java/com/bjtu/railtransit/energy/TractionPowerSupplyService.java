package com.bjtu.railtransit.energy;

import com.bjtu.railtransit.domain.model.LineProfile;
import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.dispatch.LineDataService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * DC1500V 牵引供电仿真模型。
 *
 * 模拟沿线变电站分布、电压降计算、多车取流对电压的影响、
 * 再生制动能量回馈以及低电压对牵引力的约束。
 *
 * 物理模型:
 *   V_train = V_sub - Σ(I_i × R × d_i)
 *   其中 d_i 为列车到最近变电站的距离(km)，R 为接触轨电阻(Ω/km)
 */
@Service
public class TractionPowerSupplyService {

    private static final double NOMINAL_VOLTAGE = 1500.0;      // 标称电压 V
    private static final double MIN_OPERATING_VOLTAGE = 1000.0; // 最低工作电压 V
    private static final double RAIL_RESISTANCE_OHM_PER_KM = 0.015; // 接触轨电阻 Ω/km
    private static final double SUBSTATION_CAPACITY_MW = 3.0;  // 单座变电站容量 MW
    private static final double REGEN_EFFICIENCY = 0.802;       // 再生制动吸收效率

    private final LineDataService lineDataService;
    private final List<Substation> substations = new ArrayList<>();

    /** 供电状态缓存 */
    private final Map<String, PowerState> powerStates = new LinkedHashMap<>();

    /** 变电站模型 */
    public record Substation(int id, double km, String name) {}
    public record PowerState(double voltage, double current, double powerKw,
                              double substationLoadMw, boolean voltageOK) {}

    public TractionPowerSupplyService(LineDataService lineDataService) {
        this.lineDataService = lineDataService;
        initializeSubstations();
    }

    /**
     * 基于车站位置初始化变电站（约每2-3km一座）
     */
    private void initializeSubstations() {
        LineProfile lp = lineDataService.getLineProfile();
        if (lp == null || lp.getStations() == null) return;

        var stations = lp.getStations();
        // 在典型位置布置变电站：郭公庄、丰台科技园、七里庄、六里桥、北京西站、白堆子、国家图书馆
        int[] subStationIds = {1, 2, 6, 7, 9, 11, 13};
        for (int id : subStationIds) {
            var st = stations.stream().filter(s -> s.getId() == id).findFirst().orElse(null);
            if (st != null) {
                substations.add(new Substation(id, st.getKm(), st.getName()));
            }
        }
        // 确保至少有几个变电站
        if (substations.isEmpty() && !stations.isEmpty()) {
            double spacing = stations.get(stations.size() - 1).getKm() / 6.0;
            for (int i = 0; i < 7; i++) {
                substations.add(new Substation(i + 1, spacing * (i + 0.5), "SUB" + (i + 1)));
            }
        }
    }

    /**
     * 每仿真步调用：计算全线供电状态，更新每列车电压和牵引力约束。
     *
     * @return 全线供电状态 Map<trainId, PowerState>
     */
    public Map<String, PowerState> stepPowerSupply(java.util.Collection<TrainState> trains,
                                                     double totalTractionKw,
                                                     double totalRegenKw) {
        powerStates.clear();

        // 计算每列车到最近变电站的距离
        for (TrainState t : trains) {
            if ("FINISHED".equals(t.getStatus()) || "DEPOT_WAITING".equals(t.getStatus()))
                continue;

            double posKm = t.getPositionMeters() / 1000.0;

            // 找最近变电站
            Substation nearest = findNearestSubstation(posKm);
            double distKm = Math.abs(posKm - nearest.km);

            // 取流估算：牵引功率 / 电压 = 电流
            double powerKw = 0;
            if (t.getAcceleration() > 0.05) {
                // 牵引工况
                TractionPhysics physics = new TractionPhysics(lineDataService);
                powerKw = physics.tractionPowerKw(t);
            } else if (t.getAcceleration() < -0.05 && t.getSpeed() > 5) {
                // 制动工况（再生回馈，负功率）
                powerKw = -estimateRegenPower(t);
            }
            // 惰行/停站: 功率≈0

            double current = powerKw * 1000.0 / NOMINAL_VOLTAGE; // A

            // 简化的电压降模型：V = V0 - I * R * d
            double voltageDrop = Math.abs(current) * RAIL_RESISTANCE_OHM_PER_KM * distKm;
            double voltage = NOMINAL_VOLTAGE - voltageDrop;

            // 多车效应：同一变电站区段的列车共享容量
            double subLoadMw = estimateSubstationLoad(nearest, trains);

            // 如果变电站过载，电压进一步下降
            if (subLoadMw > SUBSTATION_CAPACITY_MW) {
                double overloadRatio = SUBSTATION_CAPACITY_MW / Math.max(0.1, subLoadMw);
                voltage *= overloadRatio;
            }

            // 电压钳位
            voltage = Math.max(MIN_OPERATING_VOLTAGE, Math.min(NOMINAL_VOLTAGE * 1.1, voltage));

            boolean voltageOK = voltage >= MIN_OPERATING_VOLTAGE * 0.95;

            PowerState ps = new PowerState(voltage, current, powerKw, subLoadMw, voltageOK);
            powerStates.put(t.getTrainId(), ps);

            // ── 低电压限制牵引力 ──
            if (!voltageOK && t.getAcceleration() > 0 && t.getTractionState() != null) {
                double voltageRatio = voltage / NOMINAL_VOLTAGE;
                double maxForceN = t.getTractionState().getMaxTractiveForceN() * voltageRatio;
                if (maxForceN > 0) {
                    t.getTractionState().setMaxTractiveForceN(maxForceN);
                }
            }
        }

        return Collections.unmodifiableMap(powerStates);
    }

    public Map<String, PowerState> getPowerStates() {
        return Collections.unmodifiableMap(powerStates);
    }

    private Substation findNearestSubstation(double posKm) {
        return substations.stream()
                .min(Comparator.comparingDouble(s -> Math.abs(s.km - posKm)))
                .orElse(substations.get(0));
    }

    private double estimateSubstationLoad(Substation sub, java.util.Collection<TrainState> trains) {
        double totalMw = 0;
        for (TrainState t : trains) {
            double posKm = t.getPositionMeters() / 1000.0;
            double distKm = Math.abs(posKm - sub.km);
            if (distKm > 3.0) continue; // 超过3km不由此站供电
            if (t.getAcceleration() > 0.05) {
                TractionPhysics physics = new TractionPhysics(lineDataService);
                totalMw += physics.tractionPowerKw(t) / 1000.0;
            }
        }
        return totalMw;
    }

    private double estimateRegenPower(TrainState t) {
        double massKg = t.getCarCount() * 35000.0;
        double decelMs2 = Math.abs(t.getAcceleration() / 3.6);
        double forceN = massKg * decelMs2;
        double speedMs = t.getSpeed() / 3.6;
        return forceN * speedMs / 1000.0 * REGEN_EFFICIENCY;
    }

    public List<Substation> getSubstations() { return substations; }
    public PowerState getPowerState(String trainId) { return powerStates.get(trainId); }
}
