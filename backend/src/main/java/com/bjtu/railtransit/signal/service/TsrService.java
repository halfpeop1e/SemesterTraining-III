package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.TemporarySpeedRestriction;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 临时限速(TSR)管理服务。
 * TSR 写入 LineProfile.tsrs（与 TrackConstraintService 同缓存），
 * compute 调 maxSpeedFor 时自动消费。
 */
@Service
public class TsrService {

    private final LineProfile lineProfile;
    private final AtomicInteger idSeq = new AtomicInteger(1);

    public TsrService(LineProfileLoader loader) {
        try {
            this.lineProfile = loader.getLineProfile();
        } catch (Exception e) {
            throw new RuntimeException("TsrService: 无法加载 LineProfile", e);
        }
    }

    /** 创建 TSR 并加入线路缓存 */
    public TemporarySpeedRestriction createTsr(double startM, double endM, double speedLimitKmh, boolean active) {
        if (endM <= startM)
            throw new IllegalArgumentException("TSR 终点必须大于起点");
        if (speedLimitKmh <= 0)
            throw new IllegalArgumentException("TSR 限速必须大于 0");
        String id = "TSR-" + idSeq.getAndIncrement();
        TemporarySpeedRestriction tsr = new TemporarySpeedRestriction(id, startM, endM, speedLimitKmh, active);
        List<TemporarySpeedRestriction> tsrs = lineProfile.getTsrs();
        if (tsrs == null) {
            tsrs = new ArrayList<>();
            lineProfile.setTsrs(tsrs);
        }
        tsrs.add(tsr);
        return tsr;
    }

    /** 取消 TSR（从缓存移除） */
    public TemporarySpeedRestriction cancelTsr(String id) {
        List<TemporarySpeedRestriction> tsrs = lineProfile.getTsrs();
        if (tsrs == null) return null;
        for (int i = 0; i < tsrs.size(); i++) {
            TemporarySpeedRestriction t = tsrs.get(i);
            if (id.equals(t.getId())) {
                tsrs.remove(i);
                return t;
            }
        }
        return null;
    }

    /** 查询所有 TSR */
    public List<TemporarySpeedRestriction> getAllTsrs() {
        List<TemporarySpeedRestriction> tsrs = lineProfile.getTsrs();
        return tsrs != null ? new ArrayList<>(tsrs) : new ArrayList<>();
    }
}
