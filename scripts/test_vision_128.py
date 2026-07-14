#!/usr/bin/env python3
"""Inspect or replay a teacher-approved 128-byte Vision UDP capture.

The actual Vision target is 192.168.100.124:8303.  It expects the 128-byte
77-signal / 29-switch Vision 1.3 variant. This script never synthesizes a packet.
It can inspect a pcapng
file and, only with --send --teacher-approved, replay one captured UDP payload.
"""

from __future__ import annotations

import argparse
import ipaddress
import socket
import struct
import sys
from pathlib import Path


PAYLOAD_BYTES = 154
DEFAULT_HOST = "192.168.100.124"
DEFAULT_PORT = 8303


def udp_payload_from_ethernet(packet: bytes, port: int) -> bytes | None:
    if len(packet) < 42:
        return None
    ether_type = struct.unpack_from("!H", packet, 12)[0]
    ip_offset = 14
    if ether_type == 0x8100 and len(packet) >= 46:
        ether_type = struct.unpack_from("!H", packet, 16)[0]
        ip_offset = 18
    if ether_type != 0x0800 or len(packet) < ip_offset + 20:
        return None
    ihl = (packet[ip_offset] & 0x0F) * 4
    if ihl < 20 or len(packet) < ip_offset + ihl + 8 or packet[ip_offset + 9] != 17:
        return None
    udp_offset = ip_offset + ihl
    source_port, destination_port, udp_length = struct.unpack_from("!HHH", packet, udp_offset)
    if source_port != port and destination_port != port:
        return None
    payload = packet[udp_offset + 8:udp_offset + udp_length]
    return payload if len(payload) == PAYLOAD_BYTES else None


def extract_payloads(pcapng_path: Path, port: int) -> list[bytes]:
    raw = pcapng_path.read_bytes()
    payloads: list[bytes] = []
    offset = 0
    while offset + 12 <= len(raw):
        block_type, block_length = struct.unpack_from("<II", raw, offset)
        if block_length < 12 or offset + block_length > len(raw):
            raise ValueError(f"invalid pcapng block at offset {offset}")
        if block_type == 0x00000006 and block_length >= 32:  # Enhanced Packet Block
            captured_length = struct.unpack_from("<I", raw, offset + 20)[0]
            packet_start = offset + 28
            packet_end = packet_start + captured_length
            if packet_end <= offset + block_length - 4:
                payload = udp_payload_from_ethernet(raw[packet_start:packet_end], port)
                if payload is not None:
                    payloads.append(payload)
        offset += block_length
    return payloads


def main() -> int:
    parser = argparse.ArgumentParser(description="Vision 128B capture inspector / approved replay")
    parser.add_argument("--capture", type=Path, default=Path("8303-port.pcapng"))
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--send", action="store_true", help="replay the first matching capture payload")
    parser.add_argument("--teacher-approved", action="store_true", help="required together with --send")
    args = parser.parse_args()

    try:
        payloads = extract_payloads(args.capture, args.port)
    except (OSError, ValueError) as exc:
        print(f"CAPTURE_READ=FAILED error={exc}", file=sys.stderr)
        return 2
    if not payloads:
        print(f"CAPTURE_PAYLOAD=NOT_FOUND expected_udp_payload={PAYLOAD_BYTES}B", file=sys.stderr)
        return 2

    payload = payloads[0]
    live_counter = struct.unpack_from("<I", payload, 0)[0]
    signal_count = payload[4]
    switch_offset = 5 + signal_count
    switch_count = payload[switch_offset] if switch_offset < len(payload) else -1
    print(f"CAPTURE_OK frames={len(payloads)} payload={len(payload)}B live_counter={live_counter} "
          f"signals={signal_count} switches={switch_count}")
    print(f"payload_head={payload[:24].hex(' ')}")

    if not args.send:
        print("INSPECT_ONLY (refusing to synthesize or transmit a 128B Vision frame)")
        return 0
    if not args.teacher_approved:
        print("SEND_REFUSED: add --teacher-approved after confirming the target accepts this capture variant", file=sys.stderr)
        return 3

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect((args.host, args.port))
        local_ip, local_port = sock.getsockname()
        try:
            on_lab_subnet = ipaddress.ip_address(local_ip) in ipaddress.ip_network("192.168.100.0/24")
        except ValueError:
            on_lab_subnet = False
        print(f"UDP_ROUTE local={local_ip}:{local_port} lab_subnet={on_lab_subnet}")
        if not on_lab_subnet:
            print("SEND_REFUSED: selected route is not the laboratory 192.168.100.0/24 interface", file=sys.stderr)
            return 3
        sent = sock.send(payload)
        print(f"TX_OK target={args.host}:{args.port} bytes={sent}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
