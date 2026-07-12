#!/usr/bin/env python3
"""
实验室实体 PLC → 发动仿真列车 联调脚本

流程：
  1) POST /simulation/run 注册仿真 (trainId)
  2) test-frame: set_manual + depart_confirm  （授权发车；也可由台上确认键完成）
  3) test-frame: traction  （软件先验证能加速）
  4) POST /protocol704/connect 连接 192.168.100.123
  5) 轮询 status：看实体手柄帧是否继续推速度

用法：
  python scripts/lab_start_train.py
  python scripts/lab_start_train.py --train-id LAB1 --watch 30
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from typing import Any, Dict, Optional


API = "http://localhost:8080/api"


def ts() -> str:
    return datetime.now().strftime("%H:%M:%S")


def http_json(method: str, url: str, body: Optional[dict] = None, timeout: float = 30.0) -> Any:
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        if not raw:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"raw": raw}


def main() -> int:
    p = argparse.ArgumentParser(description="用实验室 PLC/软件帧发动仿真车")
    p.add_argument("--train-id", default="LAB1")
    p.add_argument("--from-station", type=int, default=1)
    p.add_argument("--to-station", type=int, default=4)
    p.add_argument("--watch", type=float, default=25.0, help="连接 PLC 后观察秒数")
    p.add_argument("--skip-connect", action="store_true", help="只做软件帧验证，不连实体 PLC")
    args = p.parse_args()
    tid = args.train_id

    print("=" * 64)
    print(f"[{ts()}] 实验室发车联调  trainId={tid}")
    print("=" * 64)

    # 0. health
    try:
        health = http_json("GET", f"{API}/health")
        print(f"[{ts()}] backend OK: {health}", flush=True)
    except Exception as e:
        print(f"[{ts()}] 后端未启动: {e}", flush=True)
        return 1

    # 1. 先断开旧 704，避免占端口
    try:
        http_json("POST", f"{API}/vehicle/protocol704/disconnect?trainId={tid}")
        print(f"[{ts()}] disconnect 旧连接", flush=True)
    except Exception:
        pass

    # 2. 跑仿真并注册 704 上下文
    run_body = {
        "trainId": tid,
        "fromStationId": args.from_station,
        "toStationId": args.to_station,
        "dwellTimeSeconds": 5,
    }
    print(f"[{ts()}] simulation/run {run_body} ...", flush=True)
    try:
        run_res = http_json("POST", f"{API}/vehicle/simulation/run", run_body, timeout=120)
    except Exception as e:
        print(f"[{ts()}] run 失败: {e}", flush=True)
        return 1
    if not run_res or not run_res.get("success"):
        print(f"[{ts()}] run 返回失败: {run_res}", flush=True)
        return 1
    summary = (run_res.get("data") or {}).get("summary") or {}
    states = (run_res.get("data") or {}).get("states") or []
    print(
        f"[{ts()}] 仿真已注册: stations={summary.get('fromStationName')}→{summary.get('toStationName')}  "
        f"states={len(states)}  departure={summary.get('departureState')}",
        flush=True,
    )

    def inject(ftype: str) -> Dict[str, Any]:
        url = f"{API}/vehicle/protocol704/test-frame?trainId={tid}&type={ftype}"
        print(f"[{ts()}] inject {ftype} ...", flush=True)
        st = http_json("POST", url)
        life = (st or {}).get("lastCommandLifecycle") or {}
        mapped = (st or {}).get("lastMappedCommand") or {}
        rt = (st or {}).get("realtimeVehicleState") or {}
        print(
            f"         mapped={mapped.get('command')}  "
            f"lifecycle={life.get('status')} reject={life.get('rejectionReason')}  "
            f"v={rt.get('velocityMs')} pos={rt.get('positionM')} mode={rt.get('mode')}",
            flush=True,
        )
        return st or {}

    # 3. 软件授权：人工模式 + 发车确认（实体台上也可按确认键，此处先软件打通）
    inject("set_manual")
    inject("depart_confirm")

    # 4. 软件牵引：证明仿真能被发动
    st = inject("traction")
    life = (st.get("lastCommandLifecycle") or {})
    if life.get("status") != "EXECUTED":
        print(
            f"[{ts()}] 软件牵引未 EXECUTED（{life.get('rejectionReason')}）。"
            f" 若提示缺测试帧类型，请重启后端加载最新 Protocol704Service。",
            flush=True,
        )
    else:
        v1 = (st.get("realtimeVehicleState") or {}).get("velocityMs")
        print(f"[{ts()}] ✓ 软件侧已发动，当前速度 v={v1} m/s", flush=True)

    if args.skip_connect:
        print(f"[{ts()}] --skip-connect：不连实体 PLC", flush=True)
        return 0

    # 5. 连接实体 PLC（占用 8001/8002/8003；请勿同时跑 tcp704_live_demo）
    print(f"[{ts()}] connect PLC 192.168.100.123 ...", flush=True)
    try:
        http_json("POST", f"{API}/vehicle/protocol704/connect?trainId={tid}")
    except Exception as e:
        print(f"[{ts()}] connect 失败: {e}", flush=True)
        return 1

    print(
        f"[{ts()}] 已请求连接。请在司机台确认：\n"
        f"  1) 钥匙接通\n"
        f"  2) 方向手柄 → 向前\n"
        f"  3) 主手柄 → 牵引（可拧级位）\n"
        f"  若速度仍不动：按一下「确认」或模式确认键（DEPART/MANUAL）\n"
        f"观察 {args.watch}s ...",
        flush=True,
    )

    t0 = time.time()
    last_v = None
    moved = False
    while time.time() - t0 < args.watch:
        try:
            st = http_json("GET", f"{API}/vehicle/protocol704/status?trainId={tid}")
        except Exception as e:
            print(f"[{ts()}] status 失败: {e}", flush=True)
            time.sleep(1)
            continue
        rt = (st or {}).get("realtimeVehicleState") or {}
        life = (st or {}).get("lastCommandLifecycle") or {}
        mapped = (st or {}).get("lastMappedCommand") or {}
        ports = (st or {}).get("portStatuses") or {}
        connected = (st or {}).get("connected")
        v = rt.get("velocityMs")
        pos = rt.get("positionM")
        if v is not None and last_v is not None and abs(float(v) - float(last_v)) > 0.05:
            moved = True
        last_v = v
        port_ok = []
        if isinstance(ports, dict):
            for k, ps in ports.items():
                if isinstance(ps, dict) and ps.get("connected"):
                    port_ok.append(str(k))
        print(
            f"[{ts()}] conn={connected} ports={port_ok or '-'}  "
            f"cmd={mapped.get('command')} life={life.get('status')}/{life.get('rejectionReason')}  "
            f"v={v} pos={pos}",
            flush=True,
        )
        time.sleep(1.5)

    print("=" * 64)
    if moved:
        print(f"[{ts()}] 成功：观察到速度变化，实体/软件链路已带动仿真车。")
    else:
        print(
            f"[{ts()}] 未观察到明显速度变化。\n"
            f"  - 看上面 life= 是否 NOT_READY_TO_DEPART / MODE_NOT_MANUAL / DUPLICATE_STEADY_STATE\n"
            f"  - 台上推牵引，或再跑: 不加 --skip-connect，并按确认键\n"
            f"  - 确认没有其它程序占用 PLC 端口"
        )
    print("=" * 64)
    return 0 if moved else 2


if __name__ == "__main__":
    sys.exit(main())
