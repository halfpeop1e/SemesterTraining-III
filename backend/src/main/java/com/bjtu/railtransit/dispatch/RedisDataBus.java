package com.bjtu.railtransit.dispatch;

import com.bjtu.railtransit.domain.model.*;
import com.bjtu.railtransit.signal.domain.MovingAuthority;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 数据总线 —— 仿真平台第二层（实时数据库）的核心实现。
 *
 * <h3>通道设计</h3>
 * <table>
 *   <tr><th>Redis Key / Channel</th><th>类型</th><th>内容</th><th>写入方</th><th>消费方</th></tr>
 *   <tr><td>rail:sim:latest-snapshot</td><td>STRING</td><td>最新 SimulationSnapshot JSON</td><td>SimulationService</td><td>WS Handler / 外部系统</td></tr>
 *   <tr><td>rail:sim:train:{id}</td><td>STRING</td><td>单列车 TrainState JSON</td><td>SimulationService</td><td>HilGateway / 信号系统</td></tr>
 *   <tr><td>rail:sim:commands:open</td><td>HASH</td><td>当前活跃指令</td><td>CommandBus</td><td>车载 / 中控前端</td></tr>
 *   <tr><td>rail:sim:ma</td><td>HASH</td><td>移动授权 (trainId → MA)</td><td>MovementAuthorityRegistry</td><td>车载 / 信号系统</td></tr>
 *   <tr><td>rail:sim:status:{trainId}</td><td>STRING</td><td>车载上报状态</td><td>StatusFusion</td><td>中控监控</td></tr>
 *   <tr><td>rail:sim:energy</td><td>HASH</td><td>能耗累计 (traction/regen/aux/cruising/saved)</td><td>SimulationService</td><td>能耗评估</td></tr>
 *   <tr><td>rail:sim:logs</td><td>LIST</td><td>最新仿真日志 (capped 500)</td><td>SimulationService</td><td>回放 / 分析</td></tr>
 * </table>
 *
 * <h3>降级策略</h3>
 * 所有 Redis 操作在异常时静默降级，不阻断仿真主循环的实时性。
 * {@link #isConnected()} 可查询当前 Redis 连接状态。
 */
@Service
public class RedisDataBus {

    private static final Logger log = LoggerFactory.getLogger(RedisDataBus.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // ── Key 前缀常量 ──
    private static final String KEY_LATEST_SNAPSHOT = "rail:sim:latest-snapshot";
    private static final String KEY_TRAIN_PREFIX = "rail:sim:train:";
    private static final String KEY_COMMANDS_OPEN = "rail:sim:commands:open";
    private static final String KEY_MA = "rail:sim:ma";
    private static final String KEY_STATUS_PREFIX = "rail:sim:status:";
    private static final String KEY_ENERGY = "rail:sim:energy";
    private static final String KEY_LOGS = "rail:sim:logs";

    private static final int LOG_CAP = 500;
    private static final long SNAPSHOT_TTL_SEC = 30;

    private final RedisTemplate<String, Object> redis;
    private volatile boolean connected = false;

    public RedisDataBus(RedisTemplate<String, Object> redis) {
        this.redis = redis;
        try {
            String pong = Objects.requireNonNull(redis.getConnectionFactory())
                    .getConnection().ping();
            connected = "PONG".equals(pong);
            log.info("Redis data bus connected: {}", connected);
        } catch (Exception e) {
            log.warn("Redis unavailable — running in memory-only mode: {}", e.getMessage());
        }
    }

    /** 当前 Redis 是否可用 */
    public boolean isConnected() { return connected; }

    // ═══════════════════════════════════════════════════════════════
    // SimulationSnapshot
    // ═══════════════════════════════════════════════════════════════

    /** 发布最新仿真快照到 Redis */
    public void publishSnapshot(SimulationSnapshot snapshot) {
        if (!connected) return;
        try {
            String json = mapper.writeValueAsString(snapshot);
            redis.opsForValue().set(KEY_LATEST_SNAPSHOT, json, SNAPSHOT_TTL_SEC, TimeUnit.SECONDS);
            redis.convertAndSend("rail:channel:snapshot", json);
        } catch (Exception e) {
            log.debug("Redis publish snapshot failed: {}", e.getMessage());
        }
    }

    /** 从 Redis 读取最新快照（供 HTTP 降级轮询等场景） */
    public SimulationSnapshot getLatestSnapshot() {
        if (!connected) return null;
        try {
            Object val = redis.opsForValue().get(KEY_LATEST_SNAPSHOT);
            if (val instanceof String json) {
                return mapper.readValue(json, SimulationSnapshot.class);
            }
        } catch (Exception e) {
            log.debug("Redis read snapshot failed: {}", e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // TrainState
    // ═══════════════════════════════════════════════════════════════

    /** 写入单列车状态 */
    public void putTrainState(String trainId, TrainState state) {
        if (!connected) return;
        try {
            String json = mapper.writeValueAsString(state);
            redis.opsForValue().set(trainKey(trainId), json, SNAPSHOT_TTL_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis put train state {}: {}", trainId, e.getMessage());
        }
    }

    /** 读取单列车状态 */
    public TrainState getTrainState(String trainId) {
        if (!connected) return null;
        try {
            Object val = redis.opsForValue().get(trainKey(trainId));
            if (val instanceof String json) {
                return mapper.readValue(json, TrainState.class);
            }
        } catch (Exception e) {
            log.debug("Redis get train state {}: {}", trainId, e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Commands（指令）
    // ═══════════════════════════════════════════════════════════════

    /** 写入/更新活跃指令 */
    public void putCommand(String commandId, TrainCommand cmd) {
        if (!connected) return;
        try {
            String json = mapper.writeValueAsString(cmd);
            redis.opsForHash().put(KEY_COMMANDS_OPEN, commandId, json);
        } catch (Exception e) {
            log.debug("Redis put command {}: {}", commandId, e.getMessage());
        }
    }

    /** 移除已完成的指令 */
    public void removeCommand(String commandId) {
        if (!connected) return;
        try {
            redis.opsForHash().delete(KEY_COMMANDS_OPEN, commandId);
        } catch (Exception e) {
            log.debug("Redis remove command {}: {}", commandId, e.getMessage());
        }
    }

    /** 读取所有活跃指令 */
    public Map<String, TrainCommand> getOpenCommands() {
        if (!connected) return Collections.emptyMap();
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(KEY_COMMANDS_OPEN);
            Map<String, TrainCommand> result = new LinkedHashMap<>();
            for (var e : entries.entrySet()) {
                if (e.getValue() instanceof String json) {
                    result.put((String) e.getKey(), mapper.readValue(json, TrainCommand.class));
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("Redis get commands: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // MovementAuthority（移动授权）
    // ═══════════════════════════════════════════════════════════════

    /** 全量替换移动授权表 */
    public void replaceMA(Map<String, MovingAuthority> maMap) {
        if (!connected) return;
        try {
            Map<String, String> entries = new LinkedHashMap<>();
            for (var e : maMap.entrySet()) {
                entries.put(e.getKey(), mapper.writeValueAsString(e.getValue()));
            }
            redis.delete(KEY_MA);
            if (!entries.isEmpty()) {
                redis.opsForHash().putAll(KEY_MA, entries);
            }
        } catch (Exception e) {
            log.debug("Redis replace MA: {}", e.getMessage());
        }
    }

    /** 读取单列车移动授权 */
    public MovingAuthority getMA(String trainId) {
        if (!connected) return null;
        try {
            Object val = redis.opsForHash().get(KEY_MA, trainId);
            if (val instanceof String json) {
                return mapper.readValue(json, MovingAuthority.class);
            }
        } catch (Exception e) {
            log.debug("Redis get MA {}: {}", trainId, e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // StatusReport（车载状态上报）
    // ═══════════════════════════════════════════════════════════════

    /** 写入车载状态上报 */
    public void putStatusReport(String trainId, StatusReport report) {
        if (!connected) return;
        try {
            String json = mapper.writeValueAsString(report);
            redis.opsForValue().set(KEY_STATUS_PREFIX + trainId, json, SNAPSHOT_TTL_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Redis put status {}: {}", trainId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Energy（能耗数据）
    // ═══════════════════════════════════════════════════════════════

    /** 写入能耗累计值 */
    public void putEnergyData(double tractionKwh, double regenKwh, double auxKwh,
                               double cruisingKwh, double savedKwh, double totalKwh) {
        if (!connected) return;
        try {
            Map<String, String> data = new LinkedHashMap<>();
            data.put("tractionKwh", String.valueOf(tractionKwh));
            data.put("regenKwh", String.valueOf(regenKwh));
            data.put("auxKwh", String.valueOf(auxKwh));
            data.put("cruisingKwh", String.valueOf(cruisingKwh));
            data.put("savedKwh", String.valueOf(savedKwh));
            data.put("totalKwh", String.valueOf(totalKwh));
            redis.opsForHash().putAll(KEY_ENERGY, data);
        } catch (Exception e) {
            log.debug("Redis put energy: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SimulationLog（仿真日志）
    // ═══════════════════════════════════════════════════════════════

    /** 追加仿真日志（自动 capped 保留最近 LOG_CAP 条） */
    public void appendLog(SimulationLog logEntry) {
        if (!connected) return;
        try {
            String json = mapper.writeValueAsString(logEntry);
            Long size = redis.opsForList().rightPush(KEY_LOGS, json);
            if (size != null && size > LOG_CAP) {
                redis.opsForList().trim(KEY_LOGS, size - LOG_CAP, -1);
            }
        } catch (Exception e) {
            log.debug("Redis append log: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 清理
    // ═══════════════════════════════════════════════════════════════

    /** 清空当前仿真的所有 Redis 数据（仿真重置时调用） */
    public void clearSimulationData() {
        if (!connected) return;
        try {
            Set<String> keys = redis.keys("rail:sim:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
            log.info("Redis simulation data cleared");
        } catch (Exception e) {
            log.warn("Redis clear failed: {}", e.getMessage());
        }
    }

    // ── helpers ──

    private static String trainKey(String trainId) {
        return KEY_TRAIN_PREFIX + trainId;
    }
}
