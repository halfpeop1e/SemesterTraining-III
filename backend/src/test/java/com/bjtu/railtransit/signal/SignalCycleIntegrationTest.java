package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.domain.model.TrainState;
import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.service.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignalCycleIntegrationTest {
    @Test
    void fullTeacherLineMaIsAppliedBackToRuntimeTrain() throws Exception {
        MaConfig config = MaConfig.exampleConfig();
        MovementAuthorityRegistry registry = new MovementAuthorityRegistry(null);
        LineProfileLoader loader = new LineProfileLoader();
        SignalInterlockingService interlocking = new SignalInterlockingService(loader);
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(config), registry, loader, interlocking, new SignalEventLog(), true);

        TrainState following = train("T2", 1_000, 36);
        TrainState leading = train("T1", 2_000, 18);
        Map<String, MovingAuthority> values = cycle.runCycle(List.of(following, leading), 10);

        assertEquals(2, values.size());
        // G2 后计轴占用生效：前车所在区段 occupied → 计轴约束可能比前车约束更严格
        // basis 可能是 PRECEDING_TRAIN 或 AXLE_OCCUPIED，取决于区段起点与前车尾部的相对位置
        AuthorityBasis basis = values.get("T2").getBasis();
        assertTrue(basis == AuthorityBasis.PRECEDING_TRAIN || basis == AuthorityBasis.AXLE_OCCUPIED,
                "basis 应为 PRECEDING_TRAIN 或 AXLE_OCCUPIED，实际=" + basis);
        assertEquals(values.get("T2").getEndOfAuthorityM(), following.getMovementAuthority(), 0.001);
        assertTrue(following.getMovementAuthority() < leading.getPositionMeters());
        assertEquals("SIMULATED_INTERLOCKING", registry.getSource());
        assertEquals(1, registry.getGeneration());
    }

    @Test
    void laboratoryModeDoesNotAssumeUnknownSignalsAreClear() throws Exception {
        MaConfig config = MaConfig.exampleConfig();
        MovementAuthorityRegistry registry = new MovementAuthorityRegistry(null);
        LineProfileLoader loader = new LineProfileLoader();
        SignalInterlockingService interlocking = new SignalInterlockingService(loader);
        SignalCycleService cycle = new SignalCycleService(
                new MovingAuthorityService(config), registry, loader, interlocking, new SignalEventLog(), false);
        TrainState train = train("T1", 0, 0);

        MovingAuthority ma = cycle.runCycle(List.of(train), 1).get("T1");

        assertEquals(AuthorityBasis.SIGNAL, ma.getBasis());
        assertTrue(ma.getEndOfAuthorityM() < cycle.getLineProfile().getTotalLengthM());
        assertEquals("LAB_INTERLOCKING", registry.getSource());
    }

    private TrainState train(String id, double position, double speedKmh) {
        TrainState train = new TrainState();
        train.setTrainId(id);
        train.setDirection("UP");
        train.setStatus("CRUISING");
        train.setPositionMeters(position);
        train.setSpeed(speedKmh);
        train.setTrainLengthMeters(140);
        train.setMaxSpeedLimit(80);
        return train;
    }
}
