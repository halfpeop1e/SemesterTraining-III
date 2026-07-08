package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.common.ApiResponse;
import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.MaConfig;
import com.bjtu.railtransit.signal.service.MovingAuthorityService;
import com.bjtu.railtransit.signal.web.MaRequest;
import com.bjtu.railtransit.signal.web.SignalController;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

/**
 * TASK-5 自检：直接调用 SignalController（不启动 Spring 容器，等价于 REST 入口逻辑）。
 *  - 数值算例（§2.5）经 REST 入口 → ApiResponse{success:true, message:"", data.T2.endOfAuthorityM=750}
 *  - Jackson 序列化后的 JSON 形态符合 verification.md §3 契约（success/data/endOfAuthorityM/PRECEDING_TRAIN）
 *  - fail-safe 经入口 → data.T2.event=DEGRADED（success 仍为 true）
 *  - 非法请求（lineProfile 为 null）→ success=false
 * 运行：java com.bjtu.railtransit.signal.Task5Verification
 */
public class Task5Verification {

    static int passed = 0;
    static ObjectMapper OM = new ObjectMapper();

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
        passed++;
        System.out.println("  [PASS] " + msg);
    }
    static void close(double a, double b, double eps, String msg) {
        boolean ok = (Double.isInfinite(a) || Double.isInfinite(b))
                ? Double.compare(a, b) == 0 : Math.abs(a - b) <= eps;
        check(ok, msg + " (got=" + a + ", want=" + b + ")");
    }

    static LineProfile flatLine(double total) { LineProfile lp = new LineProfile(); lp.setTotalLengthM(total); return lp; }
    static TrainState t(String id, double pos, double spd, double len, Direction d) {
        TrainState s = new TrainState(); s.setTrainId(id); s.setPositionM(pos);
        s.setSpeedKmh(spd); s.setLengthM(len); s.setDirection(d); s.setTimestamp(42); s.setAccelerationMps2(0);
        return s;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("===== TASK-5 自检开始 =====");
        checkMaEndpoint();
        checkFailSafeViaApi();
        checkInvalidRequest();
        System.out.println("===== TASK-5 自检通过：断言数 = " + passed + " =====");
    }

    static void checkMaEndpoint() throws Exception {
        System.out.println("REST 入口 + 数值算例（§2.5）：");
        SignalController ctrl = new SignalController(new MovingAuthorityService(MaConfig.exampleConfig()), new LineProfileLoader());
        MaRequest req = new MaRequest();
        req.setLineProfile(flatLine(2000));
        List<TrainState> trains = Arrays.asList(
                t("T1", 1000, 0, 140, Direction.UP),
                t("T2", 0, 64.8, 140, Direction.UP));
        req.setTrains(trains);

        ApiResponse<?> raw = ctrl.ma(req);
        check(raw.isSuccess(), "success = true");
        check("".equals(raw.getMessage()), "message = \"\"");
        @SuppressWarnings("unchecked")
        ApiResponse<java.util.Map<String, MovingAuthority>> resp =
                (ApiResponse<java.util.Map<String, MovingAuthority>>) raw;
        MovingAuthority m = resp.getData().get("T2");
        close(m.getEndOfAuthorityM(), 750, 1e-6, "data.T2.endOfAuthorityM = 750");
        check(m.getBasis() == AuthorityBasis.PRECEDING_TRAIN, "data.T2.basis = PRECEDING_TRAIN");

        // JSON 形态校验（verification.md §3 契约）
        String json = OM.writeValueAsString(resp);
        check(json.contains("\"success\":true"), "JSON 含 \"success\":true");
        check(json.contains("\"endOfAuthorityM\":750.0") || json.contains("\"endOfAuthorityM\":750"),
                "JSON 含 data.T2.endOfAuthorityM=750");
        check(json.contains("PRECEDING_TRAIN"), "JSON 含 basis=PRECEDING_TRAIN");
        System.out.println("  [INFO] 序列化 JSON 片段: " + json.substring(0, Math.min(json.length(), 180)) + " ...");
    }

    static void checkFailSafeViaApi() {
        System.out.println("fail-safe 经 REST 入口：");
        SignalController ctrl = new SignalController(new MovingAuthorityService(MaConfig.exampleConfig()), new LineProfileLoader());
        MaRequest req = new MaRequest();
        req.setLineProfile(flatLine(2000));
        // 前车状态未知
        TrainState bad = t("T1", Double.NaN, 0, 140, Direction.UP);
        TrainState t2 = t("T2", 0, 64.8, 140, Direction.UP);
        req.setTrains(Arrays.asList(bad, t2));
        @SuppressWarnings("unchecked")
        ApiResponse<java.util.Map<String, MovingAuthority>> resp =
                (ApiResponse<java.util.Map<String, MovingAuthority>>) ctrl.ma(req);
        check(resp.isSuccess(), "success = true（降级不报 500）");
        check(resp.getData().get("T2").getEvent() == SignalEvent.DEGRADED, "data.T2.event = DEGRADED");
    }

    static void checkInvalidRequest() {
        System.out.println("非法请求兜底：");
        SignalController ctrl = new SignalController(new MovingAuthorityService(MaConfig.exampleConfig()), new LineProfileLoader());
        MaRequest req = new MaRequest();
        req.setTrains(Arrays.asList(t("T2", 0, 0, 140, Direction.UP))); // lineProfile=null
        ApiResponse<?> resp = ctrl.ma(req);
        check(!resp.isSuccess(), "lineProfile 为 null → success = false");
        check(resp.getData() == null, "失败响应 data = null");
    }
}
