#!/usr/bin/env python3
"""
PLC 启动流程模拟器 —— 对齐信号仿真系统操作手册（2026年6月版）

真实司机台启动流程（参考手册 §2.3 "1车启动操作"）：
  步骤 1（§2.3.1）司机台开关钥匙：方向手柄归零、加速手柄归零 → 钥匙ON→OFF→ON
  步骤 2（§2.3.2）速度保持：缓慢推牵引杆，车速保持 10km/h 内
  步骤 3（§2.3.3）设置交路：调度员界面设置交路（本脚本通过仿真注册完成）
  步骤 4（§2.3.4）ATO 操作：牵引手柄归零 → ATO 灯闪烁 → 长按两个 ATO 按钮 2 秒 → 发车

折返流程（参考手册 §2.4）：
  按自动折返 → 方向归零 → 关钥匙 → 开钥匙 → 方向向前 → 长按 ATO 2 秒

用法：
  # 基础启动
  python scripts/plc_startup_simulator.py

  # 指定列车和牵引进站
  python scripts/plc_startup_simulator.py --train-id TR001

  # 带折返的完整循环
  python scripts/plc_startup_simulator.py --turnback

  # 自定义牵引级位和观察时长
  python scripts/plc_startup_simulator.py --traction-level 30 --watch 30
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from typing import Any, Dict, Optional

API = "http://localhost:8080/api"

# ── PLC帧数据 MD 日志 ──
_MD_LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plc-frame-log.md")


def _init_md_log():
    """首次写入时创建 MD 文件表头。"""
    if not os.path.exists(_MD_LOG_FILE):
        with open(_MD_LOG_FILE, "w", encoding="utf-8") as f:
            f.write("# PLC 帧数据日志\n\n")
            f.write("| 时间 | 速度(km/h) | raw WORD | byte24 | byte25 | hex 解析关键字段 | hex 原始帧 |\n")
            f.write("|------|-----------|----------|--------|--------|-----------------|-----------|\n")


def _log_frame_to_md(timestamp: str, speed_kmh: float, speed_raw_word: Any,
                     byte24_info: str, byte25_info: str,
                     key_fields: str, raw_hex: str):
    """将一帧 PLC 数据追加写入 MD 日志文件。"""
    _init_md_log()
    raw_word_str = f"0x{int(speed_raw_word):04X}" if speed_raw_word is not None else "-"
    hex_display = raw_hex[:60] + ("..." if len(raw_hex) > 60 else "") if raw_hex else "-"
    with open(_MD_LOG_FILE, "a", encoding="utf-8") as f:
        f.write(f"| {timestamp} | {speed_kmh:.1f} | {raw_word_str} | "
                f"{byte24_info} | {byte25_info} | {key_fields} | "
                f"`{hex_display}` |\n")


def _extract_frame_info(status: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """从 Protocol704Status 提取 PLC 帧关键信息。"""
    parsed = status.get("lastParsedFrame") or {}
    fields = parsed.get("fields") or {}
    rt = status.get("realtimeVehicleState") or {}
    raw_hex = status.get("lastRawHex") or ""

    if not fields and not raw_hex:
        return None

    # 速度：优先用 realtimeVehicleState，回退解析字段 raw speed word
    v_ms = rt.get("velocityMs")
    if v_ms is not None:
        speed_kmh = float(v_ms) * 3.6
    else:
        speed_kmh = 0.0
    speed_raw = fields.get("plc_speed_raw_word")

    # byte24 位解析
    b24_parts = []
    if fields.get("lights_high_breaker_on"):
        b24_parts.append("高断合")
    if fields.get("doors_closed_ok"):
        b24_parts.append("门关好")
    if fields.get("network_fault"):
        b24_parts.append("网络故障")
    if fields.get("brake_release_bad"):
        b24_parts.append("制动缓解不良")
    if fields.get("ar_mode_available"):
        b24_parts.append("AR可用")
    b24_str = ",".join(b24_parts) if b24_parts else "-"

    # byte25 位解析
    b25_parts = []
    if fields.get("ato_mode_available"):
        b25_parts.append("ATO可用")
    if fields.get("ato_mode_active"):
        b25_parts.append("ATO激活")
    if fields.get("ar_mode_active"):
        b25_parts.append("AR激活")
    if fields.get("wash_mode_entered"):
        b25_parts.append("洗车模式")
    b25_str = ",".join(b25_parts) if b25_parts else "-"

    # 关键字段摘要
    key_parts = []
    if fields.get("key_switch_on"):
        key_parts.append("钥匙ON")
    if fields.get("direction_desc"):
        key_parts.append(f"方向={fields['direction_desc']}")
    if fields.get("mapped_command"):
        key_parts.append(f"cmd={fields['mapped_command']}")
    if fields.get("control_mode_request"):
        key_parts.append(f"模式={fields['control_mode_request']}")
    if fields.get("eb_button_locked"):
        key_parts.append("EB锁定")
    key_str = "<br>".join(key_parts) if key_parts else "-"

    return {
        "speed_kmh": speed_kmh,
        "speed_raw_word": speed_raw,
        "byte24_info": b24_str,
        "byte25_info": b25_str,
        "key_fields": key_str,
        "raw_hex": raw_hex,
    }

# ── 协议编码常量（对齐接口协议汇总 20260630） ──
# PLC → 上位机 46B 帧关键偏移：
#   offset24 bit0: 高断合灯     offset28 bit0: 紧急制动(1=锁定)
#   offset24 bit5: 门关好灯     offset34 bit2: 模式升级确认(RESUME_ATO)
#   offset34 bit4: 确认按钮     offset34 bit3: 模式降级确认(SET_MANUAL)
#   offset34 bit5: 自动折返     offset34 bit7: ATO启动
#   offset35 bit1: 钥匙开关     offset36 word: 方向手柄(0=0位,1=向前,2=向后)
#   offset38 word: 主手柄(0=0位,1=牵引,2=制动,4=快制)
#   offset40 word: 牵引极位     offset42 word: 制动极位


def ts() -> str:
    return datetime.now().strftime("%H:%M:%S")


def http_json(method: str, url: str, body: Optional[dict] = None,
              timeout: float = 30.0) -> Any:
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"raw": raw}


def _try_log_frame(status: dict, label: str = ""):
    """提取帧数据并写入 MD 日志（静默失败，不影响主流程）。"""
    try:
        info = _extract_frame_info(status or {})
        if info is None:
            return
        t = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        _log_frame_to_md(t, info["speed_kmh"], info["speed_raw_word"],
                          info["byte24_info"], info["byte25_info"],
                          info["key_fields"], info["raw_hex"])
    except Exception:
        pass


def inject(train_id: str, ftype: str, label: str = "") -> Dict[str, Any]:
    """向后端注入 test-frame，模拟 PLC 手柄/按钮操作。"""
    url = f"{API}/vehicle/protocol704/test-frame?trainId={train_id}&type={ftype}"
    tag = label or ftype
    print(f"  [{ts()}] {tag:30s} ", end="", flush=True)
    st = http_json("POST", url)
    life = (st or {}).get("lastCommandLifecycle") or {}
    mapped = (st or {}).get("lastMappedCommand") or {}
    rt = (st or {}).get("realtimeVehicleState") or {}
    status = life.get("status", "?")
    reject = life.get("rejectionReason", "")
    detail = f"status={status}"
    if reject:
        detail += f" reject={reject}"
    print(
        f"cmd={mapped.get('command', '?')}  "
        f"{detail}  "
        f"v={rt.get('velocityMs', '?')} m/s  "
        f"pos={rt.get('positionM', '?')} m  "
        f"mode={rt.get('mode', '?')}",
        flush=True,
    )
    # 记录帧数据到 MD 日志
    _try_log_frame(st, tag)
    return st or {}


def get_status(train_id: str) -> Dict[str, Any]:
    """读取 704 实时状态。"""
    url = f"{API}/vehicle/protocol704/status?trainId={train_id}"
    return http_json("GET", url) or {}


def section(title: str):
    print(f"\n{'─' * 56}")
    print(f"[{ts()}] {title}")
    print(f"{'─' * 56}")


def step_handle_zero(train_id: str):
    """手柄归零：方向手柄 0 位 + 主手柄 0 位 + 牵引/制动 0 级。"""
    inject(train_id, "coast", "  → 方向手柄归零 + 主手柄归零")
    time.sleep(0.2)


def step_key_on(train_id: str):
    """钥匙接通：PLC 上电，钥匙开关 ON（默认 test-frame 已设置 offset35=0x02）。"""
    inject(train_id, "coast", "  → 钥匙 ON（开关已接通）")
    time.sleep(0.3)


def step_traction_slow(train_id: str, level: int = 30):
    """缓慢推牵引杆：低速牵引进站或起步。"""
    inject(train_id, "traction", f"  → 主手柄→牵引  级位={level}%")
    time.sleep(0.5)


def step_handle_to_zero(train_id: str):
    """牵引手柄归零：为 ATO 启动做准备。"""
    inject(train_id, "coast", "  → 牵引手柄归零（ATO 就绪）")
    time.sleep(0.5)


def step_ato_hold(train_id: str, hold_seconds: float = 2.0) -> bool:
    """长按两个 ATO 启动按钮（对齐手册 §2.3.4：同时按下两个按钮 2 秒）。"""
    deadline = time.time() + hold_seconds
    count = 0
    while time.time() < deadline:
        inject(train_id, "ato_start", f"  → ATO 启动按钮 按住中 ({count + 1})")
        count += 1
        time.sleep(0.5)

    st = get_status(train_id)
    life = (st or {}).get("lastCommandLifecycle") or {}
    rt = (st or {}).get("realtimeVehicleState") or {}
    return life.get("status") == "EXECUTED"


def step_turnback(train_id: str):
    """折返流程（对齐手册 §2.4）。
    1) 按自动折返  2) 方向归零  3) 关钥匙  4) 开钥匙  5) 方向向前  6) ATO 2s
    """
    print()
    section("=== 折返流程（手册 §2.4）===")

    # 2.4.1 按一下"自动折返"按钮
    inject(train_id, "resume_ato", "  → 按自动折返按钮")

    # 2.4.2 方向手柄归零
    step_handle_zero(train_id)

    # 2.4.3 关钥匙
    inject(train_id, "coast", "  → 关钥匙（钥匙 OFF）")

    # 2.4.4 开钥匙
    step_key_on(train_id)

    # 2.4.5 方向手柄向前
    inject(train_id, "traction", "  → 方向手柄 → 向前")

    # 2.4.6 ATO 启动
    time.sleep(0.5)
    step_handle_to_zero(train_id)
    return step_ato_hold(train_id, 2.0)


def watch_train(train_id: str, watch_seconds: float, started: bool) -> bool:
    """观察列车运行状态。返回是否观察到移动。"""
    t0 = time.time()
    last_v = None
    moved = started  # 如果 ATO 已 EXECUTED 就算启动成功
    station_arrivals = 0

    while time.time() - t0 < watch_seconds:
        try:
            st = get_status(train_id)
        except Exception as e:
            print(f"[{ts()}] 状态查询失败: {e}", flush=True)
            time.sleep(1)
            continue
        _try_log_frame(st, "watch")  # 记录帧数据到 MD
        rt = (st or {}).get("realtimeVehicleState") or {}
        life = (st or {}).get("lastCommandLifecycle") or {}
        mapped = (st or {}).get("lastMappedCommand") or {}
        connected = (st or {}).get("connected")
        v = rt.get("velocityMs")
        pos = rt.get("positionM")
        mode = rt.get("mode")
        depart = rt.get("departureState")
        phase = rt.get("phase", "")

        if v is not None and last_v is not None and abs(float(v) - float(last_v)) > 0.05:
            moved = True

        # 检测到站（速度归零 + 之前有速度）
        if v is not None and float(v) < 0.01 and last_v is not None and float(last_v) > 0.1:
            station_arrivals += 1
            print(f"  [{ts()}] ⚑ 到站检测 #{station_arrivals} — "
                  f"ATO 灯应闪烁，需再次长按启动", flush=True)
            # 模拟到站后 ATO 重触发（牵引归零 → ATO 2s）
            print(f"  [{ts()}]    自动重触发 ATO ...", flush=True)
            step_handle_to_zero(train_id)
            step_ato_hold(train_id, 2.0)

        last_v = v

        print(
            f"[{ts()}] conn={connected}  "
            f"mode={mode}  depart={depart}  phase={phase}  "
            f"cmd={mapped.get('command', '?')}  "
            f"v={v} m/s  pos={pos} m  "
            f"life={life.get('status', '?')}/{life.get('rejectionReason', '-')}",
            flush=True,
        )
        time.sleep(1.5)

    return moved


def main() -> int:
    p = argparse.ArgumentParser(
        description="PLC 启动流程模拟器 — 对齐信号仿真系统操作手册 §2.3/§2.4",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例：
  %(prog)s                                          # 默认启车
  %(prog)s --train-id TR001 --traction-level 40     # 自定义牵引
  %(prog)s --turnback --watch 60                    # 含折返的完整循环
  %(prog)s --skip-ato                               # 仅手动牵引不发车
        """,
    )
    p.add_argument("--train-id", default="LAB1",
                   help="列车 ID（默认 LAB1）")
    p.add_argument("--from-station", type=int, default=1,
                   help="发车站（默认 1）")
    p.add_argument("--to-station", type=int, default=4,
                   help="到达站（默认 4）")
    p.add_argument("--traction-level", type=int, default=30,
                   help="牵引百分比 0-100（默认 30，对齐手册 §2.3.2 低速 10km/h 内）")
    p.add_argument("--ato-hold-seconds", type=float, default=2.0,
                   help="长按 ATO 按钮秒数（默认 2.0，对齐手册 §2.3.4）")
    p.add_argument("--watch", type=float, default=30.0,
                   help="发车后观察秒数")
    p.add_argument("--turnback", action="store_true",
                   help="执行完整折返流程（手册 §2.4）")
    p.add_argument("--skip-ato", action="store_true",
                   help="仅手动牵引，不触发 ATO 发车")
    p.add_argument("--dwell-seconds", type=float, default=5.0,
                   help="站停时间秒数（默认 5）")
    args = p.parse_args()
    tid = args.train_id

    print("=" * 64)
    print(f"[{ts()}] PLC 启动流程模拟器  trainId={tid}")
    print(f"     对齐：信号仿真系统操作手册（2026年6月版）§2.3 / §2.4")
    print(f"     配置：from={args.from_station} to={args.to_station} "
          f"traction={args.traction_level}% ato={args.ato_hold_seconds}s "
          f"turnback={'ON' if args.turnback else 'OFF'}")
    print("=" * 64)

    # ── 0. health check ──
    try:
        health = http_json("GET", f"{API}/health")
        print(f"[{ts()}] 后端连接 OK: {health}", flush=True)
    except Exception as e:
        print(f"[{ts()}] 错误: 后端未启动或不可达 ({e})", flush=True)
        return 1

    # ── 0.5 启动仿真时钟（authoritativeTick 需要 simulationRunning=true） ──
    #  同时注册列车到 SimulationService.trains，使信号系统 MA 计算能够感知该车
    try:
        start_res = http_json("POST", f"{API}/simulations/start")
        print(f"[{ts()}] 仿真时钟已启动", flush=True)
        # 也通过 dispatch 接口添加列车（进 SimulationService.trains）
        add_body = {
            "trainId": tid,
            "headLinkId": 1,
            "direction": "UP",
            "stationId": args.from_station,
            "routePattern": "FULL",
        }
        http_json("POST", f"{API}/dispatch/trains", add_body)
        print(f"[{ts()}] 列车 {tid} 已注册到仿真", flush=True)
    except Exception as e:
        print(f"[{ts()}] 仿真启动失败: {e}", flush=True)

    # ── 1. 断开旧连接 ──
    try:
        http_json("POST", f"{API}/vehicle/protocol704/disconnect?trainId={tid}")
    except Exception:
        pass

    # ── 2. 注册仿真 ──
    run_body = {
        "trainId": tid,
        "fromStationId": args.from_station,
        "toStationId": args.to_station,
        "dwellTimeSeconds": args.dwell_seconds,
        "hardwareControlEnabled": True,
        "labAutoDepartureEnabled": True,
    }
    print(f"[{ts()}] 注册仿真 {run_body} ...", flush=True)
    try:
        run_res = http_json("POST", f"{API}/vehicle/simulation/run", run_body, timeout=120)
    except Exception as e:
        print(f"[{ts()}] 仿真注册失败: {e}", flush=True)
        return 1
    if not run_res or not run_res.get("success"):
        print(f"[{ts()}] 仿真注册返回失败: {run_res}", flush=True)
        return 1
    summary = (run_res.get("data") or {}).get("summary") or {}
    states = (run_res.get("data") or {}).get("states") or []
    print(f"[{ts()}] 仿真已注册: "
          f"stations={summary.get('fromStationName')}→{summary.get('toStationName')}  "
          f"states={len(states)}  departure={summary.get('departureState')}",
          flush=True)

    # ══════════════════════════════════════════════════════════════
    # 启动流程（对齐手册 §2.3）
    # ══════════════════════════════════════════════════════════════

    section("步骤 1/4：司机台开关钥匙（手册 §2.3.1）")
    # 2.3.1: 方向手柄归零 + 加速手柄归零 → 钥匙 ON→OFF→ON
    step_handle_zero(tid)
    step_key_on(tid)
    print(f"  [{ts()}]     钥匙已接通，方向手柄=向前(默认)，主手柄=0位")

    if not args.skip_ato:
        section("步骤 2/4：速度保持（手册 §2.3.2）")
        # 2.3.2: 缓慢推牵引杆，车速保持 10km/h 内
        step_traction_slow(tid, args.traction_level)
        time.sleep(1.5)  # 短暂牵引后归零
        step_handle_to_zero(tid)

    section("步骤 3/4：设置交路（手册 §2.3.3）")
    print(f"  [{ts()}]     交路已通过仿真注册设置（{summary.get('fromStationName')}⇆{summary.get('toStationName')}）",
          flush=True)

    if args.skip_ato:
        print(f"\n[{ts()}] --skip-ato 模式：仅手动牵引，不触发 ATO 发车。", flush=True)
        watch_train(tid, args.watch, False)
        return 0

    section("步骤 4/4：ATO 操作（手册 §2.3.4）")
    ato_ok = step_ato_hold(tid, args.ato_hold_seconds)

    rt = (get_status(tid) or {}).get("realtimeVehicleState") or {}
    if not ato_ok:
        life = (get_status(tid) or {}).get("lastCommandLifecycle") or {}
        print(f"\n[{ts()}] ATO 启动失败: status={life.get('status')} "
              f"reject={life.get('rejectionReason')}", flush=True)
        print(f"[{ts()}] 可能原因:", flush=True)
        print(f"  - 钥匙未接通（需在 PLC 实体台拧钥匙）", flush=True)
        print(f"  - 车门未关好（check doorsClosed）", flush=True)
        print(f"  - 未获得发车授权（信号未开放 / 进路未建立）", flush=True)
        print(f"  - laboratoryAutoDeparture 未启用", flush=True)
        return 2

    print(f"\n[{ts()}] ✓ ATO 已激活，列车发车！", flush=True)

    # ══════════════════════════════════════════════════════════════
    # 运行观察
    # ══════════════════════════════════════════════════════════════
    print(f"[{ts()}] 观察运行 {args.watch}s（含自动到站重触发）...", flush=True)
    moved = watch_train(tid, args.watch, True)

    # ══════════════════════════════════════════════════════════════
    # 折返流程（可选）
    # ══════════════════════════════════════════════════════════════
    if args.turnback:
        tb_ok = step_turnback(tid)
        if not tb_ok:
            print(f"\n[{ts()}] 折返 ATO 启动失败", flush=True)
            return 3
        print(f"\n[{ts()}] ✓ 折返完成，列车反向运行！", flush=True)
        print(f"[{ts()}] 观察折返后运行 {args.watch}s ...", flush=True)
        watch_train(tid, args.watch, True)

    # ── 结果 ──
    print()
    print("=" * 64)
    if moved:
        print(f"[{ts()}] ✓ 成功：列车已发车并观察到速度变化。")
    else:
        print(f"[{ts()}] ⚠ 警告：ATO 已 EXECUTED 但未观察到明显速度变化。\n"
              f"  - 检查调度仿真时钟是否在运行\n"
              f"  - 检查后端 authoritativeTick 是否正常")
    print("=" * 64)
    return 0 if moved else 2


if __name__ == "__main__":
    sys.exit(main())
