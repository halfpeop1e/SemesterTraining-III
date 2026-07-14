package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Route;
import com.bjtu.railtransit.signal.service.LineProfileLoader;
import com.bjtu.railtransit.signal.service.SignalInterlockingService;

import java.util.*;

/**
 * G1 进路绑定验证（main 方法，沙箱可独立运行）：
 *  - assign 仅允许已 built route
 *  - 一车一条（覆盖）
 *  - 一路一车（拒绝）
 *  - cancel 清绑定
 *  - unassign 不影响 builtRoutes
 *  - bindings 快照
 */
public class G1RouteBindingVerification {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {
        LineProfileLoader loader = new LineProfileLoader();
        SignalInterlockingService svc = new SignalInterlockingService(loader);
        LineProfile lp = loader.getLineProfile();

        // 找两条不冲突的进路用于测试（区段不重叠）
        List<Route> routes = lp.getRoutes();
        if (routes == null || routes.size() < 3) {
            System.out.println("SKIP: 线路进路不足 3 条，无法测试");
            return;
        }
        // 用 id=1 和 id=3（区段 [1] vs [2]，不重叠）
        Route r1 = findById(routes, 1);
        Route r2 = findById(routes, 3);
        if (r1 == null || r2 == null) {
            System.out.println("SKIP: 找不到 routeId=1 或 3");
            return;
        }
        int id1 = r1.getId();
        int id2 = r2.getId();

        System.out.println("G1 进路绑定验证（routeId=" + id1 + "/" + id2 + "）");

        // 1. assign 未 built → 拒绝
        System.out.println("\nassign 未 built route:");
        try {
            svc.assignRoute("T1", id1);
            fail("应拒绝：进路未建立却允许 assign");
        } catch (NoSuchElementException e) {
            ok("assign 未 built → NoSuchElementException");
        }

        // 2. build r1 → assign T1 成功
        System.out.println("\nbuild + assign:");
        svc.selectRoute(id1, "TEST", 0);
        Route bound = svc.assignRoute("T1", id1);
        check(bound.getId() == id1, "assign 返回正确 Route");
        check(svc.getRouteBindings().get("T1") == id1, "bindings[T1] = " + id1);
        ok("build + assign 成功");

        // 3. 一车一条（覆盖）：T1 从 r1 换到 r2
        System.out.println("\n一车一条（覆盖）:");
        svc.selectRoute(id2, "TEST", 0);
        svc.assignRoute("T1", id2);
        check(svc.getRouteBindings().get("T1") == id2, "T1 覆盖到 r2");
        check(!svc.getRouteBindings().containsValue(id1), "r1 不再被 T1 绑");
        ok("一车一条覆盖成功");

        // 4. 一路一车（拒绝）：T2 试图绑 r2（已被 T1 占）
        System.out.println("\n一路一车（拒绝）:");
        try {
            svc.assignRoute("T2", id2);
            fail("应拒绝：进路已被 T1 占用却允许 T2 绑");
        } catch (IllegalStateException e) {
            ok("一路一车拒绝: " + e.getMessage());
        }

        // 5. unassign 不影响 builtRoutes
        System.out.println("\nunassign:");
        svc.unassignRoute("T1");
        check(!svc.getRouteBindings().containsKey("T1"), "T1 解绑后 bindings 无 T1");
        check(svc.getBuiltRoutes().size() == 2, "builtRoutes 仍有 2 条");
        ok("unassign 不影响 builtRoutes");

        // 6. cancel 清绑定
        System.out.println("\ncancel 清绑定:");
        svc.assignRoute("T3", id1);
        check(svc.getRouteBindings().get("T3") == id1, "T3 绑 r1");
        svc.cancelRoute(id1);
        check(!svc.getRouteBindings().containsKey("T3"), "cancel r1 后 T3 绑定被清");
        check(svc.getBuiltRoutes().size() == 1, "builtRoutes 只剩 1 条（r2）");
        ok("cancel 清绑定成功");

        // 7. cancel 未绑定的进路不报错
        System.out.println("\ncancel 未绑定进路:");
        svc.cancelRoute(id2);
        check(svc.getRouteBindings().isEmpty(), "全部清空");
        ok("cancel 未绑定的进路正常");

        System.out.println("\nG1 done: pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }

    static void check(boolean c, String msg) {
        if (c) { pass++; System.out.println("  OK " + msg); }
        else { fail++; System.out.println("  FAIL " + msg); }
    }

    static void ok(String msg) { check(true, msg); }
    static void fail(String msg) { check(false, msg); }

    static Route findById(List<Route> routes, int id) {
        for (Route r : routes) {
            if (r.getId() == id) return r;
        }
        return null;
    }
}
