#!/usr/bin/env python3
"""Read-only capture and offline replay tool for the 704 PLC input stream.

Safety contract:
  * Live mode only calls socket.connect() and socket.recv().
  * This program contains no socket.send(), sendall(), or output-device code.
  * Run it by itself. Do not run it while the backend Protocol704 connection is active.

Examples:
  python scripts/plc704_capture.py --self-test
  python scripts/plc704_capture.py --replay hil-704-capture-1783844035066-1783844174581/records.jsonl
  python scripts/plc704_capture.py --duration 12 --label baseline
  python scripts/plc704_capture.py --interactive-label --reconnect
"""

from __future__ import annotations

import argparse
import csv
import json
import queue
import socket
import sys
import threading
import time
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


DEFAULT_HOST = "192.168.100.123"
DEFAULT_PORT = 8001
FRAME_LENGTH = 46
MAGIC = bytes((0x55, 0xAA, 0x55, 0xAA))
READ_TIMEOUT_SECONDS = 1.0
CONNECT_TIMEOUT_SECONDS = 3.0


FIELD_LABELS = {
    "key_switch_on": "key_switch_on",
    "direction_handle": "direction_handle",
    "master_handle": "master_handle",
    "traction_percent": "traction_percent",
    "brake_percent": "brake_percent",
    "emergency_brake_pressed": "emergency_brake_pressed",
    "door_open_left": "door_open_left",
    "door_open_right": "door_open_right",
    "door_close_left": "door_close_left",
    "door_close_right": "door_close_right",
    "mode_upgrade_confirm": "mode_upgrade_confirm",
    "mode_downgrade_confirm": "mode_downgrade_confirm",
    "confirm_pressed": "confirm_pressed",
    "ato_start_pressed": "ato_start_pressed",
    "ar_button_pressed": "ar_button_pressed",
    "external_light_switch": "external_light_switch",
    "door_mode_switch": "door_mode_switch",
    "doors_closed_ok": "doors_closed_ok",
    "ato_mode_available": "ato_mode_available",
    "ato_mode_active": "ato_mode_active",
    "plc_speed_raw_word": "plc_speed_raw_word",
}

CHANGE_FIELDS = tuple(FIELD_LABELS)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def local_now() -> str:
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def u16le(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8)


def find_frames(buffer: bytearray) -> Tuple[List[bytes], bytearray]:
    """Extract valid 46-byte PLC frames while retaining an incomplete tail."""
    frames: List[bytes] = []
    offset = 0
    while len(buffer) - offset >= len(MAGIC):
        start = buffer.find(MAGIC, offset)
        if start < 0:
            keep = min(len(MAGIC) - 1, len(buffer))
            return frames, buffer[-keep:] if keep else bytearray()
        if len(buffer) - start < FRAME_LENGTH:
            return frames, buffer[start:]
        if u16le(buffer, start + 4) != FRAME_LENGTH or u16le(buffer, start + 6) != 22:
            offset = start + 1
            continue
        frames.append(bytes(buffer[start : start + FRAME_LENGTH]))
        offset = start + FRAME_LENGTH
    return frames, buffer[offset:]


def bool_bit(value: int, bit: int) -> bool:
    return (value & bit) != 0


def decode_frame(frame: bytes) -> Dict[str, Any]:
    """Decode positions observed in the teacher PLC document and real capture."""
    if len(frame) != FRAME_LENGTH:
        raise ValueError(f"expected {FRAME_LENGTH} bytes, got {len(frame)}")
    if frame[:4] != MAGIC or u16le(frame, 4) != FRAME_LENGTH or u16le(frame, 6) != 22:
        raise ValueError("invalid 704 frame header or length fields")

    byte24, byte25 = frame[24], frame[25]
    byte28, byte29 = frame[28], frame[29]
    byte34, byte35 = frame[34], frame[35]
    direction = u16le(frame, 36)
    master = u16le(frame, 38)

    return {
        "plc_time": "%04d-%02d-%02d %02d:%02d:%02d" % (
            u16le(frame, 8), u16le(frame, 10), u16le(frame, 12),
            u16le(frame, 14), u16le(frame, 16), u16le(frame, 18),
        ),
        "verify_type": u16le(frame, 20),
        "verify_code": u16le(frame, 22),
        "doors_closed_ok": bool_bit(byte24, 0x20),
        "network_fault": bool_bit(byte24, 0x40),
        "ato_mode_available": bool_bit(byte25, 0x01),
        "ato_mode_active": bool_bit(byte25, 0x04),
        "plc_speed_raw_word": u16le(frame, 26),
        "emergency_brake_pressed": bool_bit(byte28, 0x01),
        "bus_control_pressed": bool_bit(byte28, 0x02),
        "emergency_command_pressed": bool_bit(byte28, 0x10),
        "door_open_left": bool_bit(byte29, 0x01),
        "door_open_right": bool_bit(byte29, 0x02),
        "door_close_left": bool_bit(byte29, 0x04),
        "door_close_right": bool_bit(byte29, 0x08),
        "external_light_switch": u16le(frame, 30),
        "door_mode_switch": u16le(frame, 32),
        "high_accel_pressed": bool_bit(byte34, 0x01),
        "mode_upgrade_confirm": bool_bit(byte34, 0x04),
        "mode_downgrade_confirm": bool_bit(byte34, 0x08),
        "confirm_pressed": bool_bit(byte34, 0x10),
        "ar_button_pressed": bool_bit(byte34, 0x20),
        "traction_assist_reset_pressed": bool_bit(byte34, 0x40),
        "ato_start_pressed": bool_bit(byte34, 0x80),
        "wash_mode_switch": bool_bit(byte35, 0x01),
        "key_switch_on": bool_bit(byte35, 0x02),
        "vigilance_pressed": bool_bit(byte35, 0x04),
        "direction_handle": direction,
        "direction_name": {0: "ZERO", 1: "FORWARD", 2: "REVERSE"}.get(direction, f"UNKNOWN({direction})"),
        "master_handle": master,
        "master_name": {0: "COAST", 1: "TRACTION", 2: "BRAKE", 4: "EMERGENCY_BRAKE"}.get(master, f"UNKNOWN({master})"),
        "traction_percent": u16le(frame, 40),
        "brake_percent": u16le(frame, 42),
    }


def raw_hex(frame: bytes) -> str:
    return frame.hex(" ")


def changes(previous: Optional[Dict[str, Any]], current: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    if previous is None:
        return {}
    return {
        name: {"from": previous.get(name), "to": current.get(name)}
        for name in CHANGE_FIELDS
        if previous.get(name) != current.get(name)
    }


def short_summary(decoded: Dict[str, Any]) -> str:
    return (
        f"key={int(decoded['key_switch_on'])} dir={decoded['direction_name']} "
        f"master={decoded['master_name']} trac={decoded['traction_percent']}% "
        f"brake={decoded['brake_percent']}% eb={int(decoded['emergency_brake_pressed'])}"
    )


@dataclass
class CaptureStats:
    source: str
    started_at: str
    host: Optional[str] = None
    port: Optional[int] = None
    bytes_received: int = 0
    frames_valid: int = 0
    invalid_chunks: int = 0
    reconnects: int = 0
    first_frame_at: Optional[str] = None
    last_frame_at: Optional[str] = None
    frame_intervals_ms: List[float] = None  # type: ignore[assignment]
    changed_fields: Counter = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        self.frame_intervals_ms = []
        self.changed_fields = Counter()


class CaptureWriter:
    def __init__(self, output_dir: Path, stats: CaptureStats, start_label: Optional[str]) -> None:
        output_dir.mkdir(parents=True, exist_ok=False)
        self.output_dir = output_dir
        self.stats = stats
        self.records = (output_dir / "records.jsonl").open("w", encoding="utf-8", newline="\n")
        self.events = (output_dir / "events.jsonl").open("w", encoding="utf-8", newline="\n")
        self.csv_file = (output_dir / "frames.csv").open("w", encoding="utf-8", newline="")
        self.csv = csv.DictWriter(self.csv_file, fieldnames=[
            "captured_at", "source", "frame_index", "plc_time", "key_switch_on",
            "direction_handle", "direction_name", "master_handle", "master_name",
            "traction_percent", "brake_percent", "emergency_brake_pressed",
            "door_open_left", "door_open_right", "door_close_left", "door_close_right",
            "mode_upgrade_confirm", "mode_downgrade_confirm", "confirm_pressed",
            "ato_start_pressed", "plc_speed_raw_word", "interval_ms", "raw_hex",
        ])
        self.csv.writeheader()
        self.previous: Optional[Dict[str, Any]] = None
        self.previous_frame_monotonic: Optional[float] = None
        self.frame_index = 0
        self.closed = False
        self.write_event("capture_started", {"label": start_label, "source": stats.source})

    def write_record(self, captured_at: str, frame: bytes, decoded: Dict[str, Any], source: str,
                     observed_interval_ms: Optional[float] = None) -> Dict[str, Dict[str, Any]]:
        self.frame_index += 1
        interval_ms = observed_interval_ms
        if interval_ms is None:
            now_monotonic = time.monotonic()
            if self.previous_frame_monotonic is not None:
                interval_ms = round((now_monotonic - self.previous_frame_monotonic) * 1000.0, 3)
            self.previous_frame_monotonic = now_monotonic
        if interval_ms is not None:
            self.stats.frame_intervals_ms.append(interval_ms)
        delta = changes(self.previous, decoded)
        self.previous = dict(decoded)
        self.stats.frames_valid += 1
        self.stats.first_frame_at = self.stats.first_frame_at or captured_at
        self.stats.last_frame_at = captured_at
        self.stats.changed_fields.update(delta.keys())

        record = {
            "record_type": "frame",
            "captured_at": captured_at,
            "source": source,
            "frame_index": self.frame_index,
            "interval_ms": interval_ms,
            "size_bytes": len(frame),
            "raw_hex": raw_hex(frame),
            "decoded": decoded,
        }
        self.records.write(json.dumps(record, ensure_ascii=True) + "\n")
        self.csv.writerow({
            "captured_at": captured_at,
            "source": source,
            "frame_index": self.frame_index,
            "raw_hex": record["raw_hex"],
            "interval_ms": interval_ms,
            **{key: decoded.get(key) for key in self.csv.fieldnames if key in decoded},
        })
        if delta:
            self.write_event("field_change", {
                "frame_index": self.frame_index,
                "changes": delta,
                "summary": short_summary(decoded),
            }, captured_at)
        self.records.flush()
        self.events.flush()
        self.csv_file.flush()
        return delta

    def write_event(self, event_type: str, payload: Dict[str, Any], captured_at: Optional[str] = None) -> None:
        event = {
            "record_type": "event",
            "captured_at": captured_at or utc_now(),
            "event_type": event_type,
            **payload,
        }
        self.records.write(json.dumps(event, ensure_ascii=True) + "\n")
        self.events.write(json.dumps(event, ensure_ascii=True) + "\n")
        self.records.flush()
        self.events.flush()

    def close(self, error: Optional[str] = None) -> None:
        if self.closed:
            return
        self.closed = True
        self.write_event("capture_stopped", {"error": error})
        self.records.close()
        self.events.close()
        self.csv_file.close()
        intervals = self.stats.frame_intervals_ms
        summary = {
            "safety": "read-only; no PLC or screen write is performed by this tool",
            "source": self.stats.source,
            "host": self.stats.host,
            "port": self.stats.port,
            "started_at": self.stats.started_at,
            "stopped_at": utc_now(),
            "bytes_received": self.stats.bytes_received,
            "frames_valid": self.stats.frames_valid,
            "invalid_chunks": self.stats.invalid_chunks,
            "reconnects": self.stats.reconnects,
            "first_frame_at": self.stats.first_frame_at,
            "last_frame_at": self.stats.last_frame_at,
            "frame_interval_ms": {
                "count": len(intervals),
                "min": min(intervals) if intervals else None,
                "max": max(intervals) if intervals else None,
                "average": round(sum(intervals) / len(intervals), 3) if intervals else None,
            },
            "changed_field_counts": dict(self.stats.changed_fields),
            "error": error,
        }
        (self.output_dir / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=True, indent=2) + "\n", encoding="utf-8"
        )


def make_output_dir(raw: Optional[str], prefix: str) -> Path:
    if raw:
        return Path(raw)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return Path("captures") / f"{prefix}-{stamp}"


def start_label_thread(labels: "queue.Queue[str]") -> None:
    def read_labels() -> None:
        while True:
            try:
                line = input()
            except (EOFError, KeyboardInterrupt):
                return
            label = line.strip()
            if label:
                labels.put(label)

    threading.Thread(target=read_labels, name="capture-label-input", daemon=True).start()


def drain_labels(labels: "queue.Queue[str]", writer: CaptureWriter) -> None:
    while True:
        try:
            label = labels.get_nowait()
        except queue.Empty:
            return
        writer.write_event("operator_label", {"label": label})
        print(f"[{local_now()}] LABEL {label}", flush=True)


def run_live(args: argparse.Namespace, writer: CaptureWriter, labels: "queue.Queue[str]") -> None:
    deadline = time.monotonic() + args.duration if args.duration > 0 else None
    pending = bytearray()
    stop_reason: Optional[str] = None
    try:
        while deadline is None or time.monotonic() < deadline:
            drain_labels(labels, writer)
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(CONNECT_TIMEOUT_SECONDS)
            try:
                print(f"[{local_now()}] READ-ONLY connect {args.host}:{args.port}", flush=True)
                sock.connect((args.host, args.port))
                sock.settimeout(READ_TIMEOUT_SECONDS)
                print(f"[{local_now()}] connected; receiving only", flush=True)
                writer.write_event("connected", {"peer": f"{args.host}:{args.port}"})
                pending.clear()
                while deadline is None or time.monotonic() < deadline:
                    drain_labels(labels, writer)
                    try:
                        chunk = sock.recv(4096)
                    except socket.timeout:
                        continue
                    if not chunk:
                        raise ConnectionError("remote closed TCP connection")
                    writer.stats.bytes_received += len(chunk)
                    pending.extend(chunk)
                    frames, pending = find_frames(pending)
                    if not frames:
                        writer.stats.invalid_chunks += 1
                    for frame in frames:
                        captured_at = utc_now()
                        decoded = decode_frame(frame)
                        delta = writer.write_record(captured_at, frame, decoded, "live")
                        if writer.stats.frames_valid == 1 or delta:
                            changed = ", ".join(delta) if delta else "first_frame"
                            print(f"[{local_now()}] frame={writer.stats.frames_valid} {short_summary(decoded)} change={changed}", flush=True)
            except (OSError, ConnectionError) as exc:
                writer.write_event("connection_error", {"message": f"{type(exc).__name__}: {exc}"})
                print(f"[{local_now()}] connection error: {exc}", flush=True)
                if not args.reconnect:
                    raise
                writer.stats.reconnects += 1
                if deadline is None or time.monotonic() < deadline:
                    time.sleep(args.reconnect_delay)
            finally:
                sock.close()
    except KeyboardInterrupt:
        stop_reason = "operator interrupted capture"
        print(f"[{local_now()}] stopped by operator", flush=True)
    finally:
        drain_labels(labels, writer)
        writer.close(stop_reason)


def source_timestamp(packet: Dict[str, Any]) -> str:
    captured_at_ms = packet.get("captured_at_unix_ms")
    if isinstance(captured_at_ms, (int, float)):
        return datetime.fromtimestamp(captured_at_ms / 1000.0, tz=timezone.utc).isoformat(timespec="milliseconds")
    return utc_now()


def frames_from_replay(path: Path) -> Iterable[Tuple[bytes, str, Optional[float]]]:
    if not path.is_file():
        raise FileNotFoundError(path)
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON at {path}:{line_no}: {exc}") from exc
        packet = record.get("packet", record)
        if packet.get("channel") not in (None, "plc"):
            continue
        if packet.get("direction") not in (None, "RX", "rx", "inbound"):
            continue
        hex_value = packet.get("raw_hex")
        if not isinstance(hex_value, str):
            continue
        frame = bytes.fromhex(hex_value)
        if len(frame) == FRAME_LENGTH and frame.startswith(MAGIC):
            interval = packet.get("interval_ms")
            yield frame, source_timestamp(packet), float(interval) if isinstance(interval, (int, float)) else None


def run_replay(args: argparse.Namespace, writer: CaptureWriter) -> None:
    try:
        for index, (frame, captured_at, interval_ms) in enumerate(frames_from_replay(Path(args.replay)), start=1):
            if args.max_frames and index > args.max_frames:
                break
            decoded = decode_frame(frame)
            delta = writer.write_record(captured_at, frame, decoded, "replay", interval_ms)
            if index == 1 or delta:
                print(f"[{local_now()}] replay frame={index} {short_summary(decoded)}", flush=True)
        if writer.stats.frames_valid == 0:
            raise ValueError("no valid PLC RX 46B frames found in replay input")
    finally:
        writer.close()


def run_self_test() -> int:
    sample = bytes.fromhex(
        "55 aa 55 aa 2e 00 16 00 ea 07 07 00 0c 00 10 00 "
        "17 00 24 00 01 00 c8 9e 00 00 00 00 00 00 00 00 "
        "00 00 00 02 01 00 01 00 64 00 00 00 00 00"
    )
    fragments = bytearray(b"noise" + sample[:9])
    frames, fragments = find_frames(fragments)
    assert frames == [] and fragments == bytearray(sample[:9])
    fragments.extend(sample[9:] + sample)
    frames, fragments = find_frames(fragments)
    assert len(frames) == 2 and not fragments
    decoded = decode_frame(frames[0])
    assert decoded["key_switch_on"] is True
    assert decoded["direction_name"] == "FORWARD"
    assert decoded["master_name"] == "TRACTION"
    assert decoded["traction_percent"] == 100
    assert decoded["brake_percent"] == 0
    print("SELF-TEST PASS: real 46B capture shape parsed; no network connection was attempted.")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Read-only 704 PLC capture and replay")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--replay", metavar="RECORDS_JSONL", help="analyse PLC RX frames from an existing JSONL capture")
    mode.add_argument("--self-test", action="store_true", help="offline parser/stream-framing test; makes no network connection")
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"PLC host, default {DEFAULT_HOST}")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"PLC TCP port, default {DEFAULT_PORT}")
    parser.add_argument("--duration", type=float, default=0.0, help="live capture seconds; 0 means until Ctrl+C")
    parser.add_argument("--reconnect", action="store_true", help="retry a dropped live connection")
    parser.add_argument("--reconnect-delay", type=float, default=2.0, help="seconds between live reconnect attempts")
    parser.add_argument("--label", help="initial operator label, for example baseline or key_on")
    parser.add_argument("--interactive-label", action="store_true", help="type a label and press Enter while capture is running")
    parser.add_argument("--output", help="new output directory; default is captures/plc704-<timestamp>")
    parser.add_argument("--max-frames", type=int, default=0, help="cap replay frames; 0 means all")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        return run_self_test()
    if args.port < 1 or args.port > 65535:
        print("ERROR: --port must be in 1..65535", file=sys.stderr)
        return 2
    if args.duration < 0 or args.reconnect_delay < 0:
        print("ERROR: duration and reconnect delay must be non-negative", file=sys.stderr)
        return 2

    source = "replay" if args.replay else "live"
    stats = CaptureStats(source=source, started_at=utc_now(), host=args.host if not args.replay else None,
                         port=args.port if not args.replay else None)
    output_dir = make_output_dir(args.output, "plc704-replay" if args.replay else "plc704-capture")
    try:
        writer = CaptureWriter(output_dir, stats, args.label)
    except FileExistsError:
        print(f"ERROR: output directory already exists: {output_dir}", file=sys.stderr)
        return 2

    print("=" * 72)
    print("704 PLC CAPTURE - READ ONLY")
    print("This tool never writes TCP data. Do not run it with backend 704 Connect.")
    print(f"Output: {output_dir}")
    if args.interactive_label and not args.replay:
        print("Type an operation label then press Enter, for example: key_on or traction_20.")
    print("=" * 72)
    try:
        if args.replay:
            run_replay(args, writer)
        else:
            labels: "queue.Queue[str]" = queue.Queue()
            if args.interactive_label:
                start_label_thread(labels)
            run_live(args, writer, labels)
    except Exception as exc:
        writer.close(f"{type(exc).__name__}: {exc}")
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    print(f"Saved {stats.frames_valid} valid frames to {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
