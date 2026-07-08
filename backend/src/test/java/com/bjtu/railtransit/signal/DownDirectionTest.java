package com.bjtu.railtransit.signal;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 包装：复用 DownDirectionVerification 的独立自检方法。
 * 运行：mvn test 或 IDE 直接跑本类。
 */
public class DownDirectionTest {

    @Test
    void downTurnback() { DownDirectionVerification.checkDownTurnback(); }

    @Test
    void downSwitch() { DownDirectionVerification.checkDownSwitch(); }

    @Test
    void downSignal() { DownDirectionVerification.checkDownSignal(); }

    @Test
    void downAxle() { DownDirectionVerification.checkDownAxle(); }

    @Test
    void downRouteOverlap() { DownDirectionVerification.checkDownRouteOverlap(); }

    @Test
    void downSpeedLimit() { DownDirectionVerification.checkDownSpeedLimit(); }

    @Test
    void downLineLimits() { DownDirectionVerification.checkDownLineLimits(); }

    @Test
    void downPreceding() { DownDirectionVerification.checkDownPreceding(); }

    @Test
    void upUnaffected() { DownDirectionVerification.checkUpUnaffected(); }
}
