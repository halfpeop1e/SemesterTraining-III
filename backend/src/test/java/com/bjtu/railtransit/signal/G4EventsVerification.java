package com.bjtu.railtransit.signal;

import com.bjtu.railtransit.signal.service.SignalEventLog;

import java.util.List;
import java.util.Map;

/**
 * G4 验证：GET /api/signal/events 真实事件流。
 *
 * 验证点：
 * 1. 初始 events 为空
 * 2. add 后 events 非空，字段齐全(id,timestamp,level,category,message,sourceId)
 * 3. 新在前（最新在最前）
 * 4. 环形上限不炸（超过 CAPACITY 后丢弃最旧的）
 * 5. 节流：同一 throttleKey 5s 内不重复
 * 6. recent(limit) 返回正确的子集
 * 7. level 合法(INFO/WARN/ERROR)，category 合法(MA/SWITCH/SIGNAL/TSR/TRAIN/SYSTEM)
 */
public class G4EventsVerification {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) {
        SignalEventLog log = new SignalEventLog();

        // 断言 1: 初始为空
        check(log.size() == 0, "初始 events 为空");
        check(log.recent(0).isEmpty(), "recent(0) 返回空列表");

        // 断言 2: add 后非空，字段齐全
        log.add("INFO", "SWITCH", "道岔 1 → NORMAL", "1");
        List<Map<String, Object>> events = log.recent(0);
        check(events.size() == 1, "add 后 events.size=1");
        Map<String, Object> e = events.get(0);
        check(e.containsKey("id"), "字段 id 存在");
        check(e.containsKey("timestamp"), "字段 timestamp 存在");
        check(e.containsKey("level"), "字段 level 存在");
        check(e.containsKey("category"), "字段 category 存在");
        check(e.containsKey("message"), "字段 message 存在");
        check(e.containsKey("sourceId"), "字段 sourceId 存在");
        check("INFO".equals(e.get("level")), "level=INFO");
        check("SWITCH".equals(e.get("category")), "category=SWITCH");
        check("道岔 1 → NORMAL".equals(e.get("message")), "message 正确");
        check("1".equals(e.get("sourceId")), "sourceId=1");

        // 断言 3: 新在前
        log.add("WARN", "SIGNAL", "信号机 5 → RED", "5");
        events = log.recent(0);
        check(events.size() == 2, "add 2 条后 size=2");
        check("信号机 5 → RED".equals(events.get(0).get("message")), "新在前（最新在 index 0）");
        check("道岔 1 → NORMAL".equals(events.get(1).get("message")), "旧的在 index 1");

        // 断言 4: 环形上限不炸
        log.clear();
        for (int i = 0; i < 250; i++) {
            log.add("INFO", "SYSTEM", "event-" + i, null);
        }
        check(log.size() == 200, "超过 CAPACITY(200) 后 size=200（不炸），实际=" + log.size());
        events = log.recent(0);
        check(events.get(0).get("message").equals("event-249"), "环形最新 event-249 在最前");
        check(events.get(199).get("message").equals("event-50"), "环形最旧 event-50 在最后");

        // 断言 5: 节流
        log.clear();
        log.addThrottled("WARN", "MA", "列车 T1 MA 降级", "T1", "T1|DEGRADED");
        check(log.size() == 1, "首次 throttled add 成功 size=1");
        log.addThrottled("WARN", "MA", "列车 T1 MA 降级（重复）", "T1", "T1|DEGRADED");
        check(log.size() == 1, "5s 内同 throttleKey 不重复 size 仍=1");
        // 不同 throttleKey 不受影响
        log.addThrottled("WARN", "MA", "列车 T2 MA 降级", "T2", "T2|DEGRADED");
        check(log.size() == 2, "不同 throttleKey 不节流 size=2");

        // 断言 6: recent(limit)
        log.clear();
        for (int i = 0; i < 10; i++) {
            log.add("INFO", "SYSTEM", "ev-" + i, null);
        }
        check(log.recent(5).size() == 5, "recent(5) 返回 5 条");
        check(log.recent(0).size() == 10, "recent(0) 返回全部 10 条");
        check(log.recent(100).size() == 10, "recent(100) 返回全部 10 条（不超限）");

        // 断言 7: level / category 合法值
        log.clear();
        log.add("ERROR", "TSR", "TSR 创建失败", null);
        log.add("INFO", "TRAIN", "列车 T1 发车", "T1");
        log.add("WARN", "MA", "MA 降级", "T1");
        events = log.recent(0);
        for (Map<String, Object> ev : events) {
            String level = (String) ev.get("level");
            check(level.equals("INFO") || level.equals("WARN") || level.equals("ERROR"),
                    "level 合法: " + level);
            String cat = (String) ev.get("category");
            check(cat.matches("MA|SWITCH|SIGNAL|TSR|TRAIN|SYSTEM"),
                    "category 合法: " + cat);
        }

        // 断言 8: sourceId 可选（null 时不输出）
        log.clear();
        log.add("INFO", "SYSTEM", "无 sourceId 事件", null);
        events = log.recent(0);
        check(!events.get(0).containsKey("sourceId"), "sourceId=null 时不输出该字段");

        System.out.println("\n===== G4EventsVerification =====");
        System.out.println("PASS: " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.out.println("FAIL: " + fail);
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    static void check(boolean ok, String msg) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + msg);
        } else {
            fail++;
            System.out.println("  [FAIL] " + msg);
        }
    }
}
