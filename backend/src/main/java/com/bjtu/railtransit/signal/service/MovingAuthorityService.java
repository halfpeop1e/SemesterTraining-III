package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.domain.AuthorityBasis;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.bjtu.railtransit.signal.domain.SignalEvent;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.model.LineProfile;
import com.bjtu.railtransit.signal.model.Route;
import com.bjtu.railtransit.signal.model.Signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 移动授权核心：为每列车编排移动授权（MA）。
 *
 * 设计（tech-design.md §2.1 / task-plan TASK-4）：
 *  - 纯函数 `compute(line, trains[, routes[, nowSec]])`：找前车 → 收集六维 EoA 截断点 →
 *    取「沿行车方向最近的前方边界」→ 限速查询 → 记录 basis/event/capSignalId/routeId。
 *  - 方向感知：UP=里程递增（边界取 min 且 > 当前位），DOWN=里程递减（边界取 max 且 < 当前位）。
 *  - fail-safe 默认原则（§3）：前车状态未知 / 本车 MA 过期 → EoA=当前位置、maxSpeed 极低、event=DEGRADED/MA_EXPIRED。
 *  - 无静态可变状态；单次 compute 复杂度 O(n·k)（n 车、k 线路实体），每车独立。
 */
@Service
public class MovingAuthorityService {

    private final TrackConstraintService constraints;
    private final MaConfig cfg;

    public MovingAuthorityService(MaConfig cfg) {
        this.cfg = cfg;
        this.constraints = new TrackConstraintService(cfg);
    }

    /** 单个约束候选（截断点里程 + 溯源信息）。 */
    private static class Cand {
        final double eoa;
        final AuthorityBasis basis;
        final SignalEvent event;
        final Integer capSignalId;
        final Integer routeId;
        Cand(double eoa, AuthorityBasis basis, SignalEvent event, Integer capSignalId, Integer routeId) {
            this.eoa = eoa; this.basis = basis; this.event = event;
            this.capSignalId = capSignalId; this.routeId = routeId;
        }
    }

    // ================= 对外入口 =================

    public Map<String, MovingAuthority> compute(LineProfile line, List<TrainState> trains) {
        return compute(line, trains, Collections.emptyMap(), Double.NaN);
    }

    public Map<String, MovingAuthority> compute(LineProfile line, List<TrainState> trains, Map<String, Route> routes) {
        return compute(line, trains, routes, Double.NaN);
    }

    public Map<String, MovingAuthority> compute(LineProfile line, List<TrainState> trains,
                                                Map<String, Route> routes, double nowSec) {
        Map<String, MovingAuthority> out = new LinkedHashMap<>();
        if (trains == null) return out;
        for (TrainState t : trains) {
            out.put(t.getTrainId(), computeOne(line, t, trains, routes, nowSec));
        }
        return out;
    }

    // ================= 单车编排 =================

    private MovingAuthority computeOne(LineProfile line, TrainState self, List<TrainState> all,
                                       Map<String, Route> routes, double nowSec) {
        Direction dir = self.getDirection();
        double pos = self.getPositionM();

        // fail-safe（0）：方向未知/非法（司机台非法编码 → INVALID）→ 收紧至当前位置
        if (dir == null || dir == Direction.INVALID) {
            return degraded(self, AuthorityBasis.LINE_LIMIT, SignalEvent.DEGRADED, null, null);
        }

        // fail-safe（0b）：本车状态非法（位置/速度 NaN、车长≤0）→ 收紧
        // （含 TrainStateAdapter 非法 segId → positionM=NaN 的场景）
        if (!validState(self)) {
            return degraded(self, AuthorityBasis.LINE_LIMIT, SignalEvent.DEGRADED, null, null);
        }

        // fail-safe（1）：本车 MA 过期 → 收紧至当前位置
        if (!Double.isNaN(nowSec)
                && (Double.isNaN(self.getTimestamp()) || self.getTimestamp() < nowSec - cfg.maValiditySec)) {
            return degraded(self, AuthorityBasis.LINE_LIMIT, SignalEvent.MA_EXPIRED, null, null);
        }

        // fail-safe（2）：邻车状态未知 → 视为前方占用不明，保守收紧（任何不确定都不放大授权）
        for (TrainState o : all) {
            if (o == self || o.getTrainId().equals(self.getTrainId())) continue;
            if (!validState(o)) {
                return degraded(self, AuthorityBasis.PRECEDING_TRAIN, SignalEvent.DEGRADED, null, null);
            }
        }

        List<Cand> cands = new ArrayList<>();

        // (0) 前车占用
        TrainState prec = findPreceding(self, all);
        if (prec != null) {
            if (!validState(prec)) {
                // fail-safe（2）：前车状态未知 → 降级
                return degraded(self, AuthorityBasis.PRECEDING_TRAIN, SignalEvent.DEGRADED, null, null);
            }
            double tail = tailOf(prec, dir);
            double gap = constraints.requiredGap(self, prec, line);
            double eoaPre = (dir == Direction.UP) ? (tail - gap) : (tail + gap);
            cands.add(new Cand(eoaPre, AuthorityBasis.PRECEDING_TRAIN, SignalEvent.PRECEDING_OCCUPATION, null, null));
        }

        // (1) 折返 / 线路终点
        double eTb = constraints.eoaFromTurnback(line, self);
        cands.add(new Cand(eTb, AuthorityBasis.TURNBACK_END, SignalEvent.NONE, null, null));

        // (2) 道岔 gating（fail-safe 已含在 eoaFromSwitch 内）
        double eSw = constraints.eoaFromSwitch(line, self);
        if (Double.isFinite(eSw)) {
            cands.add(new Cand(eSw, AuthorityBasis.SWITCH, SignalEvent.SWITCH_ABNORMAL, null, null));
        }

        // (3) 前方防护信号机
        double eSig = constraints.eoaFromSignals(line, self);
        if (Double.isFinite(eSig)) {
            Integer sigId = nearestSignalIdAhead(line, self, dir);
            cands.add(new Cand(eSig, AuthorityBasis.SIGNAL, SignalEvent.SIGNAL_BOUNDARY, sigId, null));
        }

        // (4) 计轴占用
        double eAx = constraints.eoaFromAxleOccupancy(line, self);
        if (Double.isFinite(eAx)) {
            cands.add(new Cand(eAx, AuthorityBasis.AXLE_OCCUPIED, SignalEvent.AXLE_OCCUPIED, null, null));
        }

        // (5) 已建立进路 + 保护区段
        Route route = (routes != null) ? routes.get(self.getTrainId()) : null;
        if (route != null) {
            double eRt = constraints.eoaFromRoute(line, self, route);
            if (Double.isFinite(eRt)) {
                AuthorityBasis b = (route.getOverlapIds() != null && !route.getOverlapIds().isEmpty())
                        ? AuthorityBasis.OVERLAP_END : AuthorityBasis.ROUTE_END;
                cands.add(new Cand(eRt, b, SignalEvent.ROUTE_BLOCKED, null, route.getId()));
            }
        }

        // 取「沿行车方向最近的前方边界」
        Cand best = null;
        for (Cand c : cands) {
            if (!isAhead(c.eoa, pos, dir)) continue;
            if (best == null || (dir == Direction.UP ? c.eoa < best.eoa : c.eoa > best.eoa)) {
                best = c;
            }
        }
        if (best == null) {
            // 理论上折返/线路终点必达，这里兜底
            best = new Cand(eTb, AuthorityBasis.TURNBACK_END, SignalEvent.NONE, null, null);
        }

        double eoa = (dir == Direction.UP) ? Math.max(pos, best.eoa) : Math.min(pos, best.eoa);
        double maxSpeed = constraints.speedLimitAt(line, eoa, dir, pos);

        MovingAuthority ma = new MovingAuthority();
        ma.setTrainId(self.getTrainId());
        ma.setEndOfAuthorityM(eoa);
        ma.setMaxSpeedKmh(maxSpeed);
        ma.setBasis(best.basis);
        ma.setEvent(best.event);
        ma.setCapSignalId(best.capSignalId);
        ma.setRouteId(best.routeId);
        ma.setTimestamp(self.getTimestamp());
        return ma;
    }

    // ================= fail-safe 构造 =================

    private MovingAuthority degraded(TrainState self, AuthorityBasis basis, SignalEvent event,
                                     Integer capSignalId, Integer routeId) {
        MovingAuthority ma = new MovingAuthority();
        ma.setTrainId(self.getTrainId());
        ma.setEndOfAuthorityM(self.getPositionM());   // EoA = 当前位置
        ma.setMaxSpeedKmh(cfg.degradedSpeedKmh);       // 极低（默认 0 = 须停车）
        ma.setBasis(basis);
        ma.setEvent(event);
        ma.setCapSignalId(capSignalId);
        ma.setRouteId(routeId);
        ma.setTimestamp(self.getTimestamp());
        return ma;
    }

    // ================= 方向/几何辅助 =================

    /** 前车车尾里程（沿行车方向在车头之后）。 */
    private double tailOf(TrainState prec, Direction dir) {
        return (dir == Direction.UP) ? (prec.getPositionM() - prec.getLengthM())
                                     : (prec.getPositionM() + prec.getLengthM());
    }

    /** boundary 是否位于 self 当前位「前方」（沿行车方向）。 */
    private boolean isAhead(double boundary, double pos, Direction dir) {
        return (dir == Direction.UP) ? (boundary > pos - 1e-6) : (boundary < pos + 1e-6);
    }

    /** 找前车：沿行车方向最近的一辆（UP=位更大的最小者；DOWN=位更小的最大者）。 */
    private TrainState findPreceding(TrainState self, List<TrainState> all) {
        TrainState best = null;
        for (TrainState o : all) {
            if (o == self || o.getTrainId().equals(self.getTrainId())) continue;
            if (self.getDirection() == Direction.UP) {
                if (o.getPositionM() > self.getPositionM()) {
                    if (best == null || o.getPositionM() < best.getPositionM()) best = o;
                }
            } else {
                if (o.getPositionM() < self.getPositionM()) {
                    if (best == null || o.getPositionM() > best.getPositionM()) best = o;
                }
            }
        }
        return best;
    }

    /** 前车状态是否有效（位置/速度非 NaN、长度 > 0）。 */
    private boolean validState(TrainState t) {
        return t != null && !Double.isNaN(t.getPositionM()) && t.getLengthM() > 0
                && !Double.isNaN(t.getSpeedKmh());
    }

    /** 前方最近信号机 id（用于 capSignalId）。 */
    private Integer nearestSignalIdAhead(LineProfile line, TrainState self, Direction dir) {
        if (line.getSignals() == null) return null;
        double pos = self.getPositionM();
        Signal best = null;
        for (Signal s : line.getSignals()) {
            double m = line.locateMileage(String.valueOf(s.getSegId()), s.getOffsetCm());
            if (dir == Direction.UP ? m > pos : m < pos) {
                if (best == null) best = s;
                else {
                    double bm = line.locateMileage(String.valueOf(best.getSegId()), best.getOffsetCm());
                    boolean nearer = (dir == Direction.UP) ? (m < bm) : (m > bm);
                    if (nearer) best = s;
                }
            }
        }
        return best != null ? best.getId() : null;
    }
}
