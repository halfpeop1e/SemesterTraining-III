package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Route;
import com.bjtu.railtransit.signal.model.Signal;
import com.bjtu.railtransit.signal.model.Switch;
import com.bjtu.railtransit.signal.model.SwitchState;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 模拟联锁服务 —— 在不依赖真实 CI 系统的情况下，
 * 提供道岔单操、进路办理/取消、信号机灯色控制、进路绑定（车→进路）。
 *
 * <p>G1 双层分离：builtRoutes = 站场（灯/岔排好）；routeBindings = 车→进路（MA 只读 activeRoutes）。
 */
@Service
public class SignalInterlockingService {

    private final LineProfileLoader lineProfileLoader;
    private final Map<Integer, Route> builtRoutes = new LinkedHashMap<>();
    /** G1: trainId → routeId 绑定。assign 仅允许已 built route；一车一条（覆盖）；一路一车（拒绝）。 */
    private final Map<String, Integer> routeBindings = new LinkedHashMap<>();

    public SignalInterlockingService(LineProfileLoader lineProfileLoader) {
        this.lineProfileLoader = lineProfileLoader;
    }

    // ═══ 道岔单操 ═══

    public Switch operateSwitch(String switchId, SwitchState target) {
        Switch sw = findSwitch(switchId);
        sw.setState(target);
        return sw;
    }

    // ═══ 进路办理 ═══

    public Route buildRoute(int routeId) {
        Route route = findRoute(routeId);
        if (builtRoutes.containsKey(routeId))
            throw new IllegalStateException("进路已建立: " + routeId);

        checkConflictingRoutes(route);

        // 设置入口和终端信号机为 GREEN
        setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.GREEN);
        if (route.getEndSignalId() > 0) {
            setSignalAspect(String.valueOf(route.getEndSignalId()), SignalAspect.GREEN);
        }

        builtRoutes.put(routeId, route);
        return route;
    }

    public Route cancelRoute(int routeId) {
        Route route = builtRoutes.remove(routeId);
        if (route == null)
            throw new NoSuchElementException("进路未建立: " + routeId);

        // G1: cancel 统一清该 route 的所有绑定（服务端一处做，不靠前端记得 unassign）
        routeBindings.values().removeIf(rid -> rid == routeId);

        // 恢复信号机为 RED
        setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
        if (route.getEndSignalId() > 0) {
            setSignalAspect(String.valueOf(route.getEndSignalId()), SignalAspect.RED);
        }
        return route;
    }

    private void checkConflictingRoutes(Route newRoute) {
        for (Route existing : builtRoutes.values()) {
            if (existing.getStartSignalId() == newRoute.getStartSignalId()
                    || existing.getEndSignalId() == newRoute.getEndSignalId()) {
                throw new IllegalStateException("敌对进路冲突: routeId=" + existing.getId());
            }
            // 区段重叠检查
            if (existing.getAxleSectionIds() != null && newRoute.getAxleSectionIds() != null) {
                Set<Integer> existingSections = new HashSet<>(existing.getAxleSectionIds());
                for (Integer sec : newRoute.getAxleSectionIds()) {
                    if (existingSections.contains(sec))
                        throw new IllegalStateException("进路区段重叠: routeId=" + existing.getId());
                }
            }
        }
    }

    // ═══ 信号机控制 ═══

    public Signal setSignalAspect(String signalId, SignalAspect aspect) {
        Signal sig = findSignal(signalId);
        sig.setAspect(aspect);
        return sig;
    }

    // ═══ 进路绑定（G1: 车→进路）═══

    /**
     * G1: 将已建立进路绑定到列车（一车一条覆盖；一路一车拒绝）。
     *
     * @return 绑定的 Route
     * @throws NoSuchElementException route 未建立
     * @throws IllegalStateException 该进路已被其他列车占用
     */
    public Route assignRoute(String trainId, int routeId) {
        if (trainId == null || trainId.isBlank())
            throw new IllegalArgumentException("trainId 不能为空");
        if (!builtRoutes.containsKey(routeId))
            throw new NoSuchElementException("进路未建立，无法绑定: routeId=" + routeId);

        // 一路一车：检查该 route 是否已被别的 train 占
        for (Map.Entry<String, Integer> entry : routeBindings.entrySet()) {
            if (entry.getValue() == routeId && !entry.getKey().equals(trainId)) {
                throw new IllegalStateException(
                        "进路 " + routeId + " 已被列车 " + entry.getKey() + " 占用");
            }
        }
        // 一车一条（覆盖）：先清该 train 的旧绑定
        routeBindings.remove(trainId);
        routeBindings.put(trainId, routeId);
        return builtRoutes.get(routeId);
    }

    /**
     * G1: 解绑列车的进路（不影响 builtRoutes 站场状态）。
     */
    public void unassignRoute(String trainId) {
        routeBindings.remove(trainId);
    }

    /**
     * G1: 返回 trainId → routeId 绑定快照（调试/前端展示）。
     */
    public Map<String, Integer> getRouteBindings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(routeBindings));
    }

    // ═══ 查询 ═══

    public List<Switch> getAllSwitches() {
        LineProfile lp = lineProfileLoader.getLineProfile();
        return lp.getSwitches() != null ? lp.getSwitches() : Collections.emptyList();
    }

    public List<Route> getBuiltRoutes() {
        return new ArrayList<>(builtRoutes.values());
    }

    public Map<String, SignalAspect> getAllSignalAspects() {
        Map<String, SignalAspect> result = new LinkedHashMap<>();
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getSignals() != null) {
            for (Signal s : lp.getSignals()) {
                result.put(String.valueOf(s.getId()), s.getAspect());
            }
        }
        return result;
    }

    // ═══ 内部查找 ═══

    private Switch findSwitch(String id) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getSwitches() != null) {
            for (Switch sw : lp.getSwitches()) {
                if (id.equals(String.valueOf(sw.getId()))) return sw;
            }
        }
        throw new NoSuchElementException("道岔不存在: " + id);
    }

    private Route findRoute(int id) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getRoutes() != null) {
            for (Route r : lp.getRoutes()) {
                if (r.getId() == id) return r;
            }
        }
        throw new NoSuchElementException("进路不存在: " + id);
    }

    private Signal findSignal(String id) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (lp.getSignals() != null) {
            for (Signal s : lp.getSignals()) {
                if (id.equals(String.valueOf(s.getId()))) return s;
            }
        }
        throw new NoSuchElementException("信号机不存在: " + id);
    }
}
