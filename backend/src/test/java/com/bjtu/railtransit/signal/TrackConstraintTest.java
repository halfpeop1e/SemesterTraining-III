package com.bjtu.railtransit.signal;

import org.junit.jupiter.api.Test;

/**
 * TASK-3 单元测试（{@code mvn test} 跑此）。断言逻辑复用 {@link Task3Verification}。
 */
public class TrackConstraintTest {

    @Test
    void requiredGapFlat() { Task3Verification.checkRequiredGapFlat(); }

    @Test
    void downhillLoosens() { Task3Verification.checkDownhillLoosens(); }

    @Test
    void turnbackLineEnd() { Task3Verification.checkTurnbackLineEnd(); }

    @Test
    void switchGating() { Task3Verification.checkSwitchGating(); }

    @Test
    void signalBoundary() { Task3Verification.checkSignalBoundary(); }

    @Test
    void axleOccupancy() { Task3Verification.checkAxleOccupancy(); }

    @Test
    void routeOverlap() { Task3Verification.checkRouteOverlap(); }

    @Test
    void speedLimit() { Task3Verification.checkSpeedLimit(); }
}
