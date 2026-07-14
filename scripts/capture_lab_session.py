#!/usr/bin/env python3
"""Passively capture and annotate a complete laboratory test session on Windows.

The script uses Windows PktMon and never opens a socket to a laboratory device,
so it can run beside the backend without taking ownership of a single-client
PLC port. Operator labels and backend API snapshots share wall-clock timestamps
with the generated pcapng file.
"""

from __future__ import annotations

import argparse
import csv
import ctypes
import json
import queue
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional, TextIO


REPO_ROOT = Path(__file__).resolve().parents[1]
DEVICES = (
    ("PLC", "192.168.100.123", "TCP", None),
    ("NetworkScreen", "192.168.100.121", "TCP", 8888),
    ("SignalScreen", "192.168.100.122", "TCP", 9999),
    ("Vision", "192.168.100.124", "UDP", 8303),
)
GUIDED_LABELS = {
    "1": "baseline_idle",
    "2": "key_switch_on",
    "3": "direction_forward",
    "4": "traction_low",
    "5": "master_handle_zero",
    "6": "ato_hold_start",
    "7": "ato_buttons_released",
    "8": "ato_running_observed",
    "9": "station_stopped_observed",
    "10": "door_open",
    "11": "door_close",
    "12": "next_ato_hold_start",
}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def local_now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def run(command: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=check, text=True, capture_output=True)


def require_admin() -> None:
    if not ctypes.windll.shell32.IsUserAnAdmin():
        raise PermissionError("PktMon requires an Administrator PowerShell")


def configure_filters() -> None:
    run(["pktmon", "filter", "remove"], check=False)
    for name, host, protocol, port in DEVICES:
        command = ["pktmon", "filter", "add", name, "-i", host, "-t", protocol]
        if port is not None:
            command.extend(["-p", str(port)])
        run(command)


def api_urls(api_base: str, train_id: str, hil_status_url: Optional[str]) -> dict[str, str]:
    base = api_base.rstrip("/")
    quoted_train_id = urllib.parse.quote(train_id, safe="")
    return {
        "protocol704": f"{base}/api/vehicle/protocol704/status?trainId={quoted_train_id}",
        "atoWorkflow": f"{base}/api/vehicle/protocol704/ato-workflow?trainId={quoted_train_id}",
        "driverWorkflow": f"{base}/api/signal/driver-workflow?trainId={quoted_train_id}",
        "hil": hil_status_url or f"{base}/api/hil/status",
    }


def fetch_json(url: str) -> dict[str, Any]:
    with urllib.request.urlopen(url, timeout=2) as response:
        return {
            "httpStatus": response.status,
            "body": json.loads(response.read().decode("utf-8")),
        }


def poll_runtime_status(
    urls: dict[str, str],
    output: Path,
    stop: threading.Event,
    interval: float,
    latest: dict[str, Any],
    latest_lock: threading.Lock,
) -> None:
    first = True
    with output.open("w", encoding="utf-8", newline="\n") as stream:
        while not stop.wait(0 if first else interval):
            first = False
            record: dict[str, Any] = {
                "capturedAtUtc": now_iso(),
                "capturedAtLocal": local_now_iso(),
                "checks": {},
            }
            for name, url in urls.items():
                try:
                    record["checks"][name] = fetch_json(url)
                except (OSError, ValueError, urllib.error.URLError) as exc:
                    record["checks"][name] = {
                        "error": f"{type(exc).__name__}: {exc}",
                        "url": url,
                    }
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")
            stream.flush()
            with latest_lock:
                latest.clear()
                latest.update(record)


def unwrap_api(check: Any) -> dict[str, Any]:
    if not isinstance(check, dict):
        return {}
    body = check.get("body")
    if not isinstance(body, dict):
        return {}
    data = body.get("data")
    return data if isinstance(data, dict) else body


def print_latest_status(latest: dict[str, Any], latest_lock: threading.Lock) -> None:
    with latest_lock:
        snapshot = dict(latest)
    if not snapshot:
        print("STATUS no API snapshot has been collected yet", flush=True)
        return
    checks = snapshot.get("checks", {})
    protocol = unwrap_api(checks.get("protocol704"))
    ato = unwrap_api(checks.get("atoWorkflow"))
    driver = unwrap_api(checks.get("driverWorkflow"))
    hil = unwrap_api(checks.get("hil"))
    command = protocol.get("lastMappedCommand")
    if isinstance(command, dict):
        command = command.get("command")
    lifecycle = protocol.get("lastCommandLifecycle")
    lifecycle_status = lifecycle.get("status") if isinstance(lifecycle, dict) else None
    rejection = lifecycle.get("rejectionReason") if isinstance(lifecycle, dict) else None
    workflow = driver.get("workflow") or ato.get("workflow")
    ato_ready = driver.get("atoReady")
    blocking = driver.get("atoReadinessBlockingReason") or ato.get("blockingReason")
    realtime = protocol.get("realtimeVehicleState")
    speed = realtime.get("velocityMs") if isinstance(realtime, dict) else None
    position = realtime.get("positionM") if isinstance(realtime, dict) else None
    print(
        "STATUS "
        f"plcConnected={protocol.get('connected')} validFrame={protocol.get('receivedValidFrame')} "
        f"command={command} lifecycle={lifecycle_status} rejection={rejection} "
        f"workflow={workflow} atoReady={ato_ready} blocking={blocking} "
        f"speedMps={speed} positionM={position} hilEnabled={hil.get('enabled')}",
        flush=True,
    )


def start_operator_input(commands: "queue.Queue[str]") -> None:
    def read_commands() -> None:
        while True:
            line = sys.stdin.readline()
            if line == "":
                return
            commands.put(line.strip())

    threading.Thread(target=read_commands, name="lab-capture-operator-input", daemon=True).start()


def print_operator_help() -> None:
    print("Operator commands:", flush=True)
    print("  type any text + Enter  record a custom action label", flush=True)
    print("  /status                print the latest backend workflow snapshot", flush=True)
    print("  /help                  show this help", flush=True)
    print("  /stop                  stop and save the capture", flush=True)
    print("Guided ATO shortcuts:", flush=True)
    print("  " + "  ".join(f"{key}={value}" for key, value in GUIDED_LABELS.items()), flush=True)


def write_operator_event(
    jsonl: TextIO,
    csv_writer: Any,
    started_monotonic: float,
    event_type: str,
    label: str,
) -> None:
    record = {
        "capturedAtUtc": now_iso(),
        "capturedAtLocal": local_now_iso(),
        "elapsedMs": round((time.monotonic() - started_monotonic) * 1000),
        "eventType": event_type,
        "label": label,
    }
    jsonl.write(json.dumps(record, ensure_ascii=False) + "\n")
    jsonl.flush()
    csv_writer.writerow(record.values())
    jsonl_csv = getattr(csv_writer, "_capture_stream", None)
    if jsonl_csv is not None:
        jsonl_csv.flush()


def start_backend_log(output: Path) -> tuple[Optional[subprocess.Popen[str]], TextIO]:
    stream = output.open("w", encoding="utf-8", newline="\n")
    if shutil.which("docker") is None:
        stream.write("Docker CLI not found; use runtime-status.jsonl for backend state.\n")
        stream.flush()
        return None, stream
    probe = run(["docker", "compose", "ps", "-q", "backend"], check=False)
    if probe.returncode != 0 or not probe.stdout.strip():
        stream.write("Docker backend container is not running; use runtime-status.jsonl for backend state.\n")
        stream.flush()
        return None, stream
    process = subprocess.Popen(
        ["docker", "compose", "logs", "-f", "--tail=200", "backend"],
        cwd=REPO_ROOT,
        stdout=stream,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return process, stream


def stop_process(process: Optional[subprocess.Popen[str]]) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=2)


def self_test() -> int:
    urls = api_urls("http://localhost:8080/", "LAB 1", None)
    assert "LAB%201" in urls["protocol704"]
    assert urls["hil"] == "http://localhost:8080/api/hil/status"
    assert len(GUIDED_LABELS) == 12
    assert GUIDED_LABELS["6"] == "ato_hold_start"
    assert {device[3] for device in DEVICES if device[3]} == {8888, 9999, 8303}
    print("SELF_TEST_PASS passive capture configuration and operator workflow are valid")
    print("No packet capture, device connection, or backend request was attempted.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Passive, interactive lab packet and runtime-state capture"
    )
    parser.add_argument("--duration", type=float, default=0, help="seconds to capture; 0 waits for /stop or Ctrl+C")
    parser.add_argument("--output", type=Path, help="new output directory")
    parser.add_argument("--train-id", default="LB", help="train ID used by 704 and signal workflow APIs")
    parser.add_argument("--api-base", default="http://localhost:8080", help="backend base URL")
    parser.add_argument("--status-url", help="optional HIL status URL override")
    parser.add_argument("--status-interval", type=float, default=1.0)
    parser.add_argument("--label", help="initial operator label")
    parser.add_argument("--no-backend-log", action="store_true", help="do not follow Docker backend logs")
    parser.add_argument("--self-test", action="store_true", help="validate the script without capture, network, or admin rights")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.duration < 0 or args.status_interval <= 0:
        parser.error("duration must be >= 0 and status-interval must be > 0")
    if not args.train_id.strip():
        parser.error("train-id must not be blank")

    if sys.platform != "win32":
        print("ERROR: this capture wrapper requires Windows PktMon", file=sys.stderr)
        return 2
    try:
        require_admin()
    except PermissionError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    output = args.output or Path("captures") / f"lab-session-{datetime.now():%Y%m%d-%H%M%S}"
    try:
        output.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        print(f"ERROR: output directory already exists: {output}", file=sys.stderr)
        return 2

    etl = output / "traffic.etl"
    pcap = output / "traffic.pcapng"
    backend_log = output / "backend.log"
    runtime_status = output / "runtime-status.jsonl"
    operator_jsonl_path = output / "operator-events.jsonl"
    operator_csv_path = output / "operator-events.csv"
    stop = threading.Event()
    status_thread: Optional[threading.Thread] = None
    docker_process: Optional[subprocess.Popen[str]] = None
    log_stream: Optional[TextIO] = None
    operator_jsonl: Optional[TextIO] = None
    operator_csv: Optional[TextIO] = None
    pktmon_started = False
    started_at = now_iso()
    started_monotonic = time.monotonic()
    error: Optional[str] = None
    operator_event_count = 0
    latest: dict[str, Any] = {}
    latest_lock = threading.Lock()
    operator_commands: "queue.Queue[str]" = queue.Queue()

    try:
        configure_filters()
        run([
            "pktmon", "start", "--capture", "--comp", "nics", "--pkt-size", "0",
            "--file-name", str(etl.resolve()), "--file-size", "512", "--log-mode", "circular",
        ])
        pktmon_started = True

        if args.no_backend_log:
            log_stream = backend_log.open("w", encoding="utf-8", newline="\n")
            log_stream.write("Backend log capture disabled by --no-backend-log.\n")
            log_stream.flush()
        else:
            docker_process, log_stream = start_backend_log(backend_log)

        status_thread = threading.Thread(
            target=poll_runtime_status,
            args=(
                api_urls(args.api_base, args.train_id.strip(), args.status_url),
                runtime_status,
                stop,
                args.status_interval,
                latest,
                latest_lock,
            ),
            daemon=True,
        )
        status_thread.start()

        operator_jsonl = operator_jsonl_path.open("w", encoding="utf-8", newline="\n")
        operator_csv = operator_csv_path.open("w", encoding="utf-8-sig", newline="")
        csv_writer = csv.writer(operator_csv)
        csv_writer.writerow(["capturedAtUtc", "capturedAtLocal", "elapsedMs", "eventType", "label"])
        operator_csv.flush()
        # csv.writer does not expose its stream, so attach it for prompt flushing.
        setattr(csv_writer, "_capture_stream", operator_csv)
        write_operator_event(
            operator_jsonl,
            csv_writer,
            started_monotonic,
            "capture_started",
            args.label or "session_start",
        )
        operator_event_count += 1

        print(f"CAPTURE_STARTED output={output.resolve()}")
        print(f"TRAIN_ID {args.train_id.strip()}")
        print_operator_help()
        start_operator_input(operator_commands)
        deadline = time.monotonic() + args.duration if args.duration else None
        stop_requested = False
        while not stop_requested and (deadline is None or time.monotonic() < deadline):
            try:
                command = operator_commands.get(timeout=0.25)
            except queue.Empty:
                continue
            if not command:
                continue
            lowered = command.lower()
            if lowered in {"/stop", "stop", "quit", "exit"}:
                stop_requested = True
                continue
            if lowered in {"/help", "help", "?"}:
                print_operator_help()
                continue
            if lowered in {"/status", "status"}:
                print_latest_status(latest, latest_lock)
                continue
            label = GUIDED_LABELS.get(command, command)
            write_operator_event(operator_jsonl, csv_writer, started_monotonic, "operator_label", label)
            operator_event_count += 1
            print(f"LABEL [{local_now_iso()}] {label}", flush=True)
    except KeyboardInterrupt:
        print("Stopping capture...", flush=True)
    except (OSError, subprocess.CalledProcessError) as exc:
        error = f"{type(exc).__name__}: {exc}"
        print(f"ERROR: {error}", file=sys.stderr)
    finally:
        if operator_jsonl is not None and operator_csv is not None:
            csv_writer = csv.writer(operator_csv)
            setattr(csv_writer, "_capture_stream", operator_csv)
            write_operator_event(operator_jsonl, csv_writer, started_monotonic, "capture_stopped", error or "normal")
            operator_event_count += 1
        stop.set()
        if status_thread is not None:
            status_thread.join(timeout=3)
        stop_process(docker_process)
        if log_stream is not None:
            log_stream.close()
        if operator_jsonl is not None:
            operator_jsonl.close()
        if operator_csv is not None:
            operator_csv.close()
        if pktmon_started:
            run(["pktmon", "stop"], check=False)
            conversion = run(["pktmon", "etl2pcap", str(etl), "--out", str(pcap)], check=False)
            if conversion.returncode != 0 and error is None:
                error = "PktMon conversion failed: " + (conversion.stderr or conversion.stdout).strip()
        run(["pktmon", "filter", "remove"], check=False)

    summary = {
        "startedAt": started_at,
        "stoppedAt": now_iso(),
        "trainId": args.train_id.strip(),
        "captureMode": "passive Windows PktMon; no laboratory device socket opened",
        "packetFilter": [
            {"name": name, "host": host, "protocol": protocol, "port": port}
            for name, host, protocol, port in DEVICES
        ],
        "pcap": str(pcap),
        "etl": str(etl),
        "backendLog": str(backend_log),
        "runtimeStatus": str(runtime_status),
        "operatorEventsJsonl": str(operator_jsonl_path),
        "operatorEventsCsv": str(operator_csv_path),
        "operatorEventCount": operator_event_count,
        "pcapBytes": pcap.stat().st_size if pcap.exists() else 0,
        "error": error,
    }
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"CAPTURE_SAVED output={output.resolve()} error={error or 'none'}")
    return 1 if error else 0


if __name__ == "__main__":
    raise SystemExit(main())
