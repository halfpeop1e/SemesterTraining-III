package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.SignalAspect;
import com.bjtu.railtransit.signal.model.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 模拟联锁服务 —— 对齐工业 CBI 标准。
 *
 * <p>进路生命周期：REQUESTED → SELECTING → LOCKED → SIGNAL_OPEN → APPROACH_LOCKED
 *    → OCCUPIED → RELEASING → COMPLETED。</p>
 * <p>G1 双层分离：builtRoutes = 站场进路状态；routeBindings = 车→进路绑定。</p>
 */
@Service
public class SignalInterlockingService {

    private static final double SWITCH_ACTION_SECONDS = 1.5; // 道岔转换耗时（秒）
    private static final double APPROACH_DELAY_TRAIN_SECONDS = 30.0; // 列车接近延时解锁
    private static final double APPROACH_DELAY_SHUNT_SECONDS = 180.0; // 调车接近延时解锁

    private final LineProfileLoader lineProfileLoader;
    private final Map<Integer, Route> builtRoutes = new LinkedHashMap<>();
    private final Map<String, Integer> routeBindings = new LinkedHashMap<>();

    public SignalInterlockingService(LineProfileLoader lineProfileLoader) {
        this.lineProfileLoader = lineProfileLoader;
    }

    // ═══ 进路办理（两步法：select → build）═══

    /**
     * 选排进路：检查可用性、征用道岔和区段、启动道岔转换。
     *
     * @param routeId 进路 ID
     * @param source  操作来源 "AUTO" 或 "MANUAL"
     * @param nowSeconds 当前仿真时间（秒）
     * @return 进路对象（状态 REQUESTED → SELECTING）
     */
    public Route selectRoute(int routeId, String source, double nowSeconds) {
        Route route = findRoute(routeId);
        if (builtRoutes.containsKey(routeId))
            throw new IllegalStateException("进路已建立: " + routeId);

        // 检查区段空闲
        checkSectionsFree(route);
        // 检查侵限区段空闲
        checkInfringementSectionsFree(route);
        // 检查敌对进路
        checkConflictingRoutes(route);
        // 检查道岔未被封锁
        checkSwitchesNotLocked(route);
        // 检查防护道岔
        checkGuardSwitches(route);

        route.setLockState(RouteLockState.SELECTING);
        route.setSource(source);

        // 征用道岔
        requisitionSwitches(route);
        // 启动道岔转换（使用仿真时间）
        startSwitchActions(route, SWITCH_ACTION_SECONDS, nowSeconds);

        builtRoutes.put(routeId, route);
        return route;
    }

    /**
     * 每周期调用：推进道岔转换 → 转换完成后锁闭进路 → 开放信号。
     *
     * @param nowSeconds 当前仿真时刻
     */
    public void processRouteTransitions(double nowSeconds) {
        for (Route route : builtRoutes.values()) {
            switch (route.getLockState()) {
                case SELECTING -> processSelecting(route, nowSeconds);
                case LOCKED -> processLocked(route);
                case SIGNAL_OPEN -> processSignalOpen(route);
                case RELEASING -> processReleasing(route, nowSeconds);
            }
        }
    }

    private void processSelecting(Route route, double nowSeconds) {
        // 检查所有道岔是否完成转换
        boolean allDone = switchesInPosition(route);
        if (!allDone) return; // 道岔还在转换中

        // 转换完成 → 锁闭进路
        lockRouteSwitches(route);
        route.setLockState(RouteLockState.LOCKED);
    }

    private void processLocked(Route route) {
        // 再次检查开放条件
        if (!canOpenSignal(route)) {
            route.setLockState(RouteLockState.REJECTED);
            releaseSwitches(route);
            return;
        }
        // 开放信号
        setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.GREEN);
        route.setLockState(RouteLockState.SIGNAL_OPEN);
    }

    /** 信号保持：每周期检查开放条件。*/
    private void processSignalOpen(Route route) {
        if (!canOpenSignal(route)) {
            // 异常关闭
            setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
            route.setLockState(RouteLockState.CANCELLED);
            releaseSwitches(route);
        }
    }

    private void processReleasing(Route route, double nowSeconds) {
        route.setLockState(RouteLockState.COMPLETED);
        releaseSwitches(route);
    }

    // ═══ 列车占用与进路释放 ═══

    /**
     * 通知联锁：指定区段被占用。
     * 若某条已开放进路的内方第一区段被占用 → 关闭信号 → 进路进入占用状态。
     */
    public void notifySectionOccupied(int axleSectionId) {
        for (Route route : builtRoutes.values()) {
            if (route.getLockState() != RouteLockState.SIGNAL_OPEN
                    && route.getLockState() != RouteLockState.APPROACH_LOCKED) continue;

            List<Integer> ids = route.getAxleSectionIds();
            if (ids == null || ids.isEmpty()) continue;

            // 列车驶入第一区段 → 正常关闭信号
            if (ids.get(0) == axleSectionId) {
                setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
                route.setLockState(RouteLockState.OCCUPIED);
            }
        }
    }

    /**
     * 通知联锁：指定区段已被出清。
     * 若某条占用中进路的所有区段均已出清 → 触发正常通过解锁。
     */
    public void notifySectionCleared(int axleSectionId) {
        for (Route route : builtRoutes.values()) {
            if (route.getLockState() != RouteLockState.OCCUPIED) continue;
            List<Integer> ids = route.getAxleSectionIds();
            if (ids == null) continue;

            // 简单段解锁：所有区段都出清后释放
            boolean allClear = true;
            LineProfile lp = lineProfileLoader.getLineProfile();
            for (int sid : ids) {
                for (AxleCounterSection a : lp.getAxleSections()) {
                    if (a.getId() == sid && a.isOccupied()) {
                        allClear = false;
                        break;
                    }
                }
                if (!allClear) break;
            }
            if (allClear) {
                route.setLockState(RouteLockState.RELEASING);
            }
        }
    }

    // ═══ 接近锁闭 ═══

    /**
     * 检查并触发接近锁闭：若列车进入接近区段，进路由 SIGNAL_OPEN → APPROACH_LOCKED。
     */
    public void checkApproachLocking() {
        LineProfile lp = lineProfileLoader.getLineProfile();
        for (Route route : builtRoutes.values()) {
            if (route.getLockState() != RouteLockState.SIGNAL_OPEN) continue;
            if (route.getApproachSectionIds() == null || route.getApproachSectionIds().isEmpty()) continue;

            for (int appId : route.getApproachSectionIds()) {
                for (AxleCounterSection a : lp.getAxleSections()) {
                    if (a.getId() == appId && a.isOccupied()) {
                        route.setLockState(RouteLockState.APPROACH_LOCKED);
                        return; // 只处理第一个触发
                    }
                }
            }
        }
    }

    // ═══ 进路取消 ═══

    /**
     * 取消进路。
     * - 无接近锁闭 → 立即取消
     * - 有接近锁闭 → 延时取消（列车 30s / 调车 180s）
     */
    public Route cancelRoute(int routeId) {
        Route route = builtRoutes.get(routeId);
        if (route == null)
            throw new NoSuchElementException("进路未建立: " + routeId);

        if (route.getLockState() == RouteLockState.APPROACH_LOCKED
                || route.getLockState() == RouteLockState.OCCUPIED) {
            // 接近锁闭或占用中不可即时取消
            throw new IllegalStateException("进路处于接近锁闭或占用状态，不可即时取消。需延时解锁。");
        }

        doCancel(route);
        return route;
    }

    /** 人工延时解锁：接近锁闭状态下延时后释放。*/
    public void delayedCancel(int routeId, double nowSeconds) {
        Route route = builtRoutes.get(routeId);
        if (route == null) return;
        if (route.getLockState() != RouteLockState.APPROACH_LOCKED) return;

        double delay = route.getRouteType() == 1
                ? APPROACH_DELAY_SHUNT_SECONDS : APPROACH_DELAY_TRAIN_SECONDS;
        // 教学中简化：直接取消（延时计时由上层 SimulationService 管理）
        doCancel(route);
    }

    /** 故障解锁：区段占用丢失时强制解锁。*/
    public void faultUnlock(int routeId) {
        Route route = builtRoutes.get(routeId);
        if (route == null) return;

        setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
        route.setLockState(RouteLockState.COMPLETED);
        releaseSwitches(route);
        routeBindings.values().removeIf(rid -> rid == routeId);
    }

    private void doCancel(Route route) {
        setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
        route.setLockState(RouteLockState.CANCELLED);
        releaseSwitches(route);
        routeBindings.values().removeIf(rid -> rid == route.getId());
    }

    // ═══ 信号保持（外部调用，每周期）═══

    /** 每周期信号保持检查：所有 SIGNAL_OPEN 进路持续检查开放条件。*/
    public void signalHoldingCheck() {
        for (Route route : builtRoutes.values()) {
            if (route.getLockState() != RouteLockState.SIGNAL_OPEN
                    && route.getLockState() != RouteLockState.APPROACH_LOCKED) continue;

            if (!canOpenSignal(route)) {
                setSignalAspect(String.valueOf(route.getStartSignalId()), SignalAspect.RED);
                route.setLockState(RouteLockState.FAILED);
            }
        }
    }

    // ═══ 条件检查方法 ═══

    private void checkSectionsFree(Route route) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (route.getAxleSectionIds() == null) return;
        for (int sid : route.getAxleSectionIds()) {
            for (AxleCounterSection a : lp.getAxleSections()) {
                if (a.getId() == sid && a.isOccupied()) {
                    throw new IllegalStateException("进路区段被占用: axleSectionId=" + sid);
                }
            }
        }
    }

    private void checkInfringementSectionsFree(Route route) {
        LineProfile lp = lineProfileLoader.getLineProfile();
        if (route.getInfringementSectionIds() == null) return;
        for (int sid : route.getInfringementSectionIds()) {
            for (AxleCounterSection a : lp.getAxleSections()) {
                if (a.getId() == sid && a.isOccupied()) {
                    throw new IllegalStateException("侵限区段被占用: sectionId=" + sid);
                }
            }
        }
    }

    private void checkConflictingRoutes(Route newRoute) {
        for (Route existing : builtRoutes.values()) {
            if (existing.getLockState() == RouteLockState.COMPLETED
                    || existing.getLockState() == RouteLockState.CANCELLED
                    || existing.getLockState() == RouteLockState.REJECTED
                    || existing.getLockState() == RouteLockState.FAILED) continue;

            // 显式敌对进路
            if (newRoute.getConflictRouteIds() != null && newRoute.getConflictRouteIds().contains(existing.getId())) {
                throw new IllegalStateException("敌对进路冲突: routeId=" + existing.getId());
            }
            // 始端/终端相同
            if (existing.getStartSignalId() == newRoute.getStartSignalId()
                    || existing.getEndSignalId() == newRoute.getEndSignalId()) {
                throw new IllegalStateException("信号机冲突: routeId=" + existing.getId());
            }
            // 区段重叠
            if (existing.getAxleSectionIds() != null && newRoute.getAxleSectionIds() != null) {
                Set<Integer> existingSections = new HashSet<>(existing.getAxleSectionIds());
                for (Integer sec : newRoute.getAxleSectionIds()) {
                    if (existingSections.contains(sec))
                        throw new IllegalStateException("进路区段重叠: routeId=" + existing.getId());
                }
            }
            // 道岔相反位置冲突
            if (existing.getSwitchIds() != null && newRoute.getSwitchIds() != null
                    && existing.getSwitchPositions() != null && newRoute.getSwitchPositions() != null) {
                checkSwitchPositionConflict(newRoute, existing);
            }
        }
    }

    private void checkSwitchPositionConflict(Route a, Route b) {
        int size = Math.min(a.getSwitchIds().size(), a.getSwitchPositions().size());
        for (int i = 0; i < size; i++) {
            int swId = a.getSwitchIds().get(i);
            int pos = a.getSwitchPositions().get(i);
            int bSize = Math.min(b.getSwitchIds().size(), b.getSwitchPositions().size());
            for (int j = 0; j < bSize; j++) {
                if (b.getSwitchIds().get(j) != swId) continue;
                if (b.getSwitchPositions().get(j) != pos) {
                    throw new IllegalStateException(
                            String.format("道岔相反位置冲突: switchId=%d routeA=%d pos=%d routeB=%d pos=%d",
                                    swId, a.getId(), pos, b.getId(), b.getSwitchPositions().get(j)));
                }
            }
        }
    }

    private void checkSwitchesNotLocked(Route route) {
        if (route.getSwitchIds() == null) return;
        for (int swId : route.getSwitchIds()) {
            Switch sw = findSwitch(String.valueOf(swId));
            if (sw.isFunctionLocked()) {
                throw new IllegalStateException("道岔被功能锁闭: switchId=" + swId);
            }
            if (sw.isGuideLocked()) {
                throw new IllegalStateException("道岔被引导锁闭: switchId=" + swId);
            }
        }
        if (route.getGuardSwitchIds() != null) {
            for (int swId : route.getGuardSwitchIds()) {
                Switch sw = findSwitch(String.valueOf(swId));
                if (sw.isFunctionLocked()) {
                    throw new IllegalStateException("防护道岔被功能锁闭: switchId=" + swId);
                }
            }
        }
    }

    private void checkGuardSwitches(Route route) {
        if (route.getGuardSwitchIds() == null) return;
        for (int swId : route.getGuardSwitchIds()) {
            Switch sw = findSwitch(String.valueOf(swId));
            if (sw.getState() == SwitchState.FAIL) {
                throw new IllegalStateException("防护道岔失表: switchId=" + swId);
            }
        }
    }

    private boolean canOpenSignal(Route route) {
        // 区段空闲
        if (route.getAxleSectionIds() != null) {
            LineProfile lp = lineProfileLoader.getLineProfile();
            for (int sid : route.getAxleSectionIds()) {
                for (AxleCounterSection a : lp.getAxleSections()) {
                    if (a.getId() == sid && a.isOccupied()) return false;
                }
            }
        }
        // 道岔位置正确且锁闭
        if (route.getSwitchIds() != null && route.getSwitchPositions() != null) {
            for (int i = 0; i < Math.min(route.getSwitchIds().size(), route.getSwitchPositions().size()); i++) {
                Switch sw = findSwitch(String.valueOf(route.getSwitchIds().get(i)));
                SwitchState expected = route.getSwitchPositions().get(i) == 0
                        ? SwitchState.NORMAL : SwitchState.REVERSE;
                if (sw.getState() != expected) return false;
                if (!sw.isRouteLocked()) return false;
            }
        }
        return true;
    }

    // ═══ 道岔操作 ═══

    private void requisitionSwitches(Route route) {
        if (route.getSwitchIds() != null) {
            for (int swId : route.getSwitchIds()) {
                Switch sw = findSwitch(String.valueOf(swId));
                sw.setRequisitioned(true);
                sw.setRequisitionedByRouteId(route.getId());
            }
        }
        if (route.getGuardSwitchIds() != null) {
            for (int swId : route.getGuardSwitchIds()) {
                Switch sw = findSwitch(String.valueOf(swId));
                sw.setRequisitioned(true);
                sw.setRequisitionedByRouteId(route.getId());
            }
        }
    }

    private void startSwitchActions(Route route, double durationSeconds, double nowSeconds) {
        if (route.getSwitchIds() == null || route.getSwitchPositions() == null) return;
        for (int i = 0; i < Math.min(route.getSwitchIds().size(), route.getSwitchPositions().size()); i++) {
            Switch sw = findSwitch(String.valueOf(route.getSwitchIds().get(i)));
            SwitchState target = route.getSwitchPositions().get(i) == 0
                    ? SwitchState.NORMAL : SwitchState.REVERSE;
            if (sw.getState() != target) {
                sw.setActionPending(true);
                sw.setActionStartSeconds(nowSeconds);  // 使用仿真时间，非 System.currentTimeMillis()
                sw.setActionDurationSeconds(durationSeconds);
            }
        }
    }

    private boolean switchesInPosition(Route route) {
        if (route.getSwitchIds() == null || route.getSwitchPositions() == null) return true;
        for (int i = 0; i < Math.min(route.getSwitchIds().size(), route.getSwitchPositions().size()); i++) {
            Switch sw = findSwitch(String.valueOf(route.getSwitchIds().get(i)));
            if (sw.isActionPending()) return false;
        }
        return true;
    }

    private void lockRouteSwitches(Route route) {
        if (route.getSwitchIds() != null) {
            for (int swId : route.getSwitchIds()) {
                Switch sw = findSwitch(String.valueOf(swId));
                sw.setRouteLocked(true);
                sw.setRequisitioned(false);
                sw.setRequisitionedByRouteId(0);
            }
        }
        if (route.getGuardSwitchIds() != null) {
            for (int swId : route.getGuardSwitchIds()) {
                Switch sw = findSwitch(String.valueOf(swId));
                sw.setRouteLocked(true);
                sw.setRequisitioned(false);
                sw.setRequisitionedByRouteId(0);
            }
        }
    }

    private void releaseSwitches(Route route) {
        if (route.getSwitchIds() != null) {
            for (int swId : route.getSwitchIds()) {
                Switch sw = findSwitch(String.valueOf(swId));
                sw.clearAllLocks();
            }
        }
        if (route.getGuardSwitchIds() != null) {
            for (int swId : route.getGuardSwitchIds()) {
                Switch sw = findSwitch(String.valueOf(swId));
                sw.clearAllLocks();
            }
        }
    }

    // ═══ 道岔单操 ═══

    public Switch operateSwitch(String switchId, SwitchState target) {
        Switch sw = findSwitch(switchId);
        if (sw.isAnyLocked())
            throw new IllegalStateException("道岔已被锁闭，不可操作: " + switchId);
        sw.setState(target);
        return sw;
    }

    // ═══ 信号机控制 ═══

    public Signal setSignalAspect(String signalId, SignalAspect aspect) {
        Signal sig = findSignal(signalId);
        sig.setAspect(aspect);
        return sig;
    }

    // ═══ 进路绑定（G1: 车→进路，一车一条覆盖；一路一车拒绝）═══

    public Route assignRoute(String trainId, int routeId) {
        if (trainId == null || trainId.isBlank())
            throw new IllegalArgumentException("trainId 不能为空");
        Route route = builtRoutes.get(routeId);
        if (route == null)
            throw new NoSuchElementException("进路未建立，无法绑定: routeId=" + routeId);
        if (route.getLockState() != RouteLockState.SIGNAL_OPEN
                && route.getLockState() != RouteLockState.APPROACH_LOCKED) {
            throw new IllegalStateException("进路未开放，无法绑定: lockState=" + route.getLockState());
        }
        for (Map.Entry<String, Integer> entry : routeBindings.entrySet()) {
            if (entry.getValue() == routeId && !entry.getKey().equals(trainId)) {
                throw new IllegalStateException("进路 " + routeId + " 已被列车 " + entry.getKey() + " 占用");
            }
        }
        routeBindings.remove(trainId);
        routeBindings.put(trainId, routeId);
        return route;
    }

    public void unassignRoute(String trainId) {
        routeBindings.remove(trainId);
    }

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

    public Map<Integer, Route> getBuiltRoutesMap() {
        return Collections.unmodifiableMap(builtRoutes);
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
