package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.dispatch.dto.SidingStatus;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader.StationEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 侧线调度服务（dispatch 层）。
 *
 * <p>管理北京地铁 9 号线 13 个车站各自的侧线占用状态（每站简化为单车辆容量侧线）。
 * 只负责调度层的占用/释放状态机，不涉及真实道岔/进路操作，不碰 signal 模块。</p>
 *
 * <p>状态机（与 {@link SidingStatus} 注释一致）：
 * <pre>
 *   AVAILABLE → (requestSidingEntry)   → RESERVED  → (confirmOccupied) → OCCUPIED
 *            ↑                                                          │
 *            └──────────────────── (releaseSiding) ─────────────────────┘
 * </pre>
 * 所有状态变更在 {@code synchronized(monitor)} 块内完成，保证多请求并发下的原子性。
 * {@link #sidingMap} 本身是 {@link ConcurrentHashMap}，暴露给读取方法时返回防御性拷贝。</p>
 *
 * <p>数据来源：站名与站点 id 来自 {@code configs/line-profile.json}
 * （通过 {@link LineProfileJsonLoader} 读取），与车辆仿真模块共享同一份站点权威数据。
 * 不读取 {@code line9-stations-geo.json}（地理坐标由 dispatch 地图使用，此处只需 id/name）。</p>
 */
@Service
public class SidingDispatchService {

    private static final Logger log = LoggerFactory.getLogger(SidingDispatchService.class);

    /** 侧线状态枚举（与 SidingStatus.status 字段值保持一致）。 */
    public static final String STATE_AVAILABLE = "AVAILABLE";
    public static final String STATE_RESERVED = "RESERVED";
    public static final String STATE_OCCUPIED = "OCCUPIED";

    /** 13 站侧线状态：key=stationId(1-13), value=状态。 */
    private final Map<Integer, SidingStatus> sidingMap = new ConcurrentHashMap<>();

    private final LineProfileJsonLoader lineProfileJsonLoader;

    public SidingDispatchService(LineProfileJsonLoader lineProfileJsonLoader) {
        this.lineProfileJsonLoader = lineProfileJsonLoader;
    }

    /**
     * 初始化：从 configs/line-profile.json 读取 13 站，全部置为 AVAILABLE。
     * 若读取失败，回退为 13 站占位站名，避免阻断应用启动。
     */
    @PostConstruct
    public void init() {
        sidingMap.clear();
        try {
            List<StationEntry> stations = lineProfileJsonLoader.listStations();
            for (StationEntry st : stations) {
                SidingStatus status = new SidingStatus(st.id, st.name, STATE_AVAILABLE);
                sidingMap.put(st.id, status);
            }
            log.info("侧线调度初始化完成，共加载 {} 个车站侧线", sidingMap.size());
        } catch (Exception e) {
            log.warn("读取站点列表失败，回退为 13 站占位初始化: {}", e.getMessage());
            for (int i = 1; i <= 13; i++) {
                sidingMap.put(i, new SidingStatus(i, "站点" + i, STATE_AVAILABLE));
            }
        }
    }

    /**
     * 调度发起侧线撤离指令：为 {@code trainId} 在 {@code stationId} 预留侧线。
     *
     * <p>成功将状态从 AVAILABLE 切换为 RESERVED，记录 occupiedTrainId 与预留时刻；
     * 调用方（vehicle 层）在车辆真正驶入侧线停车后，应调用 {@link #confirmOccupied}
     * 将状态推进到 OCCUPIED。</p>
     *
     * @param trainId   需要撤离的列车编号
     * @param stationId 故障车所在站点 id（1~13）
     * @return 预留成功后的侧线状态（RESERVED）
     * @throws IllegalArgumentException stationId 越界
     * @throws IllegalStateException    侧线已被占用或预留（非 AVAILABLE），无法撤离
     */
    public SidingStatus requestSidingEntry(String trainId, int stationId) {
        if (trainId == null || trainId.isEmpty()) {
            throw new IllegalArgumentException("trainId 不能为空");
        }
        SidingStatus current = requireStation(stationId);

        synchronized (this) {
            current = sidingMap.get(stationId);
            if (current == null) {
                throw new IllegalArgumentException("站点 id=" + stationId + " 不存在侧线状态记录");
            }
            String state = current.getStatus();
            if (!STATE_AVAILABLE.equals(state)) {
                throw new IllegalStateException(
                        "站点 " + current.getStationName() + "(id=" + stationId + ") 侧线当前为 "
                                + state + "（占用列车 " + current.getOccupiedTrainId()
                                + "），无法接受撤离请求");
            }
            current.setStatus(STATE_RESERVED);
            current.setOccupiedTrainId(trainId);
            current.setReservedAt(System.currentTimeMillis());
        }
        log.info("侧线撤离请求：trainId={} stationId={}({}) → RESERVED",
                trainId, stationId, current.getStationName());
        return copy(current);
    }

    /**
     * 车辆已真正驶入侧线停车，将 RESERVED 推进为 OCCUPIED。
     *
     * <p>仅允许该侧线当前预留列车确认；状态非法时抛异常。</p>
     */
    public synchronized SidingStatus confirmOccupied(String trainId, int stationId) {
        if (trainId == null || trainId.isEmpty()) {
            throw new IllegalArgumentException("trainId 不能为空");
        }
        SidingStatus current = sidingMap.get(stationId);
        if (current == null) {
            throw new IllegalArgumentException("站点 id=" + stationId + " 不存在侧线状态记录");
        }
        if (!STATE_RESERVED.equals(current.getStatus())) {
            throw new IllegalStateException(
                    "站点 " + current.getStationName() + "(id=" + stationId
                            + ") 侧线当前为 " + current.getStatus() + "，无法确认驶入");
        }
        if (!trainId.equals(current.getOccupiedTrainId())) {
            throw new IllegalStateException(
                    "站点 " + current.getStationName() + "(id=" + stationId
                            + ") 侧线预留给 " + current.getOccupiedTrainId()
                            + "，列车 " + trainId + " 无权确认驶入");
        }
        current.setStatus(STATE_OCCUPIED);
        log.info("侧线驶入确认：trainId={} stationId={}({}) → OCCUPIED",
                trainId, stationId, current.getStationName());
        return copy(current);
    }

    /**
     * 查询某站侧线状态。返回防御性拷贝。
     */
    public SidingStatus getSidingStatus(int stationId) {
        SidingStatus status = requireStation(stationId);
        return copy(status);
    }

    /**
     * 故障车离线 / 任务结束，释放侧线。仅占用或预留该侧线的列车可释放。
     *
     * <p>注意：本重载按 {@code trainId} 遍历所有侧线并释放<em>第一条</em>匹配记录。
     * 当同一列车在历史请求中可能关联多个侧线记录时，这会误释放非本次请求的侧线。
     * <b>失败补偿路径请优先使用 {@link #releaseSiding(String, int)}（精确绑定 trainId+stationId）</b>。
     * 本重载仅为兼容既有调用方（如 dispatch 层 /release 端点：列车离线时释放其全部占用）而保留。</p>
     *
     * @param trainId 释放侧线的列车编号
     * @return 释放后的侧线状态（AVAILABLE）
     * @throws IllegalStateException 该列车未占用任何侧线
     */
    public synchronized SidingStatus releaseSiding(String trainId) {
        if (trainId == null || trainId.isEmpty()) {
            throw new IllegalArgumentException("trainId 不能为空");
        }
        for (SidingStatus status : sidingMap.values()) {
            if (trainId.equals(status.getOccupiedTrainId())) {
                return doRelease(status, trainId);
            }
        }
        throw new IllegalStateException("列车 " + trainId + " 未占用任何侧线，无需释放");
    }

    /**
     * 精确释放指定 {@code stationId} 上由 {@code trainId} 占用/预留的侧线。
     *
     * <p>用于车载驶入（/api/vehicle/siding/enter）失败补偿：必须只回滚<b>本次请求刚刚预留</b>
     * 的那一条侧线（trainId + stationId 双绑定），不得遍历所有侧线释放"第一条匹配"，
     * 否则当同一列车已占用 A 站、又申请 B 站失败时，会误把 A 站释放、B 站残留 RESERVED。</p>
     *
     * <p>校验：仅当目标侧线确实由该 trainId 占用/预留（OCCUPIED 或 RESERVED）时才释放；
     * 若状态为 AVAILABLE、或占用列车不是该 trainId，则<b>不修改任何状态</b>并抛
     * {@link IllegalStateException}，由调用方静默忽略（不掩盖原始错误）。</p>
     *
     * @param trainId   释放侧线的列车编号（必须与该侧线 occupiedTrainId 一致）
     * @param stationId 目标站点 id（必须精确匹配本次请求预留的站点）
     * @return 释放后的侧线状态（AVAILABLE）
     * @throws IllegalArgumentException trainId 为空或 stationId 不存在
     * @throws IllegalStateException    该侧线未被该 trainId 占用（状态不匹配或占用者不符）
     */
    public synchronized SidingStatus releaseSiding(String trainId, int stationId) {
        if (trainId == null || trainId.isEmpty()) {
            throw new IllegalArgumentException("trainId 不能为空");
        }
        SidingStatus status = sidingMap.get(stationId);
        if (status == null) {
            throw new IllegalArgumentException("站点 id=" + stationId + " 不存在侧线状态记录");
        }
        if (!trainId.equals(status.getOccupiedTrainId())) {
            throw new IllegalStateException(
                    "站点 " + status.getStationName() + "(id=" + stationId + ") 侧线当前占用列车为 "
                            + status.getOccupiedTrainId() + "，列车 " + trainId + " 无权释放");
        }
        return doRelease(status, trainId);
    }

    /** 实际执行 AVAILABLE 回滚（状态清空 + 日志），由两个 releaseSiding 重载共用。 */
    private SidingStatus doRelease(SidingStatus status, String trainId) {
        String prev = status.getStatus();
        status.setStatus(STATE_AVAILABLE);
        status.setOccupiedTrainId(null);
        status.setReservedAt(null);
        log.info("侧线释放：trainId={} stationId={}({}) {} → AVAILABLE",
                trainId, status.getStationId(), status.getStationName(), prev);
        return copy(status);
    }

    /**
     * 查询所有侧线状态。返回按 stationId 升序的防御性拷贝列表。
     */
    public List<SidingStatus> getAllSidingStatuses() {
        List<SidingStatus> result = new ArrayList<>(sidingMap.values());
        result.sort((a, b) -> Integer.compare(a.getStationId(), b.getStationId()));
        List<SidingStatus> copies = new ArrayList<>(result.size());
        for (SidingStatus s : result) {
            copies.add(copy(s));
        }
        return Collections.unmodifiableList(copies);
    }

    private SidingStatus requireStation(int stationId) {
        SidingStatus status = sidingMap.get(stationId);
        if (status == null) {
            throw new IllegalArgumentException(
                    "站点 id=" + stationId + " 不存在侧线状态记录，有效范围为 1~" + sidingMap.size());
        }
        return status;
    }

    private static SidingStatus copy(SidingStatus src) {
        SidingStatus dst = new SidingStatus(src.getStationId(), src.getStationName(), src.getStatus());
        dst.setOccupiedTrainId(src.getOccupiedTrainId());
        dst.setReservedAt(src.getReservedAt());
        return dst;
    }

    /** 仅供测试：手动重置为给定站点集合的 AVAILABLE 状态。 */
    void resetForTest(Map<Integer, String> stationIdToName) {
        sidingMap.clear();
        Map<Integer, String> ordered = new LinkedHashMap<>(stationIdToName);
        for (Map.Entry<Integer, String> e : ordered.entrySet()) {
            sidingMap.put(e.getKey(), new SidingStatus(e.getKey(), e.getValue(), STATE_AVAILABLE));
        }
    }
}
