#!/usr/bin/env python3
"""
司机台信号屏 / 网络屏 写数 Demo —— 用来看屏上会不会动

协议（2026-06）：
  信号屏 TCP Server：文档 IP 192.168.100.121  Port 9999，帧长 66B
  网络屏 TCP Server：文档 IP 192.168.100.122  Port 8888，帧长 572B

说明：
  - 屏是 Server，我们是 Client，连上后周期发数据
  - 若文档 IP/端口与台架不一致，用参数覆盖
  - 你这边测过 .121:9999 Ping 通但 TCP 失败 → 先跑本脚本的 --probe，
    或确认屏软件已启动

用法：
  python scripts/driver_screen_write_demo.py --probe
  python scripts/driver_screen_write_demo.py --target both
  # 台架实测（2026-07-12）：.121:8888=网络屏，.122:9999=信号屏（与文档 IP 对调）
  python scripts/driver_screen_write_demo.py
"""

from __future__ import annotations

import argparse
import math
import socket
import struct
import sys
import time
from datetime import datetime
from typing import List, Optional, Tuple


CONNECT_TIMEOUT_S = 3.0
SEND_INTERVAL_S = 0.2  # 200ms，方便肉眼看变化


def ts() -> str:
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def put_u16(buf: bytearray, off: int, value: int) -> None:
    struct.pack_into("<H", buf, off, value & 0xFFFF)


def put_u32(buf: bytearray, off: int, value: int) -> None:
    struct.pack_into("<I", buf, off, value & 0xFFFFFFFF)


def put_u64(buf: bytearray, off: int, value: int) -> None:
    struct.pack_into("<Q", buf, off, value & 0xFFFFFFFFFFFFFFFF)


def put_f32(buf: bytearray, off: int, value: float) -> None:
    struct.pack_into("<f", buf, off, float(value))


def fill_common_header(
    buf: bytearray,
    total_len: int,
    data_len: int,
    msg_id: int = 1,
    magic: bytes = bytes([0x55, 0xAA, 0x55, 0xAA]),
) -> None:
    """与 PLC/屏协议共用的帧头风格。"""
    buf[0:4] = magic[:4]
    put_u16(buf, 4, total_len)
    put_u16(buf, 6, data_len)
    put_u64(buf, 8, int(time.time() * 1000) & 0xFFFFFFFFFFFFFFFF)
    put_u16(buf, 16, 0)  # verify type
    put_u16(buf, 18, 0)  # verify code
    put_u16(buf, 20, 1)  # protocol id
    put_u16(buf, 22, msg_id)


def fill_datetime_words(buf: bytearray, off: int = 24) -> None:
    now = datetime.now()
    put_u16(buf, off + 0, now.year)
    put_u16(buf, off + 2, now.month)
    put_u16(buf, off + 4, now.day)
    put_u16(buf, off + 6, now.hour)
    put_u16(buf, off + 8, now.minute)
    put_u16(buf, off + 10, now.second)


def put_f32_endian(buf: bytearray, off: int, value: float, big_endian: bool = False) -> None:
    fmt = ">f" if big_endian else "<f"
    struct.pack_into(fmt, buf, off, float(value))


def build_signal_frame(
    speed: float,
    accel: float,
    speed_limit: float,
    mode: int,
    pull: int,
    brake: int,
    eb: int,
    curr: int,
    nxt: int,
    end: int,
    run_dir: int,
    train_no: int,
    next_dist_m: float,
    sig_state: int,
    magic: bytes = bytes([0x55, 0xAA, 0x55, 0xAA]),
    float_be: bool = False,
) -> bytes:
    """信号屏 66B。doc 布局：Speed@42, Accel@46。"""
    buf = bytearray(66)
    fill_common_header(buf, 66, 66 - 24, msg_id=1, magic=magic)
    fill_datetime_words(buf, 24)
    buf[36] = curr & 0xFF
    buf[37] = nxt & 0xFF
    buf[38] = end & 0xFF
    buf[39] = 1
    buf[40] = 0
    buf[41] = 0
    put_f32_endian(buf, 42, speed, float_be)
    put_f32_endian(buf, 46, accel, float_be)
    put_u16(buf, 50, 0)
    put_u16(buf, 52, int(round(speed_limit)))
    buf[54] = mode & 0xFF
    buf[55] = pull & 0xFF
    buf[56] = brake & 0xFF
    buf[57] = eb & 0xFF
    buf[58] = 0
    buf[59] = sig_state & 0xFF
    put_u16(buf, 60, train_no)
    put_f32_endian(buf, 62, next_dist_m, float_be)
    return bytes(buf)


def build_signal_frame_spaced(
    speed: float,
    accel: float,
    speed_limit: float,
    mode: int,
    pull: int,
    brake: int,
    eb: int,
    curr: int,
    nxt: int,
    end: int,
    run_dir: int,
    train_no: int,
    next_dist_m: float,
    sig_state: int,
    magic: bytes = bytes([0x55, 0xAA, 0x55, 0xAA]),
    float_be: bool = False,
) -> bytes:
    buf = bytearray(66)
    fill_common_header(buf, 66, 66 - 24, msg_id=1, magic=magic)
    fill_datetime_words(buf, 24)
    buf[36] = curr & 0xFF
    buf[37] = nxt & 0xFF
    buf[38] = end & 0xFF
    buf[39] = 1
    buf[40] = 0
    buf[41] = 0
    buf[42] = run_dir & 0xFF
    buf[43] = 0
    put_f32_endian(buf, 44, speed, float_be)
    put_f32_endian(buf, 48, accel, float_be)
    put_u16(buf, 52, 0)
    put_u16(buf, 54, int(round(speed_limit)))
    buf[56] = mode & 0xFF
    buf[57] = pull & 0xFF
    buf[58] = brake & 0xFF
    buf[59] = eb & 0xFF
    buf[60] = sig_state & 0xFF
    put_u16(buf, 61, train_no)
    put_f32_endian(buf, 62, next_dist_m, float_be)
    return bytes(buf)


def build_network_frame(
    speed: float,
    accel: float,
    speed_limit: float,
    level_pos: int,
    run_mode: int,
    run_dir: int,
    curr: int,
    nxt: int,
    end: int,
    train_no: int,
    traction_force: int,
    net_pressure: int,
    load_pct: int = 50,
    brake_cyl: int = 200,
    line_current: int = 80,
    magic: bytes = bytes([0x55, 0xAA, 0x55, 0xAA]),
    float_be: bool = False,
) -> bytes:
    """
    网络屏 572B（文档偏移）。
    除速度/加速度外，同步刷新：网压、载客率、制动缸压力、线网电流、列车号，
    避免「网压默认已是 1500」导致看起来完全没变化。
    """
    buf = bytearray(572)
    fill_common_header(buf, 572, 572 - 24, msg_id=1, magic=magic)
    fill_datetime_words(buf, 24)
    buf[36] = curr & 0xFF
    buf[37] = nxt & 0xFF
    buf[38] = end & 0xFF
    buf[39] = 1  # 车间电源有
    put_f32_endian(buf, 40, speed, float_be)
    put_f32_endian(buf, 44, accel, float_be)
    put_u16(buf, 48, traction_force & 0xFFFF)
    put_u16(buf, 50, net_pressure & 0xFFFF)
    put_u16(buf, 52, int(round(speed_limit)) & 0xFFFF)
    buf[54] = level_pos & 0xFF
    buf[55] = run_mode & 0xFF
    put_u16(buf, 56, 110)
    buf[58] = run_dir & 0xFF
    buf[59] = 0x01  # tc1 激活

    # 6 节车：制动缸压力 WORD×6 @156，载客率 BYTE×6 @168，线网电流 BYTE×6 @174
    for i in range(6):
        put_u16(buf, 156 + i * 2, max(0, brake_cyl + i * 10) & 0xFFFF)
        buf[168 + i] = max(0, min(100, load_pct + i)) & 0xFF
        buf[174 + i] = max(0, min(255, line_current + i * 3)) & 0xFF

    # 主风缸压力 WORD×6 @144
    for i in range(6):
        put_u16(buf, 144 + i * 2, (800 + i * 5) & 0xFFFF)

    put_u16(buf, 570, train_no & 0xFFFF)
    return bytes(buf)


def try_connect(host: str, port: int) -> Optional[socket.socket]:
    print(f"[{ts()}] connect {host}:{port} ...", flush=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(CONNECT_TIMEOUT_S)
    try:
        sock.connect((host, port))
    except OSError as exc:
        sock.close()
        print(f"[{ts()}] FAIL {host}:{port} -> {exc}", flush=True)
        return None
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(f"[{ts()}] OK   {host}:{port}  local={sock.getsockname()}", flush=True)
    return sock


def probe(hosts_ports: List[Tuple[str, int]]) -> None:
    print("=" * 64)
    print(" 探测信号屏/网络屏 TCP 端口")
    print("=" * 64)
    for host, port in hosts_ports:
        sock = try_connect(host, port)
        if sock:
            sock.close()


def hex_preview(data: bytes, n: int = 32) -> str:
    return " ".join(f"{b:02X}" for b in data[:n]) + (" ..." if len(data) > n else "")


def run_writer(
    signal_sock: Optional[socket.socket],
    network_sock: Optional[socket.socket],
    signal_layout: str,
    duration_s: float,
    speed_unit: str,
    magic: bytes,
    float_be: bool,
) -> None:
    unit = "km/h" if speed_unit == "kmh" else "m/s"
    print(f"[{ts()}] 开始推数 interval={SEND_INTERVAL_S}s  unit={unit}  float={'BE' if float_be else 'LE'}", flush=True)
    print(f"[{ts()}] magic={magic.hex(' ').upper()}  Ctrl+C 停止", flush=True)
    print(f"[{ts()}] 网络屏请盯：速度、网压、载客率、制动缸压力（会一起变）", flush=True)
    t0 = time.time()
    seq = 0
    try:
        while True:
            elapsed = time.time() - t0
            if duration_s > 0 and elapsed >= duration_s:
                break

            # 地铁观感：默认用 km/h（0~80）；旧版 m/s 0~60 可能被屏当非法丢弃
            if speed_unit == "kmh":
                speed = 40.0 + 40.0 * math.sin(elapsed * 0.8)  # 0~80 km/h
            else:
                speed = 30.0 + 30.0 * math.sin(elapsed * 0.7)
            accel = 0.8 * math.cos(elapsed * 0.8)
            limit = 80.0
            curr, nxt, end = 1, 2, 13
            pull = 1 if accel >= 0 else 0
            brake = 1 if accel < 0 else 0
            level = 1 if pull else (2 if brake else 0)
            mode = 0
            run_mode = 0x00
            run_dir = 1
            train_no = 1
            next_dist = max(0.0, 1200.0 - abs(speed) * elapsed * 0.2)
            # 网压在 1400~1600 摆，避免一直 1500 看起来像“默认没变”
            net_v = int(1500 + 100 * math.sin(elapsed * 1.2))
            load_pct = int(30 + 50 * (0.5 + 0.5 * math.sin(elapsed * 0.5)))
            brake_cyl = int(150 + 100 * (0.5 + 0.5 * math.sin(elapsed)))
            line_i = int(40 + 60 * (0.5 + 0.5 * math.sin(elapsed * 0.9)))

            if signal_sock is not None:
                builder = build_signal_frame_spaced if signal_layout == "spaced" else build_signal_frame
                frame = builder(
                    speed, accel, limit, mode, pull, brake, 0,
                    curr, nxt, end, run_dir, train_no, next_dist, 0x02,
                    magic=magic, float_be=float_be,
                )
                try:
                    signal_sock.sendall(frame)
                except OSError as exc:
                    print(f"[{ts()}] 信号屏发送失败: {exc}", flush=True)
                    signal_sock = None

            if network_sock is not None:
                frame = build_network_frame(
                    speed, accel, limit, level, run_mode, 2,
                    curr, nxt, end, train_no,
                    traction_force=int(abs(accel) * 200),
                    net_pressure=net_v,
                    load_pct=load_pct,
                    brake_cyl=brake_cyl,
                    line_current=line_i,
                    magic=magic,
                    float_be=float_be,
                )
                try:
                    network_sock.sendall(frame)
                except OSError as exc:
                    print(f"[{ts()}] 网络屏发送失败: {exc}", flush=True)
                    network_sock = None

            if seq % 5 == 0:
                print(
                    f"[{ts()}] #{seq} speed={speed:5.1f}{unit}  netV={net_v}  "
                    f"load={load_pct}%  brakeCyl={brake_cyl}  "
                    f"signal={'ON' if signal_sock else 'OFF'}  "
                    f"network={'ON' if network_sock else 'OFF'}",
                    flush=True,
                )
                if network_sock and seq == 0:
                    print(f"         network speed@40 LE hex={frame[40:44].hex(' ').upper()} "
                          f"accel@44={frame[44:48].hex(' ').upper()}", flush=True)
            seq += 1
            time.sleep(SEND_INTERVAL_S)
    except KeyboardInterrupt:
        print(f"\n[{ts()}] 停止", flush=True)
    finally:
        for s in (signal_sock, network_sock):
            if s is not None:
                try:
                    s.close()
                except OSError:
                    pass
    print(f"[{ts()}] 共发送约 {seq} 轮", flush=True)


def main() -> int:
    p = argparse.ArgumentParser(description="往信号屏/网络屏写测试数据")
    p.add_argument("--probe", action="store_true", help="只探测常见端口是否 TCP 通")
    p.add_argument("--target", choices=["signal", "network", "both"], default="both")
    p.add_argument("--signal-host", default="192.168.100.122",
                   help="信号屏 IP（台架实测 .122）")
    p.add_argument("--signal-port", type=int, default=9999)
    p.add_argument("--network-host", default="192.168.100.121",
                   help="网络屏 IP（台架实测 .121）")
    p.add_argument("--network-port", type=int, default=8888)
    p.add_argument("--signal-layout", choices=["doc", "spaced"], default="doc")
    p.add_argument("--duration", type=float, default=0, help="秒；0=一直发直到 Ctrl+C")
    p.add_argument("--swap", action="store_true", help="对调信号/网络 host")
    p.add_argument(
        "--speed-unit",
        choices=["kmh", "mps"],
        default="kmh",
        help="速度单位（网络屏建议 kmh；旧默认 mps 可能被丢弃）",
    )
    p.add_argument(
        "--magic-alt",
        action="store_true",
        help="帧头改用 AA 55 AA 55（部分文档 DWORD 写法）",
    )
    p.add_argument(
        "--float-be",
        action="store_true",
        help="float 用大端（默认小端）",
    )
    args = p.parse_args()

    if args.swap:
        args.signal_host, args.network_host = args.network_host, args.signal_host

    magic = bytes([0xAA, 0x55, 0xAA, 0x55]) if args.magic_alt else bytes([0x55, 0xAA, 0x55, 0xAA])

    candidates = [
        (args.signal_host, args.signal_port),
        (args.network_host, args.network_port),
        ("192.168.100.121", 8888),
        ("192.168.100.121", 9999),
        ("192.168.100.122", 8888),
        ("192.168.100.122", 9999),
    ]
    seen = set()
    uniq = []
    for hp in candidates:
        if hp not in seen:
            seen.add(hp)
            uniq.append(hp)

    if args.probe:
        probe(uniq)
        return 0

    signal_sock = None
    network_sock = None
    if args.target in ("signal", "both"):
        signal_sock = try_connect(args.signal_host, args.signal_port)
    if args.target in ("network", "both"):
        network_sock = try_connect(args.network_host, args.network_port)

    if signal_sock is None and network_sock is None:
        print(f"[{ts()}] 没有任何屏连上。先跑 --probe", flush=True)
        return 1

    print(
        f"[{ts()}] 提示：网络屏协议写「无数据时网压默认显示 1500」。"
        f"若只看网压，容易误判为没变化；请看速度/载客率。",
        flush=True,
    )

    if network_sock:
        sample = build_network_frame(
            36.0, 0.2, 80, 1, 0, 2, 1, 2, 13, 1, 50, 1450,
            load_pct=55, brake_cyl=220, line_current=90,
            magic=magic, float_be=args.float_be,
        )
        print(f"[{ts()}] 网络屏样例 {len(sample)}B head={hex_preview(sample, 48)}", flush=True)
        print(f"         speed@40={sample[40:44].hex(' ').upper()} netV@50={sample[50:52].hex(' ').upper()}", flush=True)

    run_writer(
        signal_sock, network_sock, args.signal_layout, args.duration,
        args.speed_unit, magic, args.float_be,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
