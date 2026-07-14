#!/usr/bin/env python3
"""Probe the laboratory signal screen and optionally send one 68-byte frame.

Default operation is read-only.  The observed TCP payload at
192.168.100.122:9999 is 68 bytes even though bytes 4..7 retain legacy values
totalLen=62 and dataLen=42.
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import time
from datetime import datetime


FRAME_BYTES = 68
DEFAULT_HOST = "192.168.100.122"
DEFAULT_PORT = 9999


def build_frame(speed_kmh: float, station: int, next_station: int, terminal: int) -> bytes:
    """Build one display-only MMI frame using the observed 68-byte layout."""
    frame = bytearray(FRAME_BYTES)
    now = datetime.now()
    struct.pack_into("<IHHQHHHH", frame, 0,
                     0xAA55AA55, 62, 42, int(time.time() * 1000), 0, 0, 1, 1)
    struct.pack_into("<HHHHHH", frame, 24,
                     now.year, now.month, now.day, now.hour, now.minute, now.second)
    frame[36:39] = bytes((station, next_station, terminal))
    frame[39:42] = b"\x01\x01\x01"  # CM/MM/CTC alive
    frame[42] = 1                      # down direction in observed MMI encoding
    struct.pack_into("<f", frame, 44, speed_kmh)
    struct.pack_into("<f", frame, 48, 0.0)
    struct.pack_into("<HH", frame, 52, 0, 60)
    frame[56] = 4                      # RM/manual display mode
    frame[57:62] = b"\x00\x00\x00\x00\x01"
    struct.pack_into("<H", frame, 62, 1)
    struct.pack_into("<f", frame, 64, 0.0)
    return bytes(frame)


def main() -> int:
    parser = argparse.ArgumentParser(description="Signal screen 68B probe / single-frame test")
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
    print(f"target={args.host}:{args.port} expected_tcp_payload={FRAME_BYTES}B legacy_header=62/42")
    print(f"frame_header={frame[:24].hex(' ')}")
    try:
        with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
            print(f"TCP_CONNECT=OK local={sock.getsockname()[0]}:{sock.getsockname()[1]}")
            if not args.send:
                print("READ_ONLY_OK (use --send only after teacher approval)")
                return 0
            sock.sendall(frame)
            print(f"TX_OK bytes={len(frame)} speed_kmh={args.speed_kmh:g}")
            sock.settimeout(args.timeout)
            try:
                received = sock.recv(4096)
                print(f"RX bytes={len(received)} header={received[:8].hex(' ')}")
            except TimeoutError:
                print("RX none within timeout (normal for this one-way screen channel)")
            return 0
    except OSError as exc:
        print(f"TCP_CONNECT=FAILED error={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
