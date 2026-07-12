package com.bjtu.railtransit.energy;

import com.bjtu.railtransit.domain.model.EnergyRecord;
import com.bjtu.railtransit.domain.model.SimulationLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnergyCalculator 单元测试 — 验证效率默认值来自 Excel.
 */
class EnergyCalculatorTest {

    @Test
    void defaultEfficiencies_matchExcel() throws Exception {
        // 通过反射验证 DEFAULT_TRACTION_EFFICIENCY = 0.882
        java.lang.reflect.Field tracField = EnergyCalculator.class
                .getDeclaredField("DEFAULT_TRACTION_EFFICIENCY");
        tracField.setAccessible(true);
        assertEquals(0.882, tracField.getDouble(null), 0.001,
                "默认牵引效率应为 88.2%");

        // 验证 DEFAULT_REGEN_EFFICIENCY = 0.802
        java.lang.reflect.Field regenField = EnergyCalculator.class
                .getDeclaredField("DEFAULT_REGEN_EFFICIENCY");
        regenField.setAccessible(true);
        assertEquals(0.802, regenField.getDouble(null), 0.001,
                "默认再生效率应为 80.2%");
    }

    @Test
    void calculate_withDefaultEfficiencies_producesReasonableKwh() {
        EnergyCalculator calculator = new EnergyCalculator();

        // 构造简单牵引日志: 200kN 牵引力, 20m/s, 10秒
        List<SimulationLog> logs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SimulationLog log = new SimulationLog();
            log.setTrainId(1);
            log.setTimestamp((long) (i * 1000));
            log.setTractiveBrakeCmd("traction");
            log.setTractionForce(200_000.0); // N
            log.setSpeed(20.0);              // m/s
            log.setBrakeForce(0.0);
            logs.add(log);
        }

        List<EnergyRecord> records = calculator.calculate(logs, -1, -1); // 使用默认效率
        assertFalse(records.isEmpty());
        EnergyRecord record = records.get(0);

        // 理论值: 200kN × 20m/s × 10s / 0.882 = 45.35 MJ = 12.6 kWh
        assertTrue(record.getTotalTractionEnergyKwh() > 10.0,
                "牵引能耗应 > 10 kWh, 实际=" + record.getTotalTractionEnergyKwh());
        assertTrue(record.getTotalTractionEnergyKwh() < 15.0,
                "牵引能耗应 < 15 kWh, 实际=" + record.getTotalTractionEnergyKwh());
    }

    @Test
    void calculate_withBraking_producesRegenEnergy() {
        EnergyCalculator calculator = new EnergyCalculator();

        List<SimulationLog> logs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SimulationLog log = new SimulationLog();
            log.setTrainId(1);
            log.setTimestamp((long) (i * 1000));
            log.setTractiveBrakeCmd("brake");
            log.setTractionForce(0.0);
            log.setSpeed(15.0);
            log.setBrakeForce(150_000.0); // N
            logs.add(log);
        }

        List<EnergyRecord> records = calculator.calculate(logs, -1, -1);
        EnergyRecord record = records.get(0);

        // 理论值: 150kN × 15m/s × 10s × 0.802 = 18.045 MJ = 5.01 kWh
        assertTrue(record.getTotalRegenEnergyKwh() > 3.0,
                "再生能量应 > 3 kWh, 实际=" + record.getTotalRegenEnergyKwh());
        assertTrue(record.getTotalRegenEnergyKwh() < 7.0,
                "再生能量应 < 7 kWh, 实际=" + record.getTotalRegenEnergyKwh());
    }
}
