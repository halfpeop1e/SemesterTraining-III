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
 * 提供道岔单操、进路办理/取消、信号机灯色控制。
 */
@Service
public class SignalInterlockingService {

    private final LineProfileLoader lineProfileLoader;
    private final Map<Integer, Route> builtRoutes = new LinkedHashMap<>();

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
