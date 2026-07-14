#!/usr/bin/env python3
"""Capture and decode the current Vision UDP 8303 exchange.

This script captures new traffic only. It uses Windows PktMon as the packet
capture driver, then parses the resulting pcapng with the Python standard
library. No Wireshark, tshark, scapy, or existing capture file is required.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import shutil
import struct
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


VISION_PORT = 8303
EXPECTED_BYTES = 128
EXPECTED_SIGNALS = 77
EXPECTED_SWITCHES = 29
REPO_ROOT = Path(__file__).resolve().parents[1]


def run(command: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=check, text=True, capture_output=True)


def require_windows_admin() -> None:
    if sys.platform != "win32":
        raise RuntimeError("live capture requires Windows PktMon")
    if not ctypes.windll.shell32.IsUserAnAdmin():
        raise PermissionError("open PowerShell as Administrator and run this script again")
    if shutil.which("pktmon") is None:
        raise RuntimeError("Windows PktMon was not found")


def configure_capture_filter() -> None:
    run(["pktmon", "filter", "remove"], check=False)
    run(["pktmon", "filter", "add", "Vision8303", "-t", "UDP", "-p", str(VISION_PORT)])


def udp_payload_from_ethernet(packet: bytes) -> tuple[str, str, int, int, bytes] | None:
    if len(packet) < 42:
        return None
    ether_type = struct.unpack_from("!H", packet, 12)[0]
    ip_offset = 14
    while ether_type in (0x8100, 0x88A8) and len(packet) >= ip_offset + 4:
        ether_type = struct.unpack_from("!H", packet, ip_offset + 2)[0]
        ip_offset += 4
    if ether_type != 0x0800 or len(packet) < ip_offset + 20:
        return None
    version_ihl = packet[ip_offset]
    if version_ihl >> 4 != 4:
        return None
    ihl = (version_ihl & 0x0F) * 4
    if ihl < 20 or len(packet) < ip_offset + ihl + 8 or packet[ip_offset + 9] != 17:
        return None
    source_ip = ".".join(str(value) for value in packet[ip_offset + 12:ip_offset + 16])
    destination_ip = ".".join(str(value) for value in packet[ip_offset + 16:ip_offset + 20])
    udp_offset = ip_offset + ihl
    source_port, destination_port, udp_length = struct.unpack_from("!HHH", packet, udp_offset)
    if source_port != VISION_PORT and destination_port != VISION_PORT:
        return None
    if udp_length < 8 or udp_offset + udp_length > len(packet):
        return None
    return (
        source_ip,
        destination_ip,
        source_port,
        destination_port,
        packet[udp_offset + 8:udp_offset + udp_length],
    )


def pcapng_packets(path: Path) -> list[bytes]:
    raw = path.read_bytes()
    packets: list[bytes] = []
    offset = 0
    endian = "<"
    while offset + 12 <= len(raw):
        block_type_le, block_length_le = struct.unpack_from("<II", raw, offset)
        if block_type_le == 0x0A0D0D0A:
            byte_order_magic = raw[offset + 8:offset + 12]
            if byte_order_magic == b"\x4d\x3c\x2b\x1a":
                endian = "<"
            elif byte_order_magic == b"\x1a\x2b\x3c\x4d":
                endian = ">"
            else:
                raise ValueError(f"invalid pcapng byte-order magic at offset {offset}")
        block_type, block_length = struct.unpack_from(endian + "II", raw, offset)
        if block_length < 12 or offset + block_length > len(raw):
            raise ValueError(f"invalid pcapng block at offset {offset}")
        if block_type == 0x00000006 and block_length >= 32:
            captured_length = struct.unpack_from(endian + "I", raw, offset + 20)[0]
            packet_start = offset + 28
            packet_end = packet_start + captured_length
            if packet_end <= offset + block_length - 4:
                packets.append(raw[packet_start:packet_end])
        offset += block_length
    return packets


def decode_vision(payload: bytes) -> dict[str, Any]:
    result: dict[str, Any] = {
        "length": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
        "rawHex": payload.hex(" "),
    }
    if len(payload) < 6:
        result["decodeError"] = "payload shorter than Vision header"
        return result
    counter = struct.unpack_from("<I", payload, 0)[0]
    signal_count = payload[4]
    switch_count_offset = 5 + signal_count
    if switch_count_offset >= len(payload):
        result.update({"liveCounter": counter, "signalCount": signal_count,
                       "decodeError": "signal table exceeds payload"})
        return result
    switch_count = payload[switch_count_offset]
    vehicle_offset = switch_count_offset + 1 + switch_count
    expected_length = vehicle_offset + 16
    result.update({
        "liveCounter": counter,
        "signalCount": signal_count,
        "switchCount": switch_count,
        "expectedLengthFromCounts": expected_length,
    })
    if expected_length > len(payload):
        result["decodeError"] = "switch/vehicle fields exceed payload"
        return result
    speed_mm_s = struct.unpack_from("<i", payload, vehicle_offset)[0]
    run_state = payload[vehicle_offset + 6]
    acceleration_percent = struct.unpack_from("<b", payload, vehicle_offset + 7)[0]
    position_mm = struct.unpack_from("<i", payload, vehicle_offset + 8)[0]
    edge_id = struct.unpack_from("<H", payload, vehicle_offset + 12)[0]
    direction = struct.unpack_from("<b", payload, vehicle_offset + 14)[0]
    other_train_count = payload[vehicle_offset + 15]
    result.update({
        "speedMmS": speed_mm_s,
        "speedKmh": speed_mm_s / 1000.0 * 3.6,
        "runState": run_state,
        "accelerationPercent": acceleration_percent,
        "positionMm": position_mm,
        "positionM": position_mm / 1000.0,
        "edgeId": edge_id,
        "direction": direction,
        "otherTrainCount": other_train_count,
        "strict128": len(payload) == EXPECTED_BYTES
            and signal_count == EXPECTED_SIGNALS
            and switch_count == EXPECTED_SWITCHES
            and expected_length == EXPECTED_BYTES,
    })
    return result


def analyze_capture(pcap: Path, output: Path) -> dict[str, Any]:
    decoded: list[dict[str, Any]] = []
    seen: set[tuple[str, str, int, int, str]] = set()
    for packet in pcapng_packets(pcap):
        udp = udp_payload_from_ethernet(packet)
        if udp is None:
            continue
        source_ip, destination_ip, source_port, destination_port, payload = udp
        digest = hashlib.sha256(payload).hexdigest()
        key = (source_ip, destination_ip, source_port, destination_port, digest)
        if key in seen:
            continue
        seen.add(key)
        frame = decode_vision(payload)
        frame.update({
            "frameIndex": len(decoded) + 1,
            "source": f"{source_ip}:{source_port}",
            "destination": f"{destination_ip}:{destination_port}",
        })
        decoded.append(frame)

    frames_path = output / "vision-frames.jsonl"
    with frames_path.open("w", encoding="utf-8", newline="\n") as stream:
        for frame in decoded:
            stream.write(json.dumps(frame, ensure_ascii=False) + "\n")

    lengths = sorted({frame["length"] for frame in decoded})
    positions = [frame["positionM"] for frame in decoded if "positionM" in frame]
    speeds = [frame["speedKmh"] for frame in decoded if "speedKmh" in frame]
    edges = sorted({frame["edgeId"] for frame in decoded if "edgeId" in frame})
    counters = [frame["liveCounter"] for frame in decoded if "liveCounter" in frame]
    strict_count = sum(1 for frame in decoded if frame.get("strict128"))
    motion_changed = bool(positions) and (
        max(positions) - min(positions) >= 0.5
        or (speeds and max(speeds) - min(speeds) >= 0.2)
        or len(edges) > 1
    )
    summary = {
        "pcapng": str(pcap.resolve()),
        "framesJsonl": str(frames_path.resolve()),
        "uniqueUdpFrames": len(decoded),
        "payloadLengths": lengths,
        "strict128Frames": strict_count,
        "allFramesStrict128": bool(decoded) and strict_count == len(decoded),
        "firstCounter": counters[0] if counters else None,
        "lastCounter": counters[-1] if counters else None,
        "positionMinM": min(positions) if positions else None,
        "positionMaxM": max(positions) if positions else None,
        "speedMinKmh": min(speeds) if speeds else None,
        "speedMaxKmh": max(speeds) if speeds else None,
        "edgeIds": edges,
        "motionFieldsChanged": motion_changed,
    }
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description="Capture current Vision UDP 8303 and decode 128B frames")
    parser.add_argument("--duration", type=float, default=15.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.duration <= 0:
        parser.error("--duration must be positive")

    try:
        require_windows_admin()
    except (OSError, RuntimeError, PermissionError) as exc:
        print(f"CAPTURE_FAILED error={exc}", file=sys.stderr)
        return 2

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    output = args.output or REPO_ROOT / "captures" / f"vision-live-{stamp}"
    output.mkdir(parents=True, exist_ok=False)
    etl = output / "vision.etl"
    pcap = output / "vision.pcapng"
    pktmon_started = False

    try:
        configure_capture_filter()
        run([
            "pktmon", "start", "--capture", "--comp", "nics", "--pkt-size", "0",
            "--file-name", str(etl.resolve()), "--file-size", "128", "--log-mode", "circular",
        ])
        pktmon_started = True
        print(f"CAPTURE_STARTED udp_port={VISION_PORT} duration={args.duration:g}s output={output}")
        deadline = time.monotonic() + args.duration
        while time.monotonic() < deadline:
            remaining = max(0.0, deadline - time.monotonic())
            print(f"CAPTURING remaining={remaining:5.1f}s", end="\r", flush=True)
            time.sleep(min(1.0, remaining))
        print()
    except KeyboardInterrupt:
        print("\nCAPTURE_INTERRUPTED saving collected packets")
    except (OSError, RuntimeError, PermissionError, subprocess.CalledProcessError) as exc:
        print(f"CAPTURE_FAILED error={exc}", file=sys.stderr)
        return 2
    finally:
        if pktmon_started:
            run(["pktmon", "stop"], check=False)
        run(["pktmon", "filter", "remove"], check=False)

    conversion = run(["pktmon", "etl2pcap", str(etl), "--out", str(pcap)], check=False)
    if conversion.returncode != 0 or not pcap.exists():
        print(f"CONVERT_FAILED {(conversion.stderr or conversion.stdout).strip()}", file=sys.stderr)
        return 2
    try:
        summary = analyze_capture(pcap, output)
    except (OSError, ValueError, struct.error) as exc:
        print(f"DECODE_FAILED error={exc}", file=sys.stderr)
        return 2

    print(
        f"VISION_CAPTURE frames={summary['uniqueUdpFrames']} lengths={summary['payloadLengths']} "
        f"strict128={summary['strict128Frames']} motionChanged={summary['motionFieldsChanged']}"
    )
    if summary["uniqueUdpFrames"] == 0:
        print("NO_UDP_8303: backend did not reach a captured NIC; check HIL context, route, and cable")
        return 3
    if not summary["allFramesStrict128"]:
        print("FRAME_FORMAT=FAIL expected every frame to be 128B with 77/29")
        return 4
    if not summary["motionFieldsChanged"]:
        print("MOTION_SYNC=FAIL position/speed/edge stayed constant during this capture")
        return 5
    print("VISION_RUNTIME=PASS strict 128B and motion fields changed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
