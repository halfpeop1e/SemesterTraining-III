#!/usr/bin/env python3
"""
704 / PLC TCP 连接测试 Demo（老师台架）

设备角色（协议文档 2026-06）:
  192.168.100.123  —— PLC 输入（我们读 46B 手柄帧；回写格式须按老师文档现场验收）
  192.168.100.121  —— 网络屏 TCP:8888（572B 发送已实现，待实体显示验收）
  192.168.100.122  —— 信号屏 TCP:9999（66B 发送已实现，待实体显示验收）
  192.168.100.124  —— 视景（口头；UDP 端口待确认）

默认连接 PLC .123:8001/8002/8003，实时打印 hex + 字段摘要。
对照文档: docs/protocol/老师台架接口映射.md

用法:
  python scripts/tcp704_live_demo.py
  python scripts/tcp704_live_demo.py --host 192.168.100.123 --port 8001
  python scripts/tcp704_live_demo.py --ports 8001,8002,8003 --reconnect
  python scripts/tcp704_live_demo.py --probe-outputs
"""

from __future__ import annotations

import argparse
import socket
import sys
import time
from datetime import datetime
from typing import List, Optional, Tuple


DEFAULT_HOST = "192.168.100.123"  # PLC 输入
# 文档：.121=信号屏:9999，.122=网络屏:8888（探测用，先测 TCP 是否可达）
OUTPUT_HOSTS = ["192.168.100.121", "192.168.100.122"]
OUTPUT_PROBE_PORTS = {
    "192.168.100.121": [9999, 8888],  # 文档信号屏优先 9999；若口头对调也可能是 8888
    "192.168.100.122": [8888, 9999],
}
DEFAULT_PORTS = [8001, 8002, 8003]
CONNECT_TIMEOUT_S = 3.0
RECV_TIMEOUT_S = 1.0
FRAME_LEN = 46
MAGIC = bytes([0x55, 0xAA, 0x55, 0xAA])


def ts() -> str:
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def u16le(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8)


def hex_dump(data: bytes, width: int = 16) -> str:
    lines = []
    for i in range(0, len(data), width):
        chunk = data[i : i + width]
        hex_part = " ".join(f"{b:02X}" for b in chunk)
        ascii_part = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"  {i:04X}  {hex_part:<{width * 3}}  |{ascii_part}|")
    return "\n".join(lines)


def find_frames(buf: bytearray) -> Tuple[List[bytes], bytearray]:
    """切出合法 46 字节帧（magic + totalLength=46 + dataLength=22）。"""
    frames: List[bytes] = []
    offset = 0
    while len(buf) - offset >= len(MAGIC):
        idx = buf.find(MAGIC, offset)
        if idx < 0:
            keep = min(len(MAGIC) - 1, len(buf))
            return frames, buf[-keep:] if keep else bytearray()
        if len(buf) - idx < FRAME_LEN:
            return frames, buf[idx:]
        total_len = u16le(buf, idx + 4)
        data_len = u16le(buf, idx + 6)
        if total_len != FRAME_LEN or data_len != 22:
            offset = idx + 1
            continue
        frames.append(bytes(buf[idx : idx + FRAME_LEN]))
        offset = idx + FRAME_LEN
    return frames, buf[offset:]


def summarize_frame(frame: bytes) -> str:
    """与后端 Protocol704FrameParser local-v1 约定对齐的可读摘要。"""
    year, month, day = u16le(frame, 8), u16le(frame, 10), u16le(frame, 12)
    hour, minute, second = u16le(frame, 14), u16le(frame, 16), u16le(frame, 18)
    verify_type, verify_code = u16le(frame, 20), u16le(frame, 22)
    speed = u16le(frame, 26)
    byte28, byte34, byte35 = frame[28], frame[34], frame[35]
    direction = u16le(frame, 36)
    master = u16le(frame, 38)
    traction = u16le(frame, 40)
    brake = u16le(frame, 42)

    dir_map = {0: "ZERO", 1: "FORWARD", 2: "REVERSE"}
    master_map = {0: "coast", 1: "traction", 2: "brake", 4: "emergency_brake"}
    dir_s = dir_map.get(direction, f"UNKNOWN({direction})")
    cmd_s = master_map.get(master, f"UNSUPPORTED({master})")

    # byte34 模式/发车优先于主手柄（与后端一致）
    if byte34 & 0x08:
        cmd_s = "SET_MANUAL"
    elif byte34 & (0x04 | 0x80):
        cmd_s = "RESUME_ATO"
    elif byte34 & 0x10:
        cmd_s = "DEPART_CONFIRM"

    flags = []
    if byte35 & 0x02:
        flags.append("KEY_ON")
    if byte28 & 0x01:
        flags.append("EB_BTN")
    if frame[24] & 0x20:
        flags.append("DOORS_OK")
    if frame[24] & 0x40:
        flags.append("NET_FAULT")

    return (
        f"time={year:04d}-{month:02d}-{day:02d} {hour:02d}:{minute:02d}:{second:02d}  "
        f"verify={verify_type}/{verify_code}  "
        f"cmd={cmd_s}  dir={dir_s}  "
        f"trac={traction}%  brake={brake}%  speed_raw={speed}  "
        f"flags=[{','.join(flags) or '-'}]"
    )


def try_connect(host: str, port: int) -> Optional[socket.socket]:
    print(f"[{ts()}] 尝试 TCP 连接 {host}:{port} ...", flush=True)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(CONNECT_TIMEOUT_S)
    try:
        sock.connect((host, port))
    except OSError as exc:
        sock.close()
        print(f"[{ts()}] 连接失败 {host}:{port} -> {exc}", flush=True)
        return None
    sock.settimeout(RECV_TIMEOUT_S)
    peer = sock.getpeername()
    local = sock.getsockname()
    print(f"[{ts()}] 已建立: local={local[0]}:{local[1]} <-> peer={peer[0]}:{peer[1]}", flush=True)
    return sock


def probe_host(host: str, ports: List[int]) -> None:
    print(f"\n[{ts()}] 探测 {host} 端口 {ports} ...", flush=True)
    any_ok = False
    for port in ports:
        sock = try_connect(host, port)
        if sock is not None:
            role = "信号屏?" if port == 9999 else ("网络屏?" if port == 8888 else "未知")
            print(f"[{ts()}] {host}:{port} TCP 可连通（{role}；当前项目尚未实现向屏写帧）", flush=True)
            sock.close()
            any_ok = True
    if not any_ok:
        print(f"[{ts()}] {host} 在端口 {ports} 上均未连通", flush=True)


def live_read(sock: socket.socket, host: str, port: int) -> None:
    pending = bytearray()
    total_bytes = 0
    frame_count = 0
    print(f"[{ts()}] 开始实时接收输入帧（Ctrl+C 退出）", flush=True)
    print("-" * 72, flush=True)
    try:
        while True:
            try:
                chunk = sock.recv(4096)
            except socket.timeout:
                continue
            except OSError as exc:
                print(f"[{ts()}] 接收异常: {exc}", flush=True)
                break

            if not chunk:
                print(f"[{ts()}] 对端关闭连接 ({host}:{port})", flush=True)
                break

            total_bytes += len(chunk)
            print(f"\n[{ts()}] RECV {len(chunk)} bytes  (累计 {total_bytes} B)", flush=True)
            print(hex_dump(chunk), flush=True)

            pending.extend(chunk)
            frames, pending = find_frames(pending)
            for frame in frames:
                frame_count += 1
                print(
                    f"[{ts()}] ★ local-v1 帧 #{frame_count} ({FRAME_LEN} B)  "
                    f"{summarize_frame(frame)}",
                    flush=True,
                )
    except KeyboardInterrupt:
        print(f"\n[{ts()}] 用户中断", flush=True)
    finally:
        try:
            sock.close()
        except OSError:
            pass
        print(
            f"[{ts()}] 结束: 共收 {total_bytes} B, 识别帧 {frame_count} 个",
            flush=True,
        )


def parse_ports(raw: Optional[str], single: Optional[int]) -> List[int]:
    if single is not None:
        return [single]
    if raw:
        ports = []
        for part in raw.split(","):
            part = part.strip()
            if part:
                ports.append(int(part))
        return ports or list(DEFAULT_PORTS)
    return list(DEFAULT_PORTS)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="老师台架 704 TCP Demo：默认读输入设备 192.168.100.123"
    )
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"输入设备 IP（默认 {DEFAULT_HOST}）")
    parser.add_argument("--port", type=int, default=None, help="单个端口")
    parser.add_argument("--ports", default=None, help="逗号分隔端口，默认 8001,8002,8003")
    parser.add_argument("--reconnect", action="store_true", help="断线后每 2s 重连")
    parser.add_argument(
        "--probe-outputs",
        action="store_true",
        help="额外探测输出设备 .121/.122 是否 TCP 可达（不写数据）",
    )
    args = parser.parse_args()
    ports = parse_ports(args.ports, args.port)

    print("=" * 72)
    print(" 704 TCP Live Demo（老师台架）")
    print(f" PLC输入(读) host={args.host}  ports={ports}")
    print(" 信号屏 .121:9999 / 网络屏 .122:8888  ← 出站尚未实现")
    print(f" reconnect={args.reconnect}  probe_outputs={args.probe_outputs}")
    print("=" * 72)

    if args.probe_outputs:
        for out_host in OUTPUT_HOSTS:
            probe_host(out_host, OUTPUT_PROBE_PORTS.get(out_host, ports))

    while True:
        sock = None
        for port in ports:
            sock = try_connect(args.host, port)
            if sock is not None:
                live_read(sock, args.host, port)
                break
        else:
            print(
                f"[{ts()}] 输入设备未连通。"
                f" 请确认 {args.host} 在线: ping {args.host}",
                flush=True,
            )

        if not args.reconnect:
            return 1 if sock is None else 0

        print(f"[{ts()}] 2s 后重连 ...", flush=True)
        try:
            time.sleep(2.0)
        except KeyboardInterrupt:
            print(f"\n[{ts()}] 退出", flush=True)
            return 0


if __name__ == "__main__":
    sys.exit(main())
