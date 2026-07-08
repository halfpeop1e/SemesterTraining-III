package com.bjtu.railtransit.signal.service;

import com.bjtu.railtransit.signal.model.*;
import com.bjtu.railtransit.signal.domain.Direction;
import com.bjtu.railtransit.signal.domain.TrainState;
import com.bjtu.railtransit.signal.util.Units;

import java.util.List;

/**
 * 轨道约束服务：把真实线路数据（限速/道岔/折返/TSR/信号机/进路/计轴占用）
 * 转成 MA 的六维 EoA 截断点，并给出含坡度制动修正的追踪间隔。
 *
 * 设计要点（tech-design.md §2.3 / §2.4）：
 *  - 每个 eoaFromXxx 返回一个「里程位置」或 +INF（无此约束）。compute 取 min。
 *  - 坡度对制动的修正：a_eff = a_brake + g·(permille/1000)，上坡助力、下坡削弱
 *    （与 §2.4 正文「下坡有效减速度变小、制动距离变长」一致；doc 中公式符号取 +）。
 *  - fail-safe：道岔状态不明/非定位、计轴占用不明 → 一律按「不授权越过」处理。
 */
public class TrackConstraintService {

    public static final double G = 9.81;                       // m/s²
    public static final double INF = Double.POSITIVE_INFINITY;

    private final MaConfig cfg;

    public TrackConstraintService(MaConfig cfg) { this.cfg = cfg; }

    // ================= 追踪间隔（含坡度制动修正） =================

    /**
     * 后车与本车之间需保持的安全净距（m）。
     * requiredGap = safeSeparationM + self.speedMps · t_total，
     * t_total = t_reaction + t_brake + t_safety_margin，
     * t_brake = speedMps / a_eff，a_eff = a_brake + g·(permille/1000)（下坡 permille<0 → a_eff 变小 → gap 变大）。
     */
    public double requiredGap(TrainState self, TrainState preceding, LineProfile line) {
        double v = Units.kmhToMps(self.getSpeedKmh());
        double permille = gradientAt(line, self.getPositionM());
        double aEff = cfg.aBrakeMps2 + G * (permille / 1000.0);
        aEff = Math.max(aEff, cfg.aFloorMps2);
        double tBrake = v / aEff;
        double tTotal = cfg.tReactionS + tBrake + cfg.tSafetyMarginS;
        return cfg.safeSeparationM + v * tTotal;
    }

    // ================= 六维约束：EoA 截断点 =================

    /**
     * 线路/道岔/折返/TSR 综合位置约束（取沿行车方向最近边界）。
     *   - UP  ：取「上方最近边界」= min（eoaFromTurnback, eoaFromSwitch）；
     *   - DOWN：取「下方最近边界」= max（两者均为不可越过的下限）。
     * 任一为 +INF 表示无该约束，取另一个有限值。方向感知，勿当 UP-only 使用。
     */
    public double eoaFromLineLimits(LineProfile line, TrainState t) {
        boolean down = (t.getDirection() == Direction.DOWN);
        double a = eoaFromTurnback(line, t);
        double b = eoaFromSwitch(line, t);
        if (!Double.isFinite(a)) return b;
        if (!Double.isFinite(b)) return a;
        return down ? Math.max(a, b) : Math.min(a, b);
    }

    /** 不越过折返线/线路终点。UP：取 pos 之后最近的折返；DOWN：取 pos 之前最近的折返（或线路起点 0）。 */
    public double eoaFromTurnback(LineProfile line, TrainState t) {
        double pos = t.getPositionM();
        boolean down = (t.getDirection() == Direction.DOWN);
        double limit = down ? 0.0 : line.getTotalLengthM();
        if (line.getTurnbacks() != null) {
            for (Turnback tb : line.getTurnbacks()) {
                double tbM = tb.getPositionM();
                if (down) {
                    if (tbM <= pos && tbM > limit) limit = tbM;            // 下方最近折返
                } else {
                    if (tbM >= pos && tbM < limit) limit = tbM;            // 上方最近折返
                }
            }
        }
        return limit;
    }

    /** 前方道岔状态不符（非定位 / 失表）→ 止于道岔前（fail-safe）。方向感知：UP 取上方最近，DOWN 取下方最近。无异常道岔→ +INF（无约束）。 */
    public double eoaFromSwitch(LineProfile line, TrainState t) {
        if (line.getSwitches() == null) return INF;
        boolean down = (t.getDirection() == Direction.DOWN);
        double limit = INF;
        boolean found = false;
        for (Switch sw : line.getSwitches()) {
            double m = switchMileage(line, sw);
            boolean ahead = down ? (m < t.getPositionM()) : (m > t.getPositionM());
            if (!ahead) continue;
            if (sw.getState() == null || sw.getState() != SwitchState.NORMAL) {
                if (!found) { limit = m; found = true; }
                else if (down ? (m > limit) : (m < limit)) limit = m;   // DOWN 取下方最近(最大 m)
            }
        }
        return found ? limit : INF;
    }

    /**
     * 前方防护信号机 → 止于机前（留停车余量 signalStopMarginM）。
     * 方向感知（机前 = 列车沿行车方向的接近侧）：
     *   - UP  （里程递增）：停在机前（高里程侧）= m - margin；
     *   - DOWN（里程递减）：停在机前（低里程侧）= m + margin（列车从高里程驶来的接近侧）。
     * 取行车方向最近的信号机；无前方信号 → +INF（无约束）。
     *
     * 信号机显示状态（与司机台协议对齐，仅本模块 scope）：
     *   - 放行显示（SignalAspect.isProceed()==true，当前仅绿灯）→ 不截断，列车可越过；
     *   - 停车/限制/灭/断显示，或 aspect==null（未接入）→ 按 fail-safe 截断（保守）。
     */
    public double eoaFromSignals(LineProfile line, TrainState t) {
        if (line.getSignals() == null) return INF;
        boolean down = (t.getDirection() == Direction.DOWN);
        double limit = INF;
        boolean found = false;
        for (Signal s : line.getSignals()) {
            double m = signalMileage(line, s);
            boolean ahead = down ? (m < t.getPositionM()) : (m > t.getPositionM());
            if (!ahead) continue;
            // 放行信号不构成 EoA 边界，越过该信号继续找下一架停车信号
            if (s.getAspect() != null && s.getAspect().isProceed()) continue;
            double cand = down ? m + cfg.signalStopMarginM : m - cfg.signalStopMarginM;
            if (!found) { limit = cand; found = true; }
            else if (down ? (cand > limit) : (cand < limit)) limit = cand;
        }
        return found ? limit : INF;
    }

    /** 前方计轴区段 occupied → 止于该区段起点。方向感知：UP 取上方最近，DOWN 取下方最近。无占用→ +INF（无约束）。 */
    public double eoaFromAxleOccupancy(LineProfile line, TrainState t) {
        if (line.getAxleSections() == null) return INF;
        boolean down = (t.getDirection() == Direction.DOWN);
        double limit = INF;
        boolean found = false;
        for (AxleCounterSection a : line.getAxleSections()) {
            if (!a.isOccupied()) continue;
            double m = axleSectionStartM(line, a);
            boolean ahead = down ? (m < t.getPositionM()) : (m > t.getPositionM());
            if (!ahead) continue;
            if (!found) { limit = m; found = true; }
            else if (down ? (m > limit) : (m < limit)) limit = m;
        }
        return found ? limit : INF;
    }

    /** 沿已建立进路授权：UP → 终端信号机 + 保护区段末端；DOWN → 始端信号机 + 保护区段起点（镜像）。 */
    public double eoaFromRoute(LineProfile line, TrainState t, Route route) {
        if (route == null) return INF;
        boolean down = (t.getDirection() == Direction.DOWN);
        double eoa = INF;
        Signal termSig = down ? findSignalById(line, route.getStartSignalId())
                              : findSignalById(line, route.getEndSignalId());
        if (termSig != null) eoa = signalMileage(line, termSig);
        if (route.getOverlapIds() != null) {
            for (Integer oid : route.getOverlapIds()) {
                OverlapSection ov = findOverlapById(line, oid);
                if (ov != null) {
                    if (down) {
                        double ovStart = overlapStartM(line, ov);
                        if (ovStart < eoa) eoa = ovStart;
                    } else {
                        double ovEnd = overlapEndM(line, ov);
                        if (ovEnd > eoa) eoa = ovEnd;
                    }
                }
            }
        }
        return eoa;
    }

    // ================= 限速查询 =================

    /** EoA 所处区间允许的最大速度 km/h（取 min：默认限速 / 固定限速 / 激活 TSR / 侧向道岔）。 */
    public double speedLimitAt(LineProfile line, double mileage, Direction dir, double trainPos) {
        double limit = cfg.defaultLineSpeedKmh;
        // 固定限速表
        if (line.getStaticSpeedRestrictions() != null) {
            for (StaticSpeedRestriction s : line.getStaticSpeedRestrictions()) {
                double a = line.locateMileage(String.valueOf(s.getSegId()), s.getStartOffsetCm());
                double b = line.locateMileage(String.valueOf(s.getSegId()), s.getEndOffsetCm());
                if (mileage >= a && mileage <= b) limit = Math.min(limit, s.getSpeedLimitKmh());
            }
        }
        // 激活 TSR
        if (line.getTsrs() != null) {
            for (TemporarySpeedRestriction tsr : line.getTsrs()) {
                if (tsr.isActive() && mileage >= tsr.getStartM() && mileage <= tsr.getEndM()) {
                    limit = Math.min(limit, tsr.getSpeedLimitKmh());
                }
            }
        }
        // 侧向道岔（本车与 EoA 之间若存在反位道岔，取侧向限速；方向感知）
        if (line.getSwitches() != null) {
            boolean down = (dir == Direction.DOWN);
            for (Switch sw : line.getSwitches()) {
                double m = switchMileage(line, sw);
                boolean between = down ? (m < trainPos && m >= mileage)
                                       : (m > trainPos && m <= mileage);
                if (between && sw.getState() == SwitchState.REVERSE) {
                    limit = Math.min(limit, sw.getDivergingSpeedLimitKmh());
                }
            }
        }
        return limit;
    }

    // ================= 坡度 =================

    /** 取列车在 mileage 处（下坡最不利）的坡度 ‰；无则 0。 */
    public double gradientAt(LineProfile line, double mileage) {
        if (line.getGradients() == null) return 0.0;
        double worst = 0.0;
        for (Gradient g : line.getGradients()) {
            double s = line.locateMileage(String.valueOf(g.getStartSegId()), g.getStartOffsetCm());
            double e = line.locateMileage(String.valueOf(g.getEndSegId()), g.getEndOffsetCm());
            if (mileage >= s && mileage <= e && g.getPermille() < worst) {
                worst = g.getPermille();
            }
        }
        return worst;
    }

    // ================= 位置辅助 =================

    public double switchMileage(LineProfile line, Switch sw) {
        if (sw.getPositionM() != 0.0) return sw.getPositionM();
        double[] m = line.getSegmentMileage().get(String.valueOf(sw.getMergeSegId()));
        return m != null ? m[0] : Double.NaN;
    }

    public double signalMileage(LineProfile line, Signal s) {
        return line.locateMileage(String.valueOf(s.getSegId()), s.getOffsetCm());
    }

    public double axleSectionStartM(LineProfile line, AxleCounterSection a) {
        double min = INF;
        if (a.getSegIds() != null) {
            for (Integer segId : a.getSegIds()) {
                double sm = line.segStartM(String.valueOf(segId));
                if (!Double.isNaN(sm) && sm < min) min = sm;
            }
        }
        return min;
    }

    public double axleSectionEndM(LineProfile line, AxleCounterSection a) {
        double max = -INF;
        if (a.getSegIds() != null) {
            for (Integer segId : a.getSegIds()) {
                double em = line.segEndM(String.valueOf(segId));
                if (!Double.isNaN(em) && em > max) max = em;
            }
        }
        return max;
    }

    public double overlapEndM(LineProfile line, OverlapSection ov) {
        double max = -INF;
        if (ov.getAxleSectionIds() != null) {
            for (Integer aid : ov.getAxleSectionIds()) {
                AxleCounterSection a = findAxleById(line, aid);
                if (a != null) max = Math.max(max, axleSectionEndM(line, a));
            }
        }
        return max;
    }

    /** DOWN 方向保护区段起点（取所含计轴区段起点的最小值）。 */
    public double overlapStartM(LineProfile line, OverlapSection ov) {
        double min = INF;
        if (ov.getAxleSectionIds() != null) {
            for (Integer aid : ov.getAxleSectionIds()) {
                AxleCounterSection a = findAxleById(line, aid);
                if (a != null) min = Math.min(min, axleSectionStartM(line, a));
            }
        }
        return min;
    }

    // ================= 查找 =================

    public Signal findSignalById(LineProfile line, int id) {
        if (line.getSignals() == null) return null;
        for (Signal s : line.getSignals()) if (s.getId() == id) return s;
        return null;
    }
    public OverlapSection findOverlapById(LineProfile line, int id) {
        if (line.getOverlaps() == null) return null;
        for (OverlapSection o : line.getOverlaps()) if (o.getId() == id) return o;
        return null;
    }
    public AxleCounterSection findAxleById(LineProfile line, int id) {
        if (line.getAxleSections() == null) return null;
        for (AxleCounterSection a : line.getAxleSections()) if (a.getId() == id) return a;
        return null;
    }
}
