package com.bjtu.railtransit.energy;

import com.bjtu.railtransit.domain.model.PeakPowerResult;
import org.springframework.stereotype.Service;

/**
 * 供电风险评估器
 * 将峰值功率与供电阈值比较，输出风险等级
 */
@Service
public class PowerRiskAssessor {

    /**
     * 评估供电风险
     * @param peakResult 峰值检测结果
     * @param thresholdKw 供电阈值 (kW)
     * @return 风险等级
     */
    public String assess(PeakPowerResult peakResult, double thresholdKw) {
        if (thresholdKw <= 0) {
            peakResult.setRiskLevel("safe");
            return "safe";
        }

        double peak = peakResult.getMaxPeakKw();
        double ratio = peak / thresholdKw;

        if (ratio < 0.7) {
            peakResult.setRiskLevel("safe");
            return "safe";
        } else if (ratio < 0.9) {
            peakResult.setRiskLevel("warning");
            return "warning";
        } else {
            peakResult.setRiskLevel("danger");
            return "danger";
        }
    }
}
