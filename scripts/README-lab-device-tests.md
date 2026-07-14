# Laboratory Device Test Scripts

Run these scripts only after the wired adapter has a `Preferred`
`192.168.100.x/24` address and the teacher has confirmed that no other program
is connected to the same device port.

The commands below use the current measured mapping:

| Device | Target | Measured packet boundary |
| --- | --- | --- |
| Network screen (HMI) | `192.168.100.121:8888` TCP | 570 bytes |
| Signal screen (MMI) | `192.168.100.122:9999` TCP | 68 bytes, legacy header `62/42` |
| Vision | `192.168.100.124:8303` UDP | 128 bytes, 77 signals / 29 switches |

Start with connection probes. These do not write a device frame:

```powershell
python scripts/test_network_screen_570.py
python scripts/test_signal_screen_68.py
python scripts/test_vision_128.py --capture 8303-port.pcapng
```

Monitor the backend's complete Vision running process without opening any
laboratory socket. The second command waits through one station stop and reports
the actual automatic door/route/MA dwell before the next departure:

```powershell
python scripts/test_vision_runtime.py --duration 30
python scripts/test_vision_runtime.py --until-next-depart --duration 300
```

Capture the actual UDP 8303 packets emitted by the current backend and decode
them without using an existing pcap. Run from an Administrator PowerShell:

```powershell
python scripts/capture_vision_live.py --duration 20
```

The new capture, decoded JSONL, and summary are written under
`captures/vision-live-<timestamp>/`.

For a teacher-approved one-frame display check, send one stationary or
low-speed display-only frame. The screen scripts do not control the PLC or the
simulated train:

```powershell
python scripts/test_signal_screen_68.py --send --speed-kmh 8
python scripts/test_network_screen_570.py --send --speed-kmh 8
```

The Vision 128-byte table is implemented in the backend. The standalone script
does not synthesize a live train state; it can replay a previously captured
128-byte packet only after explicit approval and only when Windows selects a
`192.168.100.x` source address:

```powershell
python scripts/test_vision_128.py --capture 8303-port.pcapng --send --teacher-approved
```

`TX_OK` proves that this computer handed the packet to its TCP/UDP stack. For
HMI, a 570-byte echo is stronger transport evidence. For MMI and Vision, use
the device display and a simultaneous Wireshark capture to confirm reception;
neither channel provides a reliable application-level acknowledgement.

## Passive full-session capture

Run this from an Administrator PowerShell while the platform is running. It
uses Windows PktMon and does not open a connection to any laboratory device:

```powershell
python scripts/capture_lab_session.py --train-id LB --label baseline
```

While capture is running, type an action label and press Enter. Numeric
shortcuts `1` through `12` cover the normal ATO sequence; `/status` prints the
latest 704/ATO/signal state, `/help` shows the shortcuts, and `/stop` saves the
session. Pressing `Ctrl+C` is also safe. A fixed run can use `--duration 120`.

Each run writes a timestamped directory under `captures/` with:

- `traffic.pcapng`: passive packets for PLC, HMI, MMI, and Vision;
- `operator-events.jsonl` and `operator-events.csv`: timestamped desk actions;
- `runtime-status.jsonl`: synchronized 704, ATO, signal-workflow, and HIL APIs;
- `backend.log`: Docker backend logs when that container is running;
- `summary.json`: capture settings, paths, size, and errors.

Validate the script without Administrator rights, network access, or devices:

```powershell
python scripts/capture_lab_session.py --self-test
```

Do not run `plc704_capture.py` at the same time as the backend because that
tool is an active read-only PLC client and may occupy a single-client port.
