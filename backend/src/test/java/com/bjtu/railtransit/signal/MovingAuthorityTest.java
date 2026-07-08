package com.bjtu.railtransit.signal;

import org.junit.jupiter.api.Test;

/**
 * JUnit 5 包装：复用 Task4Verification 的独立自检方法。
 * 运行：mvn -pl backend test 或 IDE 直接跑本类。
 */
public class MovingAuthorityTest {

    @Test
    void utMa01_emptyLine() { Task4Verification.checkMa01(); }

    @Test
    void utMa02_numericExample() { Task4Verification.checkMa02(); }

    @Test
    void utMa03_precedingMoves() { Task4Verification.checkMa03(); }

    @Test
    void utSafe01_degraded() { Task4Verification.checkSafe01(); }
}
