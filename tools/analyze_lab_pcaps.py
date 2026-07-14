import struct
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FILES = [
    ("8888-port.pcapng", 8888),
    ("9999-port.pcapng", 9999),
    ("8303-port.pcapng", 8303),
]


def parse_packet(data):
    if len(data) < 14:
        return None
    ether_type = struct.unpack("!H", data[12:14])[0]
    ip_start = 14
    while ether_type in (0x8100, 0x88A8, 0x9100):
        if len(data) < ip_start + 4:
            return None
        ether_type = struct.unpack("!H", data[ip_start + 2:ip_start + 4])[0]
        ip_start += 4
    if ether_type != 0x0800 or len(data) < ip_start + 20:
        return None
    version_ihl = data[ip_start]
    if version_ihl >> 4 != 4:
        return None
    ihl = (version_ihl & 0x0F) * 4
    total_len = struct.unpack("!H", data[ip_start + 2:ip_start + 4])[0]
    if len(data) < ip_start + ihl or total_len < ihl:
        return None
    proto = data[ip_start + 9]
    src = ".".join(map(str, data[ip_start + 12:ip_start + 16]))
    dst = ".".join(map(str, data[ip_start + 16:ip_start + 20]))
    end = min(len(data), ip_start + total_len)
    l4 = ip_start + ihl
    if proto == 6:
        if end < l4 + 20:
            return None
        sport, dport = struct.unpack("!HH", data[l4:l4 + 4])
        header_len = (data[l4 + 12] >> 4) * 4
        if header_len < 20 or end < l4 + header_len:
            return None
        return "TCP", sport, dport, src, dst, data[l4 + header_len:end]
    if proto == 17:
        if end < l4 + 8:
            return None
        sport, dport, udp_len = struct.unpack("!HHH", data[l4:l4 + 6])
        udp_end = min(end, l4 + max(8, udp_len))
        return "UDP", sport, dport, src, dst, data[l4 + 8:udp_end]
    return None


def analyze(path, wanted_port):
    packet_count = 0
    selected = 0
    lengths = Counter()
    directions = Counter()
    prefixes = Counter()
    samples = []
    with path.open("rb") as stream:
        endian = "<"
        while True:
            header = stream.read(8)
            if len(header) < 8:
                break
            block_type, block_len = struct.unpack(endian + "II", header)
            if block_len < 12 or block_len > 16_777_216:
                break
            body = stream.read(block_len - 12)
            trailer = stream.read(4)
            if len(body) != block_len - 12 or len(trailer) != 4:
                break
            if block_type == 0x0A0D0D0A and len(body) >= 4:
                bom = body[:4]
                if bom == b"\x4d\x3c\x2b\x1a":
                    endian = "<"
                elif bom == b"\x1a\x2b\x3c\x4d":
                    endian = ">"
                continue
            if block_type != 6 or len(body) < 20:
                continue
            packet_count += 1
            cap_len = struct.unpack(endian + "I", body[12:16])[0]
            parsed = parse_packet(body[20:20 + cap_len])
            if parsed is None:
                continue
            transport, sport, dport, src, dst, payload = parsed
            if sport != wanted_port and dport != wanted_port:
                continue
            selected += 1
            directions[(transport, f"{src}:{sport} -> {dst}:{dport}")] += 1
            lengths[len(payload)] += 1
            prefixes[payload[:8].hex(" ")] += 1
            if payload and len(samples) < 5:
                samples.append((transport, f"{src}:{sport} -> {dst}:{dport}", len(payload), payload[:80].hex(" ")))
    return packet_count, selected, directions, lengths, prefixes, samples


for filename, port in FILES:
    packet_count, selected, directions, lengths, prefixes, samples = analyze(ROOT / filename, port)
    print(f"\n### {filename} target={port}")
    print(f"epb_packets={packet_count} matching_transport_packets={selected}")
    print("directions=", directions.most_common(8))
    print("payload_lengths=", lengths.most_common(12))
    print("payload_prefixes=", prefixes.most_common(8))
    print("samples=")
    for sample in samples:
        print(sample)


capture_path = ROOT / "hil-704-capture-1783844035066-1783844174581" / "records.jsonl"
if capture_path.exists():
    import json

    button_keys = (
        "mode_up_pressed", "mode_down_pressed", "confirm_pressed",
        "automatic_return_pressed", "ato_start_pressed",
        "open_left_door_pressed", "open_right_door_pressed",
        "close_left_door_pressed", "close_right_door_pressed",
    )
    frame_count = 0
    byte34_values = Counter()
    active_frames = []
    with capture_path.open(encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            if (record.get("channel") != "plc" or record.get("direction") != "RX"
                    or record.get("size_bytes") != 46):
                continue
            frame_count += 1
            raw = record.get("raw_hex", "").split()
            if len(raw) > 34:
                byte34_values[int(raw[34], 16)] += 1
            decoded = record.get("decoded") or {}
            pressed = {key: decoded[key] for key in button_keys if decoded.get(key)}
            if pressed:
                active_frames.append((record.get("captured_at_unix_ms"), pressed, record.get("raw_hex")))
    print("\n### hil-704-capture PLC input buttons")
    print(f"plc_rx_46b_frames={frame_count}")
    print("byte34_values=", byte34_values)
    print(f"active_button_frames={len(active_frames)}")
    for frame in active_frames[:20]:
        print(frame)
