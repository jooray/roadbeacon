#!/usr/bin/env python3
"""Generate nips/vectors/road-bridge-v1.json, the vectors for the bridge summary and request frames,
the claim winner and the airtime estimate (SPECIFICATION.md sections 4.2 and 4.3).

Standard library only, written from the specification, deterministic:

    python3 -I tools/gen_road_bridge_vectors.py > nips/vectors/road-bridge-v1.json
"""
import hashlib
import json
import math
import struct

TYPES = {
    "police": 0, "speed_camera": 1, "traffic_jam": 2, "accident": 3, "road_closure": 4,
    "construction": 5, "hazard": 6, "road_condition": 7, "pothole": 8, "fog": 9, "ice": 10,
    "animal": 11, "police_station": 12, "surveillance_camera": 13, "other": 255,
}
LIFETIME = {
    0: 4 * 3600, 1: 30 * 86400, 2: 3600, 3: 4 * 3600, 4: 24 * 3600, 5: 15 * 86400, 6: 4 * 3600,
    7: 15 * 86400, 8: 7 * 86400, 9: 4 * 3600, 10: 4 * 3600, 11: 3600, 12: 30 * 86400,
    13: 30 * 86400, 255: 4 * 3600,
}
SUMMARY, REQUEST = 0x10, 0x20


# ── duration code ───────────────────────────────────────────────────────────────────────────────

def duration_encode(seconds):
    """Rounded down; None when it does not fit (over 30 days)."""
    if seconds < 0:
        raise ValueError
    minutes = seconds // 60
    if minutes < 60:
        return minutes
    hours = seconds // 3600
    if hours < 24:
        return 59 + hours
    days = seconds // 86400
    if days <= 30:
        return 82 + days
    return None


def duration_decode(code):
    if code < 60:
        return code * 60
    if code <= 82:
        return (code - 59) * 3600
    if code <= 112:
        return (code - 82) * 86400
    return None  # 113..254 reserved, 255 "more than 30 days"


# ── coordinates ─────────────────────────────────────────────────────────────────────────────────

def round_half_away(num, den):
    q, r = divmod(abs(num), den)
    if 2 * r >= den:
        q += 1
    return q if num >= 0 else -q


def ref_of(e6):
    return round_half_away(e6, 10_000)


def offset_of(e6, ref):
    return round_half_away(e6 - ref * 10_000, 10)


def local_id(category, lat_e6, lon_e6):
    return "bridge:" + hashlib.sha256(bytes([category]) + struct.pack(">ii", lat_e6, lon_e6)).hexdigest()[:16]


# ── frames ──────────────────────────────────────────────────────────────────────────────────────

def summary_frame(point, entries):
    ref_lat, ref_lon = ref_of(point[0]), ref_of(point[1])
    out = bytes([SUMMARY]) + struct.pack(">hh", ref_lat, ref_lon)
    for e in entries:
        out += struct.pack(">hh", offset_of(e["lat_e6"], ref_lat), offset_of(e["lon_e6"], ref_lon))
        age = duration_encode(e["age_s"])
        out += bytes([e["category"], 255 if age is None else age, duration_encode(e["remaining_s"]),
                      (min(e["still"], 15) << 4) | min(e["gone"], 15)])
    return out


def summary_decode(frame):
    """(entries, None) or (None, error). Dropped entries are left out silently."""
    if len(frame) < 13 or (len(frame) - 5) % 8 or (len(frame) - 5) // 8 > 5:
        return None, "BAD_LENGTH"
    if frame[0] != SUMMARY:
        return None, "BAD_TYPE"
    ref_lat, ref_lon = struct.unpack(">hh", frame[1:5])
    if not -9000 <= ref_lat <= 9000 or not -18000 <= ref_lon <= 18000:
        return None, "BAD_REFERENCE"
    entries = []
    for i in range(5, len(frame), 8):
        d_lat, d_lon = struct.unpack(">hh", frame[i:i + 4])
        category, age_code, rem_code, votes = frame[i + 4:i + 8]
        lat_e6, lon_e6 = ref_lat * 10_000 + d_lat * 10, ref_lon * 10_000 + d_lon * 10
        if category not in LIFETIME:
            continue
        age = duration_decode(age_code)
        remaining = duration_decode(rem_code)
        if remaining is None or remaining == 0:
            continue
        if age is None or age > LIFETIME[category]:
            continue
        if abs(lat_e6) > 90_000_000 or abs(lon_e6) > 180_000_000:
            continue
        entries.append({"lat_e6": lat_e6, "lon_e6": lon_e6, "category": category, "age_s": age,
                        "remaining_s": remaining, "still": votes >> 4, "gone": votes & 15,
                        "id": local_id(category, lat_e6, lon_e6)})
    return entries, None


def request_frame(point, radius_km, since_s, categories):
    mask = 0
    for c in categories:
        mask |= 1 << (15 if c == 255 else c)
    since = 255 if since_s is None else duration_encode(since_s)
    return bytes([REQUEST]) + struct.pack(">hhBBH", ref_of(point[0]), ref_of(point[1]), radius_km, since, mask) + bytes(4)


def request_decode(frame):
    if len(frame) != 13:
        return None, "BAD_LENGTH"
    if frame[0] != REQUEST:
        return None, "BAD_TYPE"
    lat, lon, radius, since, mask = struct.unpack(">hhBBH", frame[1:9])
    if any(frame[9:13]):
        return None, "RESERVED_BITS"
    if not -9000 <= lat <= 9000 or not -18000 <= lon <= 18000:
        return None, "BAD_REFERENCE"
    if radius > 25:
        return None, "BAD_RADIUS"
    if since != 255 and duration_decode(since) is None:
        return None, "BAD_DURATION"
    cats = [c for c in range(14) if mask >> c & 1] + ([255] if mask >> 15 & 1 else [])
    return {"lat_e6": lat * 10_000, "lon_e6": lon * 10_000, "radius_km": radius or 25,
            "since_s": None if since == 255 else duration_decode(since), "categories": cats}, None


# ── claim winner and airtime ────────────────────────────────────────────────────────────────────

def claim_rank(pubkey_hex, area, hops):
    return (hops, hashlib.sha256(bytes.fromhex(pubkey_hex) + area.encode()).digest())


def packet_bytes(content_len, region):
    block = math.ceil((3 + content_len) / 16) * 16
    return 5 + block + (4 if region else 0)


def airtime_ms(sf, bw_hz, cr, size):
    """Semtech LoRa time on air: explicit header, CRC on, MeshCore preamble."""
    t_sym = (2 ** sf) / bw_hz * 1000
    de = 1 if t_sym > 16 else 0
    preamble = 32 if sf <= 8 else 16
    num = 8 * size - 4 * sf + 28 + 16
    symbols = 8 + max(math.ceil(num / (4 * (sf - 2 * de))) * cr, 0)
    return round((preamble + 4.25 + symbols) * t_sym, 3)


# ── vectors ─────────────────────────────────────────────────────────────────────────────────────

def main():
    point = (48_148_598, 17_107_748)  # Bratislava, the example point of the road-events vectors
    entries = [
        {"lat_e6": 48_150_000, "lon_e6": 17_100_000, "category": 0, "age_s": 25 * 60, "remaining_s": 3 * 3600 + 1800, "still": 2, "gone": 0},
        {"lat_e6": 48_120_517, "lon_e6": 17_201_003, "category": 3, "age_s": 59, "remaining_s": 4 * 3600 - 59, "still": 0, "gone": 1},
        {"lat_e6": 48_300_004, "lon_e6": 16_950_005, "category": 1, "age_s": 12 * 86400, "remaining_s": 18 * 86400, "still": 20, "gone": 3},
        {"lat_e6": 48_000_000, "lon_e6": 17_000_000, "category": 2, "age_s": 10 * 60, "remaining_s": 50 * 60, "still": 0, "gone": 0},
        {"lat_e6": 48_181_234, "lon_e6": 17_054_321, "category": 255, "age_s": 2 * 3600, "remaining_s": 2 * 3600, "still": 1, "gone": 1},
    ]

    def summary_case(name, n):
        frame = summary_frame(point, entries[:n])
        decoded, err = summary_decode(frame)
        assert err is None and len(decoded) == n
        return {"name": name, "point": {"lat_e6": point[0], "lon_e6": point[1]}, "entries": entries[:n],
                "frame": frame.hex(), "content_bytes": len(frame), "decoded": decoded}

    good = summary_frame(point, entries[:1])
    bad_summary = [
        {"name": "wrong_type", "frame": (bytes([0x11]) + good[1:]).hex(), "error": "BAD_TYPE"},
        {"name": "signed_frame_family", "frame": (bytes([0x00]) + good[1:]).hex(), "error": "BAD_TYPE"},
        {"name": "too_short", "frame": good[:12].hex(), "error": "BAD_LENGTH"},
        {"name": "partial_entry", "frame": (good + bytes(1)).hex(), "error": "BAD_LENGTH"},
        {"name": "six_entries", "frame": (good + good[5:] * 5).hex(), "error": "BAD_LENGTH"},
        {"name": "reference_out_of_range", "frame": (bytes([SUMMARY]) + struct.pack(">hh", 9001, 0) + good[5:]).hex(), "error": "BAD_REFERENCE"},
    ]

    def entry_bytes(category, age, remaining):
        return struct.pack(">hh", 10, -10) + bytes([category, age, remaining, 0x10])

    header = bytes([SUMMARY]) + struct.pack(">hh", 4815, 1711)
    dropped = [
        ("reserved_category", entry_bytes(14, 5, 30)),
        ("remaining_zero", entry_bytes(0, 5, 0)),
        ("remaining_over_30_days", entry_bytes(0, 5, 255)),
        ("reserved_duration_code", entry_bytes(0, 113, 30)),
        ("older_than_lifetime", entry_bytes(2, 61, 30)),  # traffic jam (1 h) reported 2 h ago
    ]
    keep = entry_bytes(0, 5, 30)
    dropped_cases = []
    for name, entry in dropped:
        frame = header + keep + entry + keep
        decoded, err = summary_decode(frame)
        assert err is None and len(decoded) == 2, name
        dropped_cases.append({"name": name, "frame": frame.hex(), "kept_entries": 2})

    req = request_frame(point, 15, 2 * 3600, [0, 3, 255])
    req_any = request_frame(point, 0, None, [])
    requests = []
    for name, frame in (("police_accident_other_2h_15km", req), ("defaults", req_any)):
        decoded, err = request_decode(frame)
        assert err is None
        requests.append({"name": name, "frame": frame.hex(), "decoded": decoded})
    bad_requests = [
        {"name": "reserved_not_zero", "frame": (req[:12] + b"\x01").hex(), "error": "RESERVED_BITS"},
        {"name": "radius_over_25", "frame": (req[:5] + bytes([26]) + req[6:]).hex(), "error": "BAD_RADIUS"},
        {"name": "wrong_length", "frame": req[:12].hex(), "error": "BAD_LENGTH"},
        {"name": "wrong_type", "frame": (bytes([0x21]) + req[1:]).hex(), "error": "BAD_TYPE"},
    ]
    for case in bad_requests:
        assert request_decode(bytes.fromhex(case["frame"]))[1] == case["error"], case["name"]
    for case in bad_summary:
        assert summary_decode(bytes.fromhex(case["frame"]))[1] == case["error"], case["name"]

    durations = [{"seconds": s, "code": duration_encode(s)} for s in
                 (0, 59, 60, 3599, 3600, 7199, 23 * 3600 + 3599, 86400, 86400 * 30, 86400 * 30 + 86399, 86400 * 31)]
    decodes = [{"code": c, "seconds": duration_decode(c)} for c in (0, 59, 60, 82, 83, 112, 113, 254, 255)]

    area = "u2s1"
    keys = [hashlib.sha256(f"road-bridge-v1 claim key {i}".encode()).hexdigest() for i in range(4)]
    claim_sets = []
    for name, hops in (("fewest_hops_wins", [2, 0, 1, 0]), ("tie_on_hash", [1, 1, 1, 1]), ("unknown_hops", [255, 255, 3, 255])):
        claims = [{"pubkey": k, "hops": h} for k, h in zip(keys, hops)]
        winner = min(range(len(claims)), key=lambda i: claim_rank(keys[i], area, hops[i]))
        claim_sets.append({"name": name, "area": area, "claims": claims, "winner": winner,
                           "ranks": [hashlib.sha256(bytes.fromhex(k) + area.encode()).hexdigest() for k in keys]})

    airtime = []
    for sf, bw, cr in ((11, 250_000, 5), (8, 62_500, 8), (9, 125_000, 5), (7, 125_000, 5)):
        for content, region in ((13, True), (29, True), (45, True), (45, False), (163, True)):
            size = packet_bytes(content, region)
            airtime.append({"sf": sf, "bw_hz": bw, "cr": cr, "content_bytes": content, "region": region,
                            "packet_bytes": size, "airtime_ms": airtime_ms(sf, bw, cr, size)})

    out = {
        "description": "Vectors for SPECIFICATION.md sections 4.2-4.3: bridge summary (0x10) and request (0x20) frames on data_type 0x0150, duration codes, claim winner, airtime. Generated by tools/gen_road_bridge_vectors.py; do not edit by hand.",
        "version": 1,
        "category_lifetime_s": {str(k): v for k, v in LIFETIME.items()},
        "duration_encode": durations,
        "duration_decode": decodes,
        "summary_valid": [summary_case("one_entry", 1), summary_case("three_entries", 3), summary_case("five_entries", 5)],
        "summary_invalid": bad_summary,
        "summary_entries_dropped": dropped_cases,
        "request_valid": requests,
        "request_invalid": bad_requests,
        "claims": claim_sets,
        "airtime": airtime,
    }
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
