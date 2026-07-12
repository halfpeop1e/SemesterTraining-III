#!/usr/bin/env python3
"""
PLC 指示灯 / 模式灯 写数 Demo —— 让司机台上的灯亮起来

依据协议「司机驾驶模拟台PLC」7.2：上位机 → PLC（按需发送）
  - PLC 是 TCP Server：192.168.100.123:8001/8002/8003
  - 我们连上后写入帧，点亮 byte24/25 各位指示灯
  - 老师协议总表规定 26B / dataLen=2；字段表又把速度 WORD 写在 offset 26，
    两处互相矛盾。朋友的真实采集显示其软件曾向 PLC 写入 28B，但未证明 PLC
    已接受或回显该写法。因此默认严格发送文档 26B。

注意：
  每个端口同时只允许 1 个客户端。若 tcp704_live_demo / 后端 704 已占用，
  请先断开再跑本脚本。

用法：
  python scripts/plc_lamp_write_demo.py
  python scripts/plc_lamp_write_demo.py --port 8001
  python scripts/plc_lamp_write_demo.py --lamps all --blink
  python scripts/plc_lamp_write_demo.py --lamps doors_ok,ato_ready,ato_active
  python scripts/plc_lamp_write_demo.py --frame-format capture-variant-28 --speed 36
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time
from datetime import datetime
from typing import Dict, List, Optional


DEFAULT_HOST = "192.168.100.123"
DEFAULT_PORTS = [8001, 8002, 8003]

# byte24 / byte25 位定义（协议 7.2 上位机→PLC）
LAMP_BITS: Dict[str, tuple] = {
    # name: (byte_offset, bit_index, 中文)
    "high_breaker": (24, 1, "高断合指示灯"),
    "brake_release_bad": (24, 2, "制动缓解不良灯"),
    "door_open_lamp": (24, 4, "开门灯"),
    "doors_ok": (24, 5, "门关好指示灯"),
    "net_fault": (24, 6, "网络故障指示灯"),
    "ar_ready": (24, 7, "具备自动折返"),
    "ato_ready": (25, 0, "具备ATO"),
    "wash": (25, 1, "进入洗车模式"),
    "ato_active": (25, 2, "激活ATO"),
    "ar_active": (25, 3, "激活自动折返"),
}


def ts() -> str:
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def build_frame(
    lamps_on: List[str],
    speed_kmh: int = 0,
    frame_format: str = "documented-26",
) -> bytes:
    """
    documented-26: 老师协议总表的 26B / dataLen=2。
    capture-variant-28: 朋友系统使用过的 28B 变体，仅限老师允许的现场对照。
    """
    if frame_format not in {"documented-26", "capture-variant-28"}:
        raise ValueError(f"未知帧格式: {frame_format}")
    include_speed = frame_format == "capture-variant-28"
    size = 28 if include_speed else 26
    buf = bytearray(size)
    # 协议 7.2：Identify 示例写作 55 AA 55 AA
    buf[0:4] = bytes([0x55, 0xAA, 0x55, 0xAA])
    struct.pack_into("<H", buf, 4, size)          # totalLen
    struct.pack_into("<H", buf, 6, 4 if include_speed else 2)  # dataLen
    now = datetime.now()
    struct.pack_into("<H", buf, 8, now.year)
    struct.pack_into("<H", buf, 10, now.month)
    struct.pack_into("<H", buf, 12, now.day)
    struct.pack_into("<H", buf, 14, now.hour)
    struct.pack_into("<H", buf, 16, now.minute)
    struct.pack_into("<H", buf, 18, now.second)
    struct.pack_into("<H", buf, 20, 0)  # verify type
    struct.pack_into("<H", buf, 22, 0)  # verify code

    b24 = 0
    b25 = 0
    for name in lamps_on:
        if name not in LAMP_BITS:
            continue
        off, bit, _ = LAMP_BITS[name]
        if off == 24:
            b24 |= 1 << bit
        else:
            b25 |= 1 << bit
    buf[24] = b24
    buf[25] = b25

    if include_speed:
        struct.pack_into("<H", buf, 26, speed_kmh & 0xFFFF)

    return bytes(buf)


def try_connect(host: str, port: int) -> Optional[socket.socket]:
    print(f"[{ts()}] connect {host}:{port} ...", flush=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(3.0)
    try:
        sock.connect((host, port))
    except OSError as exc:
        sock.close()
        print(f"[{ts()}] FAIL -> {exc}", flush=True)
        return None
    print(f"[{ts()}] OK local={sock.getsockname()}", flush=True)
    return sock


def main() -> int:
    p = argparse.ArgumentParser(description="点亮 PLC 司机台指示灯")
    p.add_argument("--host", default=DEFAULT_HOST)
    p.add_argument("--port", type=int, default=None, help="只连一个端口；默认依次试 8001/8002/8003")
    p.add_argument(
        "--lamps",
        default="doors_ok,ato_ready,ato_active,high_breaker",
        help="逗号分隔灯名，或 all。可选: " + ",".join(LAMP_BITS.keys()),
    )
    p.add_argument("--blink", action="store_true", help="闪烁（更易肉眼确认）")
    p.add_argument(
        "--frame-format",
        choices=["documented-26", "capture-variant-28"],
        default="documented-26",
        help="默认按老师协议发送 26B；28B 仅为朋友系统使用过的未验收候选格式",
    )
    p.add_argument("--speed", type=int, default=0, help="仅 28B 候选格式写入的速度 WORD")
    p.add_argument("--duration", type=float, default=20.0, help="秒")
    p.add_argument("--interval", type=float, default=0.5)
    args = p.parse_args()

    if args.lamps.strip().lower() == "all":
        lamp_list = list(LAMP_BITS.keys())
    else:
        lamp_list = [x.strip() for x in args.lamps.split(",") if x.strip()]
        unknown = [x for x in lamp_list if x not in LAMP_BITS]
        if unknown:
            print(f"未知灯名: {unknown}", flush=True)
            print("可选:", ", ".join(LAMP_BITS.keys()), flush=True)
            return 2

    print("=" * 64)
    print(" PLC 指示灯写数")
    format_note = "候选对照，未验收" if args.frame_format == "capture-variant-28" else "老师协议默认"
    print(f" 帧格式: {args.frame_format}（{format_note}）")
    print(" 将点亮:")
    for name in lamp_list:
        print(f"   - {name}: {LAMP_BITS[name][2]}")
    print("=" * 64)

    ports = [args.port] if args.port else list(DEFAULT_PORTS)
    sock = None
    used_port = None
    for port in ports:
        sock = try_connect(args.host, port)
        if sock is not None:
            used_port = port
            break
    if sock is None:
        print(
            f"[{ts()}] 无法连接 PLC。请确认：\n"
            f"  1) ping {args.host}\n"
            f"  2) 其它程序未占用 8001/8002/8003（先停 tcp704_live_demo / 后端 Connect）",
            flush=True,
        )
        return 1

    print(f"[{ts()}] 使用端口 {used_port}，按需发送（协议：上位机输出仅需发送）", flush=True)
    print(f"[{ts()}] 请盯着司机台实体指示灯；Ctrl+C 停止", flush=True)

    t0 = time.time()
    on = True
    n = 0
    try:
        while time.time() - t0 < args.duration:
            active = lamp_list if (on or not args.blink) else []
            frame = build_frame(active, speed_kmh=args.speed, frame_format=args.frame_format)
            sock.sendall(frame)
            if n % 2 == 0:
                bits = f"b24={frame[24]:08b} b25={frame[25]:08b}"
                print(f"[{ts()}] #{n} len={len(frame)} {bits} lamps={active or 'OFF'}", flush=True)
            n += 1
            if args.blink:
                on = not on
            time.sleep(args.interval)
    except KeyboardInterrupt:
        print(f"\n[{ts()}] 停止", flush=True)
    finally:
        # 熄灭再退出
        try:
            sock.sendall(build_frame([], frame_format=args.frame_format))
        except OSError:
            pass
        sock.close()

    print(f"[{ts()}] 结束，已尝试熄灭指示灯", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
