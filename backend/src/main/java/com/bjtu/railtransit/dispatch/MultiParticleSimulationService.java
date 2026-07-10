package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.TrainCar;
import com.bjtu.railtransit.domain.model.TrainState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 多质点列车动力学仿真服务 —— 参考中车/铁科院纵向动力学方案。
 *
 * <p>将原有单质点模型升级为6节编组多质点模型，每节车厢独立计算:
 *   - 位置（尾部车厢滞后于车头） → 各车厢处于不同坡度段，坡度阻力独立
 *   - 质量（空车+乘客加载） → 动车/拖车质量不同，载客比例按车厢位置分布
 *   - Davis基本阻力（含v²项）
 *   - 车钩力（弹性-阻尼耦合，相邻车厢间）
 *   - 牵引力/电制动分配（仅动车贡献）
 *
 * <p>编组方案（北京地铁9号线 6B Tc-Mp-M-M-Mp-Tc）:
 *   Car0(Tc): 拖车+司机室, 空重34t, AW2≈50t
 *   Car1(Mp): 动车+受电弓, 空重38t, AW2≈55t, 牵引/电制动
 *   Car2(M):  动车, 空重36t, AW2≈53t, 牵引/电制动
 *   Car3(M):  动车, 空重36t, AW2≈53t, 牵引/电制动
 *   Car4(Mp): 动车+受电弓, 空重38t, AW2≈55t, 牵引/电制动
 *   Car5(Tc): 拖车+司机室, 空重34t, AW2≈50t
 *
 * <p>载客分布模型（高峰期）:
 *   中间车厢(Car2, Car3) 载客率 1.0~1.2 (最拥挤)
 *   中部车厢(Car1, Car4) 载客率 0.85~1.0
 *   端部车厢(Car0, Car5) 载客率 0.7~0.85
 */
@Service
public class MultiParticleSimulationService {

    private static final Logger log = LoggerFactory.getLogger(MultiParticleSimulationService.class);

    /** 车厢长度 m (B型车标准) */
    private static final double CAR_LENGTH = 19.0;

    /** 重力加速度 m/s² */
    private static final double GRAVITY = 9.80665;

    /** m/s → km/h */
    private static final double KMH_PER_MS = 3.6;

    /**
     * 车钩刚度系数 N/m (参考中车缓冲器参数, 每cm压缩约35kN)
     * 对应 1m 压缩 ≈ 3.5 MN
     */
    private static final double COUPLER_STIFFNESS = 3.5e6;

    /**
     * 车钩阻尼系数 N·s/m (参考中车缓冲器阻尼)
     */
    private static final double COUPLER_DAMPING = 5.0e4;

    /** 车钩自由间隙 m (车钩正常间距≈0.8m) */
    private static final double COUPLER_FREE_LENGTH = 0.8;

    // ═══════════════════════════════════════════════════════════════
    // 车厢配置
    // ═══════════════════════════════════════════════════════════════

    /** AW2 定员 (B型车: Tc≈230人, Mp/M≈250人, 人均65kg) */
    private static final double PASSENGER_MASS_KG = 65.0;
    private static final double TC_AW2_PASSENGERS = 230;
    private static final double M_AW2_PASSENGERS = 250;

    // ═══════════════════════════════════════════════════════════════

    /**
     * 初始化6节编组车厢列表（空车状态）。
     */
    public List<TrainCar> initConsist() {
        return initConsistWithLoad(0.0); // 默认空车
    }

    /**
     * 初始化6节编组车厢列表，按载客率加载乘客。
     *
     * @param baseLoadRatio 基准载客率 (0.0~1.5, 代表列车满载率)
     * @return 带载客的车厢列表
     */
    public List<TrainCar> initConsistWithLoad(double baseLoadRatio) {
        List<TrainCar> cars = new ArrayList<>(6);

        // 载客率分布因子（高峰分布: 中间高, 两端低）
        double[] loadFactors = distributePassengerLoad(baseLoadRatio);

        // Car0: Tc (拖车+司机室)
        TrainCar c0 = new TrainCar(0, "Tc", false, 34000.0);
        applyPassengerLoad(c0, loadFactors[0], TC_AW2_PASSENGERS);
        cars.add(c0);

        // Car1: Mp (动车+受电弓)
        TrainCar c1 = new TrainCar(1, "Mp", true, 38000.0);
        applyPassengerLoad(c1, loadFactors[1], M_AW2_PASSENGERS);
        cars.add(c1);

        // Car2: M (动车)
        TrainCar c2 = new TrainCar(2, "M", true, 36000.0);
        applyPassengerLoad(c2, loadFactors[2], M_AW2_PASSENGERS);
        cars.add(c2);

        // Car3: M (动车)
        TrainCar c3 = new TrainCar(3, "M", true, 36000.0);
        applyPassengerLoad(c3, loadFactors[3], M_AW2_PASSENGERS);
        cars.add(c3);

        // Car4: Mp (动车+受电弓)
        TrainCar c4 = new TrainCar(4, "Mp", true, 38000.0);
        applyPassengerLoad(c4, loadFactors[4], M_AW2_PASSENGERS);
        cars.add(c4);

        // Car5: Tc (拖车+司机室)
        TrainCar c5 = new TrainCar(5, "Tc", false, 34000.0);
        applyPassengerLoad(c5, loadFactors[5], TC_AW2_PASSENGERS);
        cars.add(c5);

        return cars;
    }

    /**
     * 载客量分布模型：高峰时段中间车厢最拥挤。
     *
     * 分布: [0.75, 0.88, 1.05, 1.05, 0.88, 0.75] × baseRatio
     */
    private double[] distributePassengerLoad(double baseRatio) {
        if (baseRatio <= 0) return new double[]{0, 0, 0, 0, 0, 0};
        double[] factors = {0.75, 0.88, 1.05, 1.05, 0.88, 0.75};
        double[] result = new double[6];
        for (int i = 0; i < 6; i++) {
            result[i] = Math.min(1.5, factors[i] * baseRatio);
        }
        return result;
    }

    private void applyPassengerLoad(TrainCar car, double loadFactor, double aw2Passengers) {
        car.setPassengerLoadRatio(loadFactor);
        double passengerMass = aw2Passengers * loadFactor * PASSENGER_MASS_KG;
        car.setOccupiedMass(car.getCurbMass() + passengerMass);
    }

    // ═══════════════════════════════════════════════════════════════
    // 核心：多质点动力学步进
    // ═══════════════════════════════════════════════════════════════

    /**
     * 多质点单步更新 —— 在1个仿真秒内更新整列6节车厢。
     *
     * <p>调用顺序:
     *   1. 计算每节车厢的 Davis 基本阻力 + 坡度阻力
     *   2. 计算车钩力（相邻车厢速度差 + 位置差）
     *   3. 施加控制力（牵引/制动, 只在动车上施加）
     *   4. 合力 → 加速度 → 积分速度/位置
     *
     * @param cars        6节车厢列表
     * @param targetAccel 目标加速度 m/s² (正值=牵引, 负值=制动)
     * @param dt          步长 s
     * @param inBraking   是否处于制动状态 (电制动优先+空气制动补充)
     */
    public void stepConsist(List<TrainCar> cars, double targetAccel, double dt, boolean inBraking) {
        if (cars == null || cars.size() != 6) return;

        int n = cars.size();
        double[] dragForces = new double[n];   // 阻力 (N, 正值=阻碍运动)
        double[] controlForces = new double[n]; // 控制力 (N, 正值=牵引)
        double[] couplerForces = new double[n]; // 车钩合力
        double[] netForces = new double[n];
        double[] accelerations = new double[n];

        // ── Step 1: 计算各车厢阻力 ──
        for (int i = 0; i < n; i++) {
            TrainCar c = cars.get(i);
            dragForces[i] = computeDragForce(c);
        }

        // ── Step 2: 计算车钩力 ──
        for (int i = 0; i < n; i++) {
            couplerForces[i] = 0;
        }
        for (int i = 0; i < n - 1; i++) {
            TrainCar front = cars.get(i);
            TrainCar rear = cars.get(i + 1);
            double couplerForce = computeCouplerForce(front, rear);
            // 前车受向后拉力（-），后车受向前拉力（+）
            couplerForces[i] -= couplerForce;
            couplerForces[i + 1] += couplerForce;
        }

        // ── Step 3: 分配控制力 ──
        if (inBraking) {
            // 制动: 电制动优先(仅动车) + 空气制动(所有车)
            applyBrakingForce(cars, targetAccel, controlForces);
        } else if (targetAccel > 0) {
            // 牵引: 仅动车贡献
            applyTractiveForce(cars, targetAccel, controlForces);
        }
        // 惰行: controlForces = 0

        // ── Step 4: 合力 → 加速度 → 积分 ──
        for (int i = 0; i < n; i++) {
            TrainCar c = cars.get(i);
            // 控制力方向: 正=向前加速
            netForces[i] = controlForces[i] - dragForces[i] + couplerForces[i];
            accelerations[i] = netForces[i] / c.getOccupiedMass();
            c.setAccelerationKmhs(accelerations[i] * KMH_PER_MS);
        }

        // 积分（梯形法）
        for (int i = 0; i < n; i++) {
            TrainCar c = cars.get(i);
            double vMs = c.getSpeedMps();
            double vNextMs = vMs + accelerations[i] * dt;
            if (vNextMs < 0) vNextMs = 0;
            double avgVMs = (vMs + vNextMs) / 2.0;
            c.setPositionMeters(c.getPositionMeters() + avgVMs * dt);
            c.setSpeedKmh(vNextMs * KMH_PER_MS);
        }
    }

    /**
     * 初始化车厢位置：以车头位置为基准，各车厢依次后移。
     *
     * @param cars        车厢列表
     * @param headPosM    车头位置 m
     * @param speedKmh    初始速度
     */
    public void initCarPositions(List<TrainCar> cars, double headPosM, double speedKmh) {
        for (int i = 0; i < cars.size(); i++) {
            // Car0 的车头在 headPosM, 车厢后部 = headPosM - CAR_LENGTH
            // Car1 的车头 = headPosM - CAR_LENGTH - 车钩间隙
            double posOffset = i * (CAR_LENGTH + COUPLER_FREE_LENGTH);
            cars.get(i).setPositionMeters(headPosM - posOffset);
            cars.get(i).setSpeedKmh(speedKmh);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 阻力计算
    // ═══════════════════════════════════════════════════════════════

    /**
     * 计算单节车厢总阻力 (N) = Davis基本阻力 + 坡度阻力。
     *
     * <p>Davis 公式: w0 = a + b·v + c·v² (N/kN), v单位km/h
     * Davis力 = w0 · (mass_kg · g / 1000) (N)
     *
     * <p>坡度阻力: F_grade = mass · g · gradient(pos)
     */
    private double computeDragForce(TrainCar car) {
        double vKmh = car.getSpeedKmh();
        double davisW0 = car.getDavisA() + car.getDavisB() * vKmh + car.getDavisC() * vKmh * vKmh;
        // 重量 kN = mass_kg * g / 1000
        double weightKN = car.getOccupiedMass() * GRAVITY / 1000.0;
        double davisForceN = davisW0 * weightKN;

        // 坡度阻力: 这里需要线路数据, 暂时由调用方通过 gradeResistance 字段传入
        double gradeForceN = car.getGradeResistance() * car.getOccupiedMass();

        return davisForceN + gradeForceN;
    }

    // ═══════════════════════════════════════════════════════════════
    // 车钩力 (弹性-阻尼耦合)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 计算前车(front)和后车(rear)之间的车钩力。
     *
     * <p>规定: 正值 = 前车拉后车 (拉伸力)
     *
     * <p>模型: F_coupler = k · (Δx - L0) + c · Δv
     *   - k: 缓冲器刚度 (N/m)
     *   - Δx = front.tailPos - rear.headPos (间距, 正值=钩拉开)
     *   - L0: 自由间隙 0.8m
     *   - c: 阻尼系数 (N·s/m)
     *   - Δv = front.tailVel - rear.headVel (相对速度)
     */
    private double computeCouplerForce(TrainCar front, TrainCar rear) {
        double frontTailPos = front.getPositionMeters(); // 前车尾部
        double rearHeadPos = rear.getPositionMeters() + CAR_LENGTH; // 后车头部
        double dx = frontTailPos - rearHeadPos;

        double frontTailVel = front.getSpeedMps();
        double rearHeadVel = rear.getSpeedMps();
        double dv = frontTailVel - rearHeadVel;

        double springForce = COUPLER_STIFFNESS * (dx - COUPLER_FREE_LENGTH);
        double dampingForce = COUPLER_DAMPING * dv;

        return springForce + dampingForce;
    }

    // ═══════════════════════════════════════════════════════════════
    // 牵引/制动力分配
    // ═══════════════════════════════════════════════════════════════

    /**
     * 分配牵引力到动车车厢。
     *
     * <p>整列车需要的总牵引力:
     *   F_total = totalMass · targetAccel
     *
     * <p>分配到各节动车:
     *   F_car_i = (tractive_capacity_i / total_tractive_capacity) · F_total
     *
     * 每节动车贡献不超过其 maxTractiveEffortN。
     */
    private void applyTractiveForce(List<TrainCar> cars, double targetAccel, double[] forces) {
        // 总质量
        double totalMass = 0;
        double totalTractiveCap = 0;
        for (TrainCar c : cars) {
            totalMass += c.getOccupiedMass();
            if (c.isMotored()) {
                totalTractiveCap += c.getMaxTractiveEffortN();
            }
        }

        double totalNeededForce = totalMass * targetAccel; // N

        for (int i = 0; i < cars.size(); i++) {
            TrainCar c = cars.get(i);
            if (c.isMotored() && totalTractiveCap > 0) {
                double share = c.getMaxTractiveEffortN() / totalTractiveCap;
                forces[i] = Math.min(totalNeededForce * share, c.getMaxTractiveEffortN());
            }
            // 拖车: forces[i] = 0
        }
    }

    /**
     * 分配制动力到各车厢。
     *
     * <p>中车方案: 电制动优先(动车), 不足部分由空气制动补充(所有车厢)。
     * 此处简化: 需要总制动力均分到各车厢, 动车部分由电制动cover, 其余为空气制动。
     *
     * <p>F_total = totalMass · |targetDecel|
     * 每节车厢应出力 = F_total / 6
     */
    private void applyBrakingForce(List<TrainCar> cars, double targetDecel, double[] forces) {
        double totalMass = 0;
        for (TrainCar c : cars) {
            totalMass += c.getOccupiedMass();
        }

        double absDecel = Math.abs(targetDecel);
        double totalBrakeForce = totalMass * absDecel;
        double forcePerCar = totalBrakeForce / cars.size();

        for (int i = 0; i < cars.size(); i++) {
            TrainCar c = cars.get(i);
            double electricCap = c.isMotored() ? c.getMaxElectricBrakeForceN() : 0;
            // 电制动优先
            double electric = Math.min(forcePerCar, electricCap);
            // 不足部分由空气制动补充 (此处空气制动力用负控制力表示)
            double air = forcePerCar - electric;
            forces[i] = -(electric + air); // 负值 = 减速
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 坡度阻力注入（由线路数据服务传入）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据线路数据更新各车厢的坡度阻力减速度。
     *
     * @param cars          车厢列表
     * @param gradientAt    坡度查询函数: positionMeters → gradient (‰)
     */
    public void updateGradeResistance(List<TrainCar> cars, java.util.function.DoubleFunction<Double> gradientAt) {
        if (cars == null || gradientAt == null) return;
        for (TrainCar c : cars) {
            double centerPos = c.getCenterPositionMeters();
            double gradient = gradientAt.apply(centerPos); // ‰
            // 坡度阻力减速度 = g · gradient/1000 (正值=上坡阻碍)
            c.setGradeResistance(GRAVITY * gradient / 1000.0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 车厢状态快照 (供信号系统上报中控)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 车厢状态摘要 —— 供 TrainState.cars 和 StatusReport 使用。
     */
    public static class CarStatusSnapshot {
        public int carIndex;
        public String carType;
        public boolean motored;
        public double curbMass;
        public double occupiedMass;
        public double passengerLoadRatio;
        public double positionMeters;
        public double speedKmh;
        public double accelerationKmhs;
        public double couplerForceKN;   // 车钩力 kN (受拉为正)
        public double gradeResistance;
        public String health;
    }

    /**
     * 生成车厢状态快照列表。
     */
    public List<CarStatusSnapshot> snapshotCars(List<TrainCar> cars) {
        List<CarStatusSnapshot> snaps = new ArrayList<>();
        if (cars == null) return snaps;
        for (TrainCar c : cars) {
            CarStatusSnapshot s = new CarStatusSnapshot();
            s.carIndex = c.getCarIndex();
            s.carType = c.getCarType();
            s.motored = c.isMotored();
            s.curbMass = c.getCurbMass();
            s.occupiedMass = c.getOccupiedMass();
            s.passengerLoadRatio = c.getPassengerLoadRatio();
            s.positionMeters = c.getPositionMeters();
            s.speedKmh = c.getSpeedKmh();
            s.accelerationKmhs = c.getAccelerationKmhs();
            s.couplerForceKN = c.getCouplerForceN() / 1000.0;
            s.gradeResistance = c.getGradeResistance();
            s.health = c.getHealth();
            snaps.add(s);
        }
        return snaps;
    }

    /**
     * 从车厢快照重建 TrainState.cars (供中控消费车载上报)。
     */
    public List<TrainCar> fromSnapshots(List<CarStatusSnapshot> snaps) {
        List<TrainCar> cars = new ArrayList<>();
        if (snaps == null) return cars;
        for (CarStatusSnapshot s : snaps) {
            TrainCar c = new TrainCar(s.carIndex, s.carType, s.motored, s.curbMass);
            c.setOccupiedMass(s.occupiedMass);
            c.setPassengerLoadRatio(s.passengerLoadRatio);
            c.setPositionMeters(s.positionMeters);
            c.setSpeedKmh(s.speedKmh);
            c.setAccelerationKmhs(s.accelerationKmhs);
            c.setCouplerForceN(s.couplerForceKN * 1000.0);
            c.setGradeResistance(s.gradeResistance);
            c.setHealth(s.health);
            cars.add(c);
        }
        return cars;
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    /** 获取列车总质量 kg */
    public double getTotalMass(List<TrainCar> cars) {
        double total = 0;
        for (TrainCar c : cars) total += c.getOccupiedMass();
        return total;
    }

    /** 获取列车头车位置（Car0头部）*/
    public double getHeadPosition(List<TrainCar> cars) {
        if (cars == null || cars.isEmpty()) return 0;
        return cars.get(0).getHeadPositionMeters();
    }

    /** 获取列车尾车位置（Car5尾部）*/
    public double getTailPosition(List<TrainCar> cars) {
        if (cars == null || cars.isEmpty()) return 0;
        TrainCar last = cars.get(cars.size() - 1);
        return last.getPositionMeters();
    }

    /** 获取编组总长度 m */
    public double getConsistLength(List<TrainCar> cars) {
        if (cars == null || cars.isEmpty()) return 0;
        return cars.size() * CAR_LENGTH + (cars.size() - 1) * COUPLER_FREE_LENGTH;
    }
}
