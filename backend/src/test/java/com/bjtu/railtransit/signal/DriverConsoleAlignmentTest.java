package com.bjtu.railtransit.signal;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 包装：复用 DriverConsoleAlignmentVerification 的独立自检方法。
 * 运行：mvn test 或 IDE 直接跑本类。
 */
public class DriverConsoleAlignmentTest {

    @Test
    void directionCodec() { DriverConsoleAlignmentVerification.checkDirectionCodec(); }

    @Test
    void signalAspectDecode() { DriverConsoleAlignmentVerification.checkSignalAspectDecode(); }

    @Test
    void signalAspectToDriverConsole() { DriverConsoleAlignmentVerification.checkSignalAspectToDriverConsole(); }

    @Test
    void isProceed() { DriverConsoleAlignmentVerification.checkIsProceed(); }

    @Test
    void signalProceedUp() { DriverConsoleAlignmentVerification.checkSignalProceedUp(); }

    @Test
    void nearestStopSignal() { DriverConsoleAlignmentVerification.checkNearestStopSignal(); }

    @Test
    void computeGreenThrough() { DriverConsoleAlignmentVerification.checkComputeGreenThrough(); }

    @Test
    void invalidDirectionFailSafe() { DriverConsoleAlignmentVerification.checkInvalidDirectionFailSafe(); }

    @Test
    void upRegression() { DriverConsoleAlignmentVerification.checkUpRegression(); }
}
