package com.bjtu.railtransit.signal;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 包装：复用 TrainStateAdapterVerification 的独立自检方法。
 * 运行：mvn test 或 IDE 直接跑本类。
 */
public class TrainStateAdapterTest {

    @Test
    void segmentRuntimeConversion() { TrainStateAdapterVerification.checkSegmentRuntimeConversion(); }

    @Test
    void absoluteBuilder() { TrainStateAdapterVerification.checkAbsoluteBuilder(); }

    @Test
    void badSegFailSafe() { TrainStateAdapterVerification.checkBadSegFailSafe(); }

    @Test
    void flowsIntoCompute() { TrainStateAdapterVerification.checkFlowsIntoCompute(); }
}
