package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.model.LineProfile;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TASK-2 单元测试（{@code mvn test} 跑此）。
 * 断言逻辑复用 {@link Task2Verification}，保证沙箱自检与 CI 一致。
 */
public class LineProfileLoaderTest {

    @Test
    void utData01_units() {
        Task2Verification.checkUnits();
    }

    @Test
    void utLoc01_mileageIndex() {
        Task2Verification.checkMileageSynthetic();
    }

    @Test
    void verificationRealData() throws Exception {
        LineProfile lp = Task2Verification.loadFromClasspath();
        Task2Verification.checkRealData(lp);
    }

    @Test
    void verificationPermillePassthrough() throws Exception {
        URL url = getClass().getClassLoader().getResource("line-profile.json");
        assertNotNull(url, "classpath 必须包含 line-profile.json");
        String path = Paths.get(url.toURI()).toString();
        Task2Verification.checkPermillePassthrough(path);
    }
}
