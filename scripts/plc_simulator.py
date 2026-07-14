#!/usr/bin/env python3
"""
PLC 司机台模拟器（TCP Server）

替代实体 PLC（192.168.100.123:8001/8002/8003），让后端能连接并接收模拟的
46B 手柄帧。模拟完整的列车启动流程：

  1) 钥匙接通 + 惰行
  2) 方向手柄 → 向前
  3) 切换到人工模式（SET_MANUAL）
  4) 发车确认（DEPART_CONFIRM）
  5) 主手柄 → 牵引（可调级位）
  6) 长按 ATO 启动按钮 2 秒
  7) ATO 激活后维持牵引

同时接收并打印后端发来的 28B 输出帧（指示灯 + 速度回写）。

用法：
  python scripts/plc_startup_simulator.py
  python scripts/plc_startup_simulator.py --ports 8001,8002,8003 --traction-level 60
  python scripts/plc_startup_simulator.py --delay-before-start 3 --ato-hold 2.5
  python scripts/plc_startup_simulator.py --no-startup  # 只转发，不自动启动
"""

from __future__ import annotations

import argparse
import os
import selectors
import socket
import struct
import sys
import time
from datetime import datetime
from typing import Dict, List, Optional, Tuple


# ── 帧格式常量 ──

PLC_FRAME_LEN = 46          # PLC → 上位机 46B
PLC_INPUT_MAGIC = 0xAA55AA55

OUTPUT_FRAME_LEN = 28       # 上位机 → PLC 28B（实验室实测）

# ── PLC帧数据 MD 日志 ──
_MD_LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plc-frame-log.md")


def _init_md_log():
    """首次写入时创建 MD 文件表头。"""
    if not os.path.exists(_MD_LOG_FILE):
        with open(_MD_LOG_FILE, "w", encoding="utf-8") as f:
            f.write("# PLC 帧数据日志\n\n")
            f.write("| 时间 | 速度(km/h) | raw WORD | byte24 | byte25 | hex 原始帧 |\n")
            f.write("|------|-----------|----------|--------|--------|-----------|\n")


def _log_output_frame_to_md(data: bytes):
    """解析 28B 输出帧并追加写入 MD 日志文件。"""
    if len(data) < OUTPUT_FRAME_LEN:
        return
    try:
        _init_md_log()
        yr = struct.unpack_from("<H", data, 8)[0]
        mo = struct.unpack_from("<H", data, 10)[0]
        dy = struct.unpack_from("<H", data, 12)[0]
        hr = struct.unpack_from("<H", data, 14)[0]
        mi = struct.unpack_from("<H", data, 16)[0]
        sc = struct.unpack_from("<H", data, 18)[0]
        b24 = data[24]
        b25 = data[25]
        speed = struct.unpack_from("<H", data, 26)[0]
        hex_str = _hex(data, max_len=92)
        timestamp = f"{yr:04d}-{mo:02d}-{dy:02d} {hr:02d}:{mi:02d}:{sc:02d}"
        b24_label = _bits_on(b24, _B24_BITS)
        b25_label = _bits_on(b25, _B25_BITS)
        with open(_MD_LOG_FILE, "a", encoding="utf-8") as f:
            f.write(f"| {timestamp} | {speed:.0f} | 0x{speed:04X} | "
                    f"0x{b24:02X} [{b24_label}] | 0x{b25:02X} [{b25_label}] | "
                    f"`{hex_str}` |\n")
    except Exception:
        pass


def ts() -> str:
    return datetime.now().strftime("%H:%M:%S")


def _now() -> Tuple[int, int, int, int, int, int]:
    n = datetime.now()
    return n.year, n.month, n.day, n.hour, n.minute, n.second


def _hex(b: bytes, max_len: int = 64) -> str:
    length = min(len(b), max_len)
    return " ".join(f"{b[i]:02X}" for i in range(length))


# ── 46B PLC 帧构造 ──

def build_plc_46b_frame(
    *,
    key_switch: bool = True,
    direction: int = 0,          # 0=ZERO, 1=FORWARD, 2=REVERSE
    master_handle: int = 0,      # 0=coast, 1=traction, 2=brake, 4=EB
    traction_level: int = 0,     # 0-100
    brake_level: int = 0,        # 0-100
    byte34: int = 0,             # 按钮位: bit3=SET_MANUAL, bit4=DEPART, bit7=ATO_START
    doors_closed: bool = True,
    speed_echo: int = 0,         # 速度回显 WORD（km/h）
) -> bytes:
    """构造一条 46B PLC 输入帧（PLC → 上位机）。"""
    buf = bytearray(PLC_FRAME_LEN)

    # header
    struct.pack_into("<I", buf, 0, PLC_INPUT_MAGIC)   # 55 AA 55 AA
    struct.pack_into("<H", buf, 4, PLC_FRAME_LEN)      # totalLen = 46
    struct.pack_into("<H", buf, 6, 22)                  # dataLen = 22

    yr, mo, dy, hr, mi, sc = _now()
    struct.pack_into("<H", buf, 8, yr)
    struct.pack_into("<H", buf, 10, mo)
    struct.pack_into("<H", buf, 12, dy)
    struct.pack_into("<H", buf, 14, hr)
    struct.pack_into("<H", buf, 16, mi)
    struct.pack_into("<H", buf, 18, sc)

    # verify (bytes 20-23) = 0
    struct.pack_into("<H", buf, 20, 0)
    struct.pack_into("<H", buf, 22, 0)

    # byte24: 指示灯
    b24 = 0
    if doors_closed:
        b24 |= 0x20  # bit5: doors_closed_ok
    buf[24] = b24

    # byte25: 模式灯
    buf[25] = 0

    # bytes 26-27: 速度回显
    struct.pack_into("<H", buf, 26, speed_echo & 0xFFFF)

    # byte28: EB/制动按钮
    buf[28] = 0

    # byte29: 门按钮
    buf[29] = 0

    # bytes 30-31: 外灯
    struct.pack_into("<H", buf, 30, 0)
    # bytes 32-33: 门模式
    struct.pack_into("<H", buf, 32, 0)

    # byte34: 模式/确认/ATO 按钮
    buf[34] = byte34

    # byte35: key_switch / wash / vigilance
    b35 = 0
    if key_switch:
        b35 |= 0x02  # bit1: key_switch_on
    buf[35] = b35

    # bytes 36-37: 方向手柄
    struct.pack_into("<H", buf, 36, direction)

    # bytes 38-39: 主手柄类型
    struct.pack_into("<H", buf, 38, master_handle)

    # bytes 40-41: 牵引级位
    struct.pack_into("<H", buf, 40, max(0, min(100, traction_level)))

    # bytes 42-43: 制动级位
    struct.pack_into("<H", buf, 42, max(0, min(100, brake_level)))

    # bytes 44-45: 预留
    struct.pack_into("<H", buf, 44, 0)

    return bytes(buf)


def describe_frame(frame: bytes) -> str:
    """简要描述 46B 帧内容。"""
    if len(frame) < 44:
        return f"len={len(frame)} (too short)"
    parts: List[str] = []
    b34 = frame[34]
    if b34 & 0x80:
        parts.append("ATO_START")
    if b34 & 0x10:
        parts.append("DEPART_CONFIRM")
    if b34 & 0x08:
        parts.append("SET_MANUAL")
    if b34 & 0x04:
        parts.append("RESUME_ATO")
    dh = struct.unpack_from("<H", frame, 36)[0]
    parts.append({0: "DIR=0", 1: "FWD", 2: "REV"}.get(dh, f"DIR={dh}"))
    mh = struct.unpack_from("<H", frame, 38)[0]
    mh_label = {0: "COAST", 1: "TRACT", 2: "BRAKE", 4: "EB"}.get(mh, f"MH={mh}")
    parts.append(mh_label)
    if mh == 1:
        tl = struct.unpack_from("<H", frame, 40)[0]
        parts.append(f"Lv={tl}%")
    key = frame[35] & 0x02
    if key:
        parts.append("KEY=ON")
    return " | ".join(parts)


# ── 28B 输出帧解析 ──

# byte24 位定义
_B24_BITS = {
    1: "高断合",
    2: "制动缓解不良",
    4: "开门灯",
    5: "门关好",
    6: "网络故障",
    7: "具备自动折返",
}

# byte25 位定义
_B25_BITS = {
    0: "具备ATO",
    1: "进入洗车",
    2: "激活ATO",
    3: "激活自动折返",
}


def _bits_on(byte_val: int, bit_map: dict) -> str:
    parts = []
    for bit, label in bit_map.items():
        if byte_val & (1 << bit):
            parts.append(label)
    return "+".join(parts) if parts else "-"


def parse_output_frame(data: bytes) -> str:
    """详细解析后端发来的 28B 输出帧。"""
    if len(data) < OUTPUT_FRAME_LEN:
        return f"len={len(data)} (too short, expected {OUTPUT_FRAME_LEN}B)"

    # header fields
    magic = struct.unpack_from("<I", data, 0)[0]
    total_len = struct.unpack_from("<H", data, 4)[0]
    data_len = struct.unpack_from("<H", data, 6)[0]
    yr = struct.unpack_from("<H", data, 8)[0]
    mo = struct.unpack_from("<H", data, 10)[0]
    dy = struct.unpack_from("<H", data, 12)[0]
    hr = struct.unpack_from("<H", data, 14)[0]
    mi = struct.unpack_from("<H", data, 16)[0]
    sc = struct.unpack_from("<H", data, 18)[0]
    verify_type = struct.unpack_from("<H", data, 20)[0]
    verify_code = struct.unpack_from("<H", data, 22)[0]

    b24 = data[24]
    b25 = data[25]
    speed = struct.unpack_from("<H", data, 26)[0]

    lines = [
        f"  magic=0x{magic:08X} totalLen={total_len} dataLen={data_len}",
        f"  time={yr:04d}-{mo:02d}-{dy:02d} {hr:02d}:{mi:02d}:{sc:02d} "
        f"verify=({verify_type},{verify_code})",
        f"  byte24=0x{b24:02X} [{_bits_on(b24, _B24_BITS)}]",
        f"  byte25=0x{b25:02X} [{_bits_on(b25, _B25_BITS)}]",
        f"  speed={speed} km/h (raw WORD=0x{speed:04X})",
        f"  hex={_hex(data)}",
    ]
    return "\n".join(lines)


# ── TCP 连接管理 ──

class PlcSimulator:
    """模拟 PLC 司机台，在 8001/8002/8003 上监听 TCP 连接。"""

    def __init__(self, host: str, ports: List[int],
                 traction_level: int = 50,
                 delay_before_start: float = 2.0,
                 ato_hold_seconds: float = 2.0,
                 frame_interval: float = 0.1,
                 auto_startup: bool = True):
        self.host = host
        self.ports = ports
        self.traction_level = traction_level
        self.delay_before_start = delay_before_start
        self.ato_hold_seconds = ato_hold_seconds
        self.frame_interval = frame_interval
        self.auto_startup = auto_startup

        self.control_port = ports[0] if ports else 8001
        self._sel = selectors.DefaultSelector()
        self._servers: Dict[int, socket.socket] = {}
        self._clients: Dict[int, socket.socket] = {}  # port → connected socket
        self._running = True
        self._startup_done = False
        self._startup_thread_idx = 0

        # 当前手柄状态
        self._key_switch = True
        self._direction = 0
        self._master_handle = 0
        self._traction_level = 0
        self._byte34 = 0
        self._speed_echo = 0

    # ── 启动序列 ──

    def _startup_sequence(self):
        """在控制端口连接后，按步骤发送手柄序列帧。"""
        if self._startup_done or not self.auto_startup:
            return
        print(f"\n[{ts()}] === PLC 启动序列开始（{self.delay_before_start}s 后） ===",
              flush=True)

        time.sleep(self.delay_before_start)

        # 步骤 1：钥匙接通 + 惰行（持续 0.5s）
        print(f"[{ts()}] 步骤 1: 钥匙接通 + 惰行", flush=True)
        self._key_switch = True
        self._direction = 0
        self._master_handle = 0
        self._traction_level = 0
        self._byte34 = 0
        self._transmit_cycle(0.5)

        # 步骤 2：方向手柄 → 向前
        print(f"[{ts()}] 步骤 2: 方向手柄 → 向前", flush=True)
        self._direction = 1
        self._transmit_cycle(0.3)

        # 步骤 3：切换到人工模式
        print(f"[{ts()}] 步骤 3: SET_MANUAL（byte34 bit3）", flush=True)
        self._byte34 = 0x08  # mode_downgrade_confirm
        self._transmit_cycle(0.2)
        self._byte34 = 0  # 松开按钮
        time.sleep(0.3)

        # 步骤 4：发车确认
        print(f"[{ts()}] 步骤 4: DEPART_CONFIRM（byte34 bit4）", flush=True)
        self._byte34 = 0x10  # confirm_btn
        self._transmit_cycle(0.2)
        self._byte34 = 0
        time.sleep(0.3)

        # 步骤 5：主手柄 → 牵引
        print(f"[{ts()}] 步骤 5: 牵引 {self.traction_level}%", flush=True)
        self._master_handle = 1  # traction
        self._traction_level = self.traction_level
        self._transmit_cycle(0.5)

        # 步骤 6：长按 ATO 启动按钮
        print(f"[{ts()}] 步骤 6: 长按 ATO 启动按钮 {self.ato_hold_seconds}s", flush=True)
        deadline = time.time() + self.ato_hold_seconds
        while time.time() < deadline and self._running:
            self._byte34 = 0x80  # ato_start_btn
            self._transmit_cycle(0.2)
        self._byte34 = 0
        self._transmit_cycle(0.1)

        self._startup_done = True
        print(f"[{ts()}] === 启动序列完成，维持牵引 ===", flush=True)

    def _transmit_cycle(self, duration: float):
        """发送当前手柄状态帧，持续 duration 秒。"""
        deadline = time.time() + duration
        while time.time() < deadline and self._running:
            frame = build_plc_46b_frame(
                key_switch=self._key_switch,
                direction=self._direction,
                master_handle=self._master_handle,
                traction_level=self._traction_level,
                byte34=self._byte34,
                speed_echo=self._speed_echo,
            )
            self._send_to_all(frame)
            time.sleep(self.frame_interval)

    def _send_to_all(self, frame: bytes):
        """向所有已连接的端口发送帧。控制端口先发。"""
        # 控制端口先发
        ctrl_sock = self._clients.get(self.control_port)
        if ctrl_sock:
            try:
                ctrl_sock.sendall(frame)
            except OSError:
                pass
        # 镜像端口
        for port, sock in self._clients.items():
            if port == self.control_port:
                continue
            try:
                sock.sendall(frame)
            except OSError:
                pass

    def _send_keepalive(self):
        """发送维持帧（持续牵引，无按钮）。"""
        frame = build_plc_46b_frame(
            key_switch=True,
            direction=1,  # FORWARD
            master_handle=self._master_handle,
            traction_level=self._traction_level,
            byte34=0,
            speed_echo=self._speed_echo,
        )
        self._send_to_all(frame)

    # ── 网络事件处理 ──

    def _accept(self, server_sock: socket.socket, port: int):
        conn, addr = server_sock.accept()
        conn.setblocking(False)
        is_control = (port == self.control_port)
        label = "CTRL" if is_control else "MIRROR"
        print(f"[{ts()}] [{label}] 新连接 port={port} from {addr}", flush=True)

        # 注册到 selector
        self._sel.register(conn, selectors.EVENT_READ, data=("client", port))
        self._clients[port] = conn

        # 如果是控制端口，启动启动序列（后台线程）
        if is_control and self.auto_startup and not self._startup_done:
            import threading
            t = threading.Thread(target=self._startup_sequence, daemon=True)
            t.start()

    def _handle_client_data(self, conn: socket.socket, port: int):
        """读取后端发来的 28B 输出帧。"""
        try:
            data = conn.recv(4096)
        except OSError:
            data = None

        if not data:
            self._close_client(conn, port)
            return

        # 解析输出帧
        if len(data) >= OUTPUT_FRAME_LEN:
            info = parse_output_frame(data)
            # 提取速度回显用于下次 PLC 帧
            sp = struct.unpack_from("<H", data, 26)[0]
            self._speed_echo = sp
            label = "CTRL" if port == self.control_port else "MIRROR"
            print(f"[{ts()}] [{label}] ← 28B output: {info}", flush=True)
            _log_output_frame_to_md(data)  # 写入 MD 日志

    def _close_client(self, conn: socket.socket, port: int):
        label = "CTRL" if port == self.control_port else "MIRROR"
        print(f"[{ts()}] [{label}] 断开 port={port}", flush=True)
        try:
            self._sel.unregister(conn)
        except Exception:
            pass
        try:
            conn.close()
        except OSError:
            pass
        if self._clients.get(port) is conn:
            del self._clients[port]

    # ── 主循环 ──

    def run(self):
        # 启动监听
        for port in self.ports:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((self.host, port))
            sock.listen(1)
            sock.setblocking(False)
            self._sel.register(sock, selectors.EVENT_READ, data=("server", port))
            self._servers[port] = sock
            print(f"[{ts()}] 监听 {self.host}:{port} "
                  f"({'控制端口' if port == self.control_port else '镜像端口'})",
                  flush=True)

        print(f"\n[{ts()}] PLC 模拟器就绪，等待后端连接...")
        print(f"[{ts()}] 启动参数: traction={self.traction_level}%  "
              f"delay={self.delay_before_start}s  ato_hold={self.ato_hold_seconds}s")
        print(f"[{ts()}] 启动序列完成后每 {self.frame_interval}s 发送维持帧")
        print()

        last_keepalive = 0.0
        try:
            while self._running:
                events = self._sel.select(timeout=0.5)
                for key, mask in events:
                    event_type, port = key.data
                    if event_type == "server":
                        self._accept(key.fileobj, port)  # type: ignore[arg-type]
                    elif event_type == "client":
                        self._handle_client_data(key.fileobj, port)  # type: ignore[arg-type]

                # 启动完成后发送维持帧
                if self._startup_done and self._clients:
                    now = time.time()
                    if now - last_keepalive >= 0.1:
                        self._send_keepalive()
                        last_keepalive = now

        except KeyboardInterrupt:
            print(f"\n[{ts()}] 收到中断信号，关闭...", flush=True)
        finally:
            self._running = False
            self._sel.close()
            for sock in self._servers.values():
                try:
                    sock.close()
                except OSError:
                    pass
            for sock in self._clients.values():
                try:
                    sock.close()
                except OSError:
                    pass
            print(f"[{ts()}] 已关闭所有连接", flush=True)


# ── 入口 ──

def main() -> int:
    p = argparse.ArgumentParser(
        description="PLC 司机台模拟器 — 替代实体 PLC (192.168.100.123)",
    )
    p.add_argument("--host", default="0.0.0.0",
                   help="监听地址（默认 0.0.0.0，即所有网卡）")
    p.add_argument("--ports", default="8001,8002,8003",
                   help="逗号分隔的端口列表（默认 8001,8002,8003）")
    p.add_argument("--traction-level", type=int, default=50,
                   help="牵引百分比 0-100（默认 50）")
    p.add_argument("--delay-before-start", type=float, default=2.0,
                   help="连接后等待秒数再开始启动序列（默认 2.0）")
    p.add_argument("--ato-hold", type=float, default=2.0,
                   help="长按 ATO 按钮秒数（默认 2.0）")
    p.add_argument("--frame-interval", type=float, default=0.1,
                   help="帧间隔秒数（默认 0.1，模拟 100ms PLC 周期）")
    p.add_argument("--no-startup", action="store_true",
                   help="不执行自动启动序列，仅转发维持帧")
    args = p.parse_args()

    ports = []
    for p_str in args.ports.split(","):
        try:
            ports.append(int(p_str.strip()))
        except ValueError:
            print(f"无效端口: {p_str.strip()}", file=sys.stderr)
            return 2
    if not ports:
        ports = [8001, 8002, 8003]

    sim = PlcSimulator(
        host=args.host,
        ports=ports,
        traction_level=args.traction_level,
        delay_before_start=args.delay_before_start,
        ato_hold_seconds=args.ato_hold,
        frame_interval=args.frame_interval,
        auto_startup=not args.no_startup,
    )
    sim.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
