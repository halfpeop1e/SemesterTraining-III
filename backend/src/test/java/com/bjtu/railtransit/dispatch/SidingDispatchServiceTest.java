package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.dispatch.dto.SidingStatus;
import com.bjtu.railtransit.vehicle.service.LineProfileJsonLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SidingDispatchService 单元测试。
 *
 * <p>覆盖护栏中要求的状态机：
 * <ol>
 *   <li>初始化后 13 站全部 AVAILABLE。</li>
 *   <li>requestSidingEntry 成功 → 状态变 RESERVED。</li>
 *   <li>重复 requestSidingEntry 同一站 → 抛异常。</li>
 *   <li>releaseSiding → 状态变 AVAILABLE。</li>
 * </ol>
 * </p>
 *
 * <p>说明：这里不启动 Spring，直接 new SidingDispatchService，并用
 * {@code resetForTest} 注入 13 站名，避免对 classpath JSON 的依赖。
 * 真实环境的站点加载由 @PostConstruct 走 LineProfileJsonLoader 负责。</p>
 */
class SidingDispatchServiceTest {

    private SidingDispatchService service;

    @BeforeEach
    void setUp() {
        // 不走 @PostConstruct 的 JSON 加载，手动注入 13 站
        service = new SidingDispatchService(new LineProfileJsonLoader());
        Map<Integer, String> stations = new LinkedHashMap<>();
        for (int i = 1; i <= 13; i++) {
            stations.put(i, "站点" + i);
        }
        service.resetForTest(stations);
    }

    @Test
    void allSidingsAvailableAfterInit() {
        List<SidingStatus> all = service.getAllSidingStatuses();
        assertEquals(13, all.size(), "初始化后应有 13 站侧线");
        for (SidingStatus s : all) {
            assertEquals("AVAILABLE", s.getStatus(),
                    "站点 " + s.getStationId() + " 初始化后应为 AVAILABLE");
            assertNull(s.getOccupiedTrainId(), "AVAILABLE 时不应有占用列车");
        }
        // 站点 id 升序
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).getStationId());
        }
    }

    @Test
    void requestSidingEntryTransitionsToReserved() {
        SidingStatus before = service.getSidingStatus(5);
        assertEquals("AVAILABLE", before.getStatus());

        SidingStatus result = service.requestSidingEntry("T1", 5);

        assertEquals("RESERVED", result.getStatus(), "请求成功后状态应为 RESERVED");
        assertEquals("T1", result.getOccupiedTrainId());
        assertNotNull(result.getReservedAt(), "预留时刻应被记录");

        // 持久化生效
        SidingStatus after = service.getSidingStatus(5);
        assertEquals("RESERVED", after.getStatus());
        assertEquals("T1", after.getOccupiedTrainId());

        // 其他站不受影响
        assertEquals("AVAILABLE", service.getSidingStatus(6).getStatus());
    }

    @Test
    void duplicateRequestOnSameStationThrows() {
        service.requestSidingEntry("T1", 5);

        // 同一站再请求（不同列车）→ 抛 IllegalStateException
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.requestSidingEntry("T2", 5));
        assertTrue(ex.getMessage().contains("RESERVED") || ex.getMessage().contains("无法接受撤离请求"),
                "异常信息应说明侧线已被占用/预留: " + ex.getMessage());

        // 状态仍是 T1 的 RESERVED
        SidingStatus still = service.getSidingStatus(5);
        assertEquals("RESERVED", still.getStatus());
        assertEquals("T1", still.getOccupiedTrainId());
    }

    @Test
    void confirmOccupiedTransitionsReservedToOccupied() {
        service.requestSidingEntry("T1", 7);

        SidingStatus confirmed = service.confirmOccupied("T1", 7);

        assertEquals("OCCUPIED", confirmed.getStatus(), "确认驶入后状态应为 OCCUPIED");
        assertEquals("T1", confirmed.getOccupiedTrainId());

        // 释放
        SidingStatus released = service.releaseSiding("T1");
        assertEquals("AVAILABLE", released.getStatus(), "释放后状态应回到 AVAILABLE");
        assertNull(released.getOccupiedTrainId(), "释放后 occupiedTrainId 应为 null");
        assertNull(released.getReservedAt(), "释放后 reservedAt 应为 null");
    }

    @Test
    void releaseSidingRestoresAvailable() {
        // 直接从 RESERVED 释放（故障车未真正驶入即离线）
        service.requestSidingEntry("T3", 3);
        assertEquals("RESERVED", service.getSidingStatus(3).getStatus());

        SidingStatus released = service.releaseSiding("T3");

        assertEquals("AVAILABLE", released.getStatus());
        assertEquals(3, released.getStationId(), "释放返回的应为对应站点状态");
        assertNull(released.getOccupiedTrainId());

        // 再次释放同一列车 → 抛异常（已无占用）
        assertThrows(IllegalStateException.class, () -> service.releaseSiding("T3"));
    }

    @Test
    void requestWithInvalidStationIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.requestSidingEntry("T1", 99));
        assertThrows(IllegalArgumentException.class,
                () -> service.requestSidingEntry("T1", 0));
    }

    @Test
    void requestWithBlankTrainIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.requestSidingEntry("", 1));
    }

    @Test
    void confirmByWrongTrainIsRejected() {
        service.requestSidingEntry("T1", 4);
        // T2 无权确认 T1 预留的侧线
        assertThrows(IllegalStateException.class,
                () -> service.confirmOccupied("T2", 4));
        // 状态仍是 T1 RESERVED
        assertEquals("RESERVED", service.getSidingStatus(4).getStatus());
    }
}
