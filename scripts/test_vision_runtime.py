#!/usr/bin/env python3
"""Read-only monitor for the backend-owned 128-byte Vision running process.

The script never opens a laboratory device socket. It polls the local backend,
checks the Vision byte/frame delta, and verifies that position, speed and edge
change with the authoritative LB train. With --until-next-depart it also times
the stop-to-next-depart interval and observes the automatic door cycle.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime


VISION_FRAME_BYTES = 128
STOPPED_MPS = 0.05
DEPARTED_MPS = 0.20


def api_data(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=3.0) as response:
        payload = json.load(response)
    if isinstance(payload, dict) and "data" in payload:
        return payload["data"]
    return payload


def stable_hil_status(url: str) -> dict:
    """Avoid reading counters while the 100 ms sender updates them."""
    previous = api_data(url)
    for _ in range(20):
        current = api_data(url)
        before = previous.get("vision") or {}
        after = current.get("vision") or {}
        if (before.get("framesSent"), before.get("bytesSent")) == (
            after.get("framesSent"), after.get("bytesSent")
        ):
            return current
        previous = current
    return previous


@dataclass
class Sample:
    monotonic: float
    frames: int
    bytes_sent: int
    position_m: float
    speed_mps: float
    edge_id: int
    current_station: int
    next_station: int
    doors_closed: bool
    ato_active: bool
    emergency: bool
    workflow: str
    door_state: str


def read_sample(backend: str, train_id: str, stable: bool = False) -> Sample:
    hil_url = f"{backend}/api/hil/status"
    hil = stable_hil_status(hil_url) if stable else api_data(hil_url)
    vision = hil.get("vision") or {}
    vehicle = hil.get("vehicleSnapshot") or {}
    workflow = "UNKNOWN"
    door_state = "UNKNOWN"
    emergency = bool(vehicle.get("emergencyBrake", False))
    try:
        encoded = urllib.parse.quote(train_id, safe="")
        driver = api_data(f"{backend}/api/signal/driver-workflow?trainId={encoded}")
        workflow = str(driver.get("workflow", "UNKNOWN"))
        control = driver.get("control") or {}
        door_state = str(control.get("doorState", "UNKNOWN"))
        emergency = emergency or bool(control.get("emergencyLatched", False))
    except (OSError, ValueError, KeyError):
        pass
    return Sample(
        monotonic=time.monotonic(),
        frames=int(vision.get("framesSent", 0)),
        bytes_sent=int(vision.get("bytesSent", 0)),
        position_m=float(vehicle.get("positionM", 0.0)),
        speed_mps=float(vehicle.get("speedMps", 0.0)),
        edge_id=int(vehicle.get("visionEdgeId", 0)),
        current_station=int(vehicle.get("currentStationId", 0)),
        next_station=int(vehicle.get("nextStationId", 0)),
        doors_closed=bool(vehicle.get("doorsClosed", True)),
        ato_active=bool(vehicle.get("atoActive", False)),
        emergency=emergency,
        workflow=workflow,
        door_state=door_state,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Monitor Vision 128B frames and LB motion")
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--train-id", default="LB")
    parser.add_argument("--duration", type=float, default=30.0, help="maximum seconds to monitor")
    parser.add_argument("--interval", type=float, default=0.5)
    parser.add_argument(
        "--until-next-depart",
        action="store_true",
        help="wait for moving -> stopped -> moving and report the automatic dwell time",
    )
    parser.add_argument(
        "--allow-stationary",
        action="store_true",
        help="pass frame validation even when no train movement is observed",
    )
    args = parser.parse_args()
    backend = args.backend.rstrip("/")

    if args.duration <= 0 or args.interval <= 0:
        parser.error("--duration and --interval must be positive")

    try:
        initial_hil = stable_hil_status(f"{backend}/api/hil/status")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"BACKEND=FAILED error={exc}", file=sys.stderr)
        return 2
    vision = initial_hil.get("vision") or {}
    if not initial_hil.get("enabled") or not vision.get("enabled"):
        print("VISION=DISABLED start the platform with -Lab", file=sys.stderr)
        return 2

    print("time     frames      bytes   pos_m  speed_kmh edge station next doors ato workflow")
    samples: list[Sample] = []
    stop_at: float | None = None
    depart_at: float | None = None
    seen_moving = False
    deadline = time.monotonic() + args.duration

    while time.monotonic() < deadline:
        try:
            sample = read_sample(backend, args.train_id)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print(f"SAMPLE=FAILED error={exc}", file=sys.stderr)
            return 2
        samples.append(sample)
        now = datetime.now().strftime("%H:%M:%S")
        doors = "closed" if sample.doors_closed else sample.door_state.lower()
        print(
            f"{now} {sample.frames:8d} {sample.bytes_sent:10d} "
            f"{sample.position_m:7.1f} {sample.speed_mps * 3.6:10.2f} "
            f"{sample.edge_id:4d} {sample.current_station:7d} {sample.next_station:4d} "
            f"{doors:>10s} {str(sample.ato_active):>5s} {sample.workflow}"
        )

        if sample.speed_mps > DEPARTED_MPS:
            if stop_at is not None:
                depart_at = sample.monotonic
                break
            seen_moving = True
        elif seen_moving and stop_at is None and sample.speed_mps <= STOPPED_MPS:
            stop_at = sample.monotonic
            print("EVENT=STOPPED waiting for automatic door cycle and new route/MA")

        time.sleep(args.interval)

    try:
        final = read_sample(backend, args.train_id, stable=True)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"FINAL_SAMPLE=FAILED error={exc}", file=sys.stderr)
        return 2
    if not samples or samples[-1].frames != final.frames:
        samples.append(final)

    first = samples[0]
    delta_frames = final.frames - first.frames
    delta_bytes = final.bytes_sent - first.bytes_sent
    frame_ok = delta_frames > 0 and delta_bytes == delta_frames * VISION_FRAME_BYTES
    positions = [sample.position_m for sample in samples]
    speeds = [sample.speed_mps for sample in samples]
    edges = {sample.edge_id for sample in samples}
    motion_ok = (max(positions) - min(positions) >= 0.5) or (max(speeds) - min(speeds) >= 0.2)
    emergency_seen = any(sample.emergency for sample in samples)

    print(
        f"VISION_FRAMES={'PASS' if frame_ok else 'FAIL'} delta_frames={delta_frames} "
        f"delta_bytes={delta_bytes} expected_bytes={delta_frames * VISION_FRAME_BYTES}"
    )
    print(
        f"VISION_MOTION={'PASS' if motion_ok else 'NOT_OBSERVED'} "
        f"position_delta={max(positions) - min(positions):.3f}m "
        f"speed_range={(max(speeds) - min(speeds)) * 3.6:.3f}km/h edges={sorted(edges)}"
    )
    if stop_at is not None and depart_at is not None:
        print(f"AUTO_NEXT_DEPART=PASS stopped_to_moving={depart_at - stop_at:.2f}s")
    elif args.until_next_depart:
        print("AUTO_NEXT_DEPART=NOT_OBSERVED within timeout")
    if emergency_seen:
        print("FAIL_SAFE=ATP_EMERGENCY_BRAKE observed; this run cannot verify normal station restart")

    if not frame_ok:
        return 3
    if args.until_next_depart and depart_at is None:
        return 4
    if not args.allow_stationary and not motion_ok:
        return 4
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
