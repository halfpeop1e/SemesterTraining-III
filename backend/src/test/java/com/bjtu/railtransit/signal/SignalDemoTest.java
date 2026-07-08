package com.bjtu.railtransit.signal;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 包装：端到端跟车仿真（天然跟车）。
 * 运行：mvn -pl backend test 或 IDE 直接跑本类。
 */
public class SignalDemoTest {

    @Test
    void endToEndFollow() throws Exception {
        SignalDemo.main(new String[0]);
    }
}
