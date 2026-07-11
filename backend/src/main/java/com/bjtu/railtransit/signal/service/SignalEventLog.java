package com.bjtu.railtransit.signal.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * G4: 内存环形缓冲事件日志（线程安全）。
 * 容量 200 条，新在前。支持 5s 节流（同一 trainId+eventKey 不重复刷屏）。
 */
@Service
public class SignalEventLog {

    private static final int CAPACITY = 200;
    private static final long THROTTLE_MS = 5_000;

    private final List<Map<String, Object>> buffer = new ArrayList<>(CAPACITY);
    private final AtomicInteger idSeq = new AtomicInteger(0);

    // 节流：key = "trainId|eventKey" → last timestamp ms
    private final Map<String, Long> throttleMap = new ConcurrentHashMap<>();

    public synchronized void add(String level, String category, String message, String sourceId) {
        int id = idSeq.incrementAndGet();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(id));
        item.put("timestamp", System.currentTimeMillis());
        item.put("level", level);
        item.put("category", category);
        item.put("message", message);
        if (sourceId != null) {
            item.put("sourceId", sourceId);
        }
        buffer.add(0, item);
        if (buffer.size() > CAPACITY) {
            buffer.remove(buffer.size() - 1);
        }
    }

    /**
     * 带节流的写入：同一 throttleKey（如 trainId+event）在 THROTTLE_MS 内不重复。
     */
    public void addThrottled(String level, String category, String message, String sourceId, String throttleKey) {
        if (throttleKey != null) {
            long now = System.currentTimeMillis();
            Long last = throttleMap.get(throttleKey);
            if (last != null && (now - last) < THROTTLE_MS) {
                return; // 节流，跳过
            }
            throttleMap.put(throttleKey, now);
        }
        add(level, category, message, sourceId);
    }

    /**
     * 返回最近 limit 条事件（新在前）。limit<=0 或 >CAPACITY 返回全部。
     */
    public synchronized List<Map<String, Object>> recent(int limit) {
        if (limit <= 0 || limit >= buffer.size()) {
            return new ArrayList<>(buffer);
        }
        return new ArrayList<>(buffer.subList(0, limit));
    }

    public synchronized int size() {
        return buffer.size();
    }

    public synchronized void clear() {
        buffer.clear();
        throttleMap.clear();
    }
}
