#!/usr/bin/env python3
"""Probe the laboratory network screen and optionally send one 570-byte frame.

Default operation is read-only: it opens and closes a TCP connection.  A frame
is written only when --send is present.  The frame follows the observed
192.168.100.121:8888 TCP payload boundary (570 bytes), not the obsolete 572B
value in the original interface document.
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time
from datetime import datetime


FRAME_BYTES = 570
HEADER_BYTES = 24
DEFAULT_HOST = "192.168.100.121"
DEFAULT_PORT = 8888


def build_frame(speed_kmh: float, station: int, next_station: int, terminal: int) -> bytes:
    """Build one display-only HMI frame with conservative stationary defaults."""
    frame = bytearray(FRAME_BYTES)
    now = datetime.now()
    struct.pack_into("<IHHQHHHH", frame, 0,
                     0xAA55AA55, FRAME_BYTES, FRAME_BYTES - HEADER_BYTES,
                     int(time.time() * 1000), 0, 0, 1, 1)
    struct.pack_into("<HHHHHH", frame, 24,
                     now.year, now.month, now.day, now.hour, now.minute, now.second)
    frame[36:39] = bytes((station, next_station, terminal))
    frame[39] = 1                 # power available
    struct.pack_into("<f", frame, 40, speed_kmh)
    struct.pack_into("<f", frame, 44, 0.0)
    struct.pack_into("<HHH", frame, 48, 0, 1500, 60)
    frame[54] = 0                 # coast
    frame[55] = 0                 # manual display mode
    struct.pack_into("<H", frame, 56, 110)
    frame[58] = 2                 # down direction in the observed HMI encoding
    frame[59] = 1                 # TC1 active
    struct.pack_into("<H", frame, 568, 1)
    return bytes(frame)


def receive_available(sock: socket.socket, timeout: float) -> bytes:
    sock.settimeout(timeout)
    chunks: list[bytes] = []
    try:
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            chunks.append(chunk)
            if len(chunk) < 4096:
                break
    except TimeoutError:
        pass
    return b"".join(chunks)


def main() -> int:
    parser = argparse.ArgumentParser(description="Network screen 570B probe / single-frame test")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--timeout", type=float, default=2.0)
    parser.add_argument("--send", action="store_true", help="send one display-only frame")
    parser.add_argument("--speed-kmh", type=float, default=0.0)
    parser.add_argument("--station", type=int, default=1)
    parser.add_argument("--next-station", type=int, default=2)
    parser.add_argument("--terminal", type=int, default=13)
    args = parser.parse_args()

    frame = build_frame(args.speed_kmh, args.station, args.next_station, args.terminal)
    print(f"target={args.host}:{args.port} expected_tcp_payload={FRAME_BYTES}B")
    print(f"frame_header={frame[:24].hex(' ')}")
    try:
        with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
            print(f"TCP_CONNECT=OK local={sock.getsockname()[0]}:{sock.getsockname()[1]}")
            if not args.send:
                print("READ_ONLY_OK (use --send only after teacher approval)")
                return 0
            sock.sendall(frame)
            print(f"TX_OK bytes={len(frame)} speed_kmh={args.speed_kmh:g}")
            echoed = receive_available(sock, args.timeout)
            if echoed:
                print(f"RX bytes={len(echoed)} header={echoed[:8].hex(' ')}")
                if len(echoed) % FRAME_BYTES == 0:
                    print(f"RX_FRAME_BOUNDARY_OK frames={len(echoed) // FRAME_BYTES}")
            else:
                print("RX none within timeout (this does not prove display failure)")
            return 0
    except OSError as exc:
        print(f"TCP_CONNECT=FAILED error={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
