#!/usr/bin/env python3
"""Generate nips/vectors/road-events-v1.json, the conformance vectors for nips/road-events.md.

Independent of both apps: standard library only, BIP-340 from the BIP's reference code, geohash,
coordinate text and frames written from the NIP. Deterministic (fixed keys, zero aux randomness),
so re-running it must reproduce the file byte for byte.

    python3 -I tools/gen_road_event_vectors.py > nips/vectors/road-events-v1.json
"""
import hashlib
import json
import struct

# ── BIP-340 (reference implementation, trimmed) ─────────────────────────────────────────────────

P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def point_add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    if p1[0] == p2[0] and p1[1] != p2[1]:
        return None
    if p1 == p2:
        lam = (3 * p1[0] * p1[0] * pow(2 * p1[1], P - 2, P)) % P
    else:
        lam = ((p2[1] - p1[1]) * pow(p2[0] - p1[0], P - 2, P)) % P
    x3 = (lam * lam - p1[0] - p2[0]) % P
    return x3, (lam * (p1[0] - x3) - p1[1]) % P


def point_mul(p, n):
    r = None
    for i in range(256):
        if (n >> i) & 1:
            r = point_add(r, p)
        p = point_add(p, p)
    return r


def tagged_hash(tag, msg):
    t = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(t + t + msg).digest()


def bytes_from_int(x):
    return x.to_bytes(32, "big")


def int_from_bytes(b):
    return int.from_bytes(b, "big")


def pubkey_gen(seckey):
    return bytes_from_int(point_mul(G, int_from_bytes(seckey))[0])


def schnorr_sign(msg, seckey, aux_rand=bytes(32)):
    d0 = int_from_bytes(seckey)
    pt = point_mul(G, d0)
    d = d0 if pt[1] % 2 == 0 else N - d0
    t = bytes_from_int(d ^ int_from_bytes(tagged_hash("BIP0340/aux", aux_rand)))
    k0 = int_from_bytes(tagged_hash("BIP0340/nonce", t + bytes_from_int(pt[0]) + msg)) % N
    r = point_mul(G, k0)
    k = k0 if r[1] % 2 == 0 else N - k0
    e = int_from_bytes(tagged_hash("BIP0340/challenge", bytes_from_int(r[0]) + bytes_from_int(pt[0]) + msg)) % N
    return bytes_from_int(r[0]) + bytes_from_int((k + e * d) % N)


# ── road-event format ───────────────────────────────────────────────────────────────────────────

TYPES = {
    "police": 0, "speed_camera": 1, "traffic_jam": 2, "accident": 3, "road_closure": 4,
    "construction": 5, "hazard": 6, "road_condition": 7, "pothole": 8, "fog": 9, "ice": 10,
    "animal": 11, "police_station": 12, "surveillance_camera": 13, "other": 255,
}
LIFETIME = {
    "police": 4 * 3600, "speed_camera": 30 * 86400, "traffic_jam": 3600, "accident": 4 * 3600,
    "road_closure": 24 * 3600, "construction": 15 * 86400, "hazard": 4 * 3600,
    "road_condition": 15 * 86400, "pothole": 7 * 86400, "fog": 4 * 3600, "ice": 4 * 3600,
    "animal": 3600, "police_station": 30 * 86400, "surveillance_camera": 30 * 86400,
    "other": 4 * 3600,
}
BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def fmt(e6):
    sign = "-" if e6 < 0 else ""
    m = abs(e6)
    return f"{sign}{m // 1_000_000}.{m % 1_000_000:06d}"


def geohash(lat, lon, precision):
    lat_r, lon_r = [-90.0, 90.0], [-180.0, 180.0]
    out, bit, ch, is_lon = [], 0, 0, True
    while len(out) < precision:
        rng, v = (lon_r, lon) if is_lon else (lat_r, lat)
        mid = (rng[0] + rng[1]) / 2
        if v >= mid:
            ch |= 1 << (4 - bit)
            rng[0] = mid
        else:
            rng[1] = mid
        is_lon = not is_lon
        bit += 1
        if bit == 5:
            out.append(BASE32[ch])
            bit, ch = 0, 0
    return "".join(out)


def report_tags(lat_e6, lon_e6, type_name, expiration, maxspeed=None):
    lat, lon = lat_e6 / 1e6, lon_e6 / 1e6
    tags = [["lat", fmt(lat_e6)], ["lon", fmt(lon_e6)]]
    tags += [["g", geohash(lat, lon, p)] for p in (4, 5, 6)]
    tags += [["t", type_name], ["expiration", str(expiration)]]
    if maxspeed is not None:
        tags.append(["maxspeed", str(maxspeed)])
    return tags


def event_id(pubkey, created_at, kind, tags, content):
    s = json.dumps([0, pubkey, created_at, kind, tags, content], separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(s.encode()).hexdigest()


def sign_event(seckey, created_at, kind, tags, content):
    pubkey = pubkey_gen(seckey).hex()
    eid = event_id(pubkey, created_at, kind, tags, content)
    sig = schnorr_sign(bytes.fromhex(eid), seckey).hex()
    return {"id": eid, "pubkey": pubkey, "created_at": created_at, "kind": kind,
            "tags": tags, "content": content, "sig": sig}


def report_frame(ev, lat_e6, lon_e6, type_name, expiration, maxspeed=0, flags=0):
    comment = ev["content"].encode()
    return (bytes([flags]) + bytes.fromhex(ev["pubkey"]) + struct.pack(">I", ev["created_at"])
            + struct.pack(">ii", lat_e6, lon_e6) + bytes([TYPES[type_name]])
            + struct.pack(">IH", expiration, maxspeed) + bytes([len(comment)]) + comment
            + bytes.fromhex(ev["sig"]))


def vote_frame(ev, flags=0x08):
    status = 1 if ev["tags"][1][1] == "still_there" else 0
    return (bytes([flags]) + bytes.fromhex(ev["pubkey"]) + struct.pack(">I", ev["created_at"])
            + bytes.fromhex(ev["tags"][0][1]) + bytes([status]) + bytes.fromhex(ev["sig"]))


def key(n):
    return hashlib.sha256(f"road-events-v1 test key {n}".encode()).digest()


def report(seckey, created_at, lat_e6, lon_e6, type_name, content="", maxspeed=None):
    exp = created_at + max(1_209_600, LIFETIME[type_name])
    ev = sign_event(seckey, created_at, 1315, report_tags(lat_e6, lon_e6, type_name, exp, maxspeed), content)
    return ev, report_frame(ev, lat_e6, lon_e6, type_name, exp, maxspeed or 0)


def vote(seckey, created_at, report_id, still_there):
    tags = [["e", report_id], ["status", "still_there" if still_there else "no_longer_there"]]
    ev = sign_event(seckey, created_at, 1316, tags, "")
    return ev, vote_frame(ev)


def main():
    t0 = 1_761_552_000  # 2025-10-27T08:00:00Z
    valid, decode_only, decode_invalid, encode_invalid = [], [], [], []

    def ok(name, ev, frame, note=None):
        v = {"name": name, "event": ev, "frame": frame.hex()}
        if note:
            v["note"] = note
        valid.append(v)

    police, police_frame = report(key(1), t0, 48_148_598, 17_107_748, "police")
    ok("report_minimal", police, police_frame)
    cam, cam_frame = report(key(2), t0 + 60, 48_716_385, 21_261_074, "speed_camera", "Mobile radar", 90)
    ok("report_maxspeed_comment", cam, cam_frame)
    comment46 = "Pozor, zľadovatená cesta za mostom!!"
    comment46 += "x" * (46 - len(comment46.encode()))
    ice, ice_frame = report(key(3), t0 + 120, 49_195_060, 16_606_837, "ice", comment46)
    assert len(ice_frame) == 163
    ok("report_comment_46_bytes_utf8", ice, ice_frame, "largest frame an encoder may produce: 163 bytes")
    south, south_frame = report(key(4), t0 + 180, -33_868_820, -151_209_290, "other")
    ok("report_negative_coordinates_other", south, south_frame)
    edge, edge_frame = report(key(5), t0 + 240, 90_000_000, -180_000_000, "road_closure", "", 5)
    ok("report_coordinate_extremes_maxspeed_5", edge, edge_frame)
    zero, zero_frame = report(key(6), t0 + 300, 0, -500_000, "animal")
    ok("report_zero_latitude", zero, zero_frame)
    yes, yes_frame = vote(key(7), t0 + 600, police["id"], True)
    ok("vote_still_there", yes, yes_frame)
    no, no_frame = vote(key(8), t0 + 660, police["id"], False)
    ok("vote_no_longer_there", no, no_frame)

    # Frames a decoder must accept but an encoder must never produce.
    decode_only.append({"name": "report_ephemeral_flag", "frame": (bytes([0x04]) + police_frame[1:]).hex(),
                        "event": police, "note": "flag bit 2 is unsigned; same event as report_minimal"})
    decode_only.append({"name": "vote_ephemeral_flag", "frame": (bytes([0x0C]) + yes_frame[1:]).hex(),
                        "event": yes})
    for extra in (47, 48):
        c = "y" * extra
        ev, fr = report(key(9), t0 + 900 + extra, 48_148_598, 17_107_748, "hazard", c)
        decode_only.append({"name": f"report_comment_{extra}_bytes", "frame": fr.hex(), "event": ev,
                            "note": f"{len(fr)} bytes: valid, but over the 163-byte transport limit"})

    def bad(name, frame, error):
        decode_invalid.append({"name": name, "frame": frame.hex(), "error": error})

    pf = bytearray(police_frame)
    bad("report_truncated", bytes(pf[:-1]), "BAD_LENGTH")
    bad("report_comment_length_mismatch", bytes(pf[:52]) + bytes([1]) + bytes(pf[53:]), "BAD_LENGTH")
    bad("report_with_vote_class", bytes([0x08]) + bytes(pf[1:]), "BAD_LENGTH")
    bad("report_version_1", bytes([0x10]) + bytes(pf[1:]), "BAD_VERSION")
    bad("report_reserved_bit", bytes([0x01]) + bytes(pf[1:]), "RESERVED_BITS")
    bad("report_type_14", bytes(pf[:45]) + bytes([14]) + bytes(pf[46:]), "UNKNOWN_TYPE")
    bad("report_latitude_out_of_range", bytes(pf[:37]) + struct.pack(">i", 90_000_001) + bytes(pf[41:]),
        "COORDINATE_RANGE")
    bad("report_maxspeed_4", bytes(pf[:50]) + struct.pack(">H", 4) + bytes(pf[52:]), "BAD_SPEED_LIMIT")
    bad("report_maxspeed_301", bytes(pf[:50]) + struct.pack(">H", 301) + bytes(pf[52:]), "BAD_SPEED_LIMIT")
    long_comment = bytes(pf[:52]) + bytes([49]) + b"z" * 49 + bytes(pf[53:])
    bad("report_comment_49_bytes", long_comment, "BAD_LENGTH")
    bad("report_invalid_utf8", bytes(pf[:52]) + bytes([2, 0xC3, 0x28]) + bytes(pf[53:]), "NON_CANONICAL")
    bad("report_control_character", bytes(pf[:52]) + bytes([2, 0x41, 0x0A]) + bytes(pf[53:]), "NON_CANONICAL")
    sig_flip = bytearray(pf)
    sig_flip[-1] ^= 0x01
    bad("report_signature_flipped", bytes(sig_flip), "SIGNATURE_INVALID")
    bad("report_created_at_changed", bytes(pf[:36]) + bytes([pf[36] ^ 1]) + bytes(pf[37:]), "SIGNATURE_INVALID")
    yf = bytearray(yes_frame)
    bad("vote_status_2", bytes(yf[:69]) + bytes([2]) + bytes(yf[70:]), "BAD_STATUS")
    bad("vote_status_flipped", bytes(yf[:69]) + bytes([0]) + bytes(yf[70:]), "SIGNATURE_INVALID")
    bad("vote_with_report_class", bytes([0x00]) + bytes(yf[1:]), "BAD_LENGTH")
    bad("vote_too_long", bytes(yf) + b"\x00", "BAD_LENGTH")

    def refuse(name, ev, error):
        encode_invalid.append({"name": name, "event": ev, "error": error})

    def resign(seckey, created_at, kind, tags, content):
        return sign_event(seckey, created_at, kind, tags, content)

    base_tags = police["tags"]
    k = key(1)
    refuse("seven_decimals", resign(k, t0, 1315, [["lat", "48.1485980"]] + base_tags[1:], ""), "NON_CANONICAL")
    refuse("comma_decimal", resign(k, t0, 1315, [["lat", "48,148598"]] + base_tags[1:], ""), "NON_CANONICAL")
    refuse("negative_zero", resign(k, t0, 1315, [base_tags[0], ["lon", "-0.000000"]] + base_tags[2:], ""),
           "NON_CANONICAL")
    refuse("alt_tag", resign(k, t0, 1315, base_tags + [["alt", "Roadstr: police report"]], ""), "NON_CANONICAL")
    refuse("old_tag_order", resign(k, t0, 1315, [base_tags[5]] + base_tags[2:5] + base_tags[:2] + base_tags[6:], ""),
           "NON_CANONICAL")
    refuse("wrong_geohash", resign(k, t0, 1315, base_tags[:4] + [["g", "u2s8vw"]] + base_tags[5:], ""),
           "NON_CANONICAL")
    refuse("expiration_leading_zero",
           resign(k, t0, 1315, base_tags[:6] + [["expiration", "0" + base_tags[6][1]]], ""), "NON_CANONICAL")
    refuse("expiration_over_uint32",
           resign(k, t0, 1315, base_tags[:6] + [["expiration", "4294967296"]], ""), "TIMESTAMP_RANGE")
    refuse("maxspeed_leading_zero", resign(k, t0, 1315, base_tags + [["maxspeed", "090"]], ""), "BAD_SPEED_LIMIT")
    refuse("maxspeed_4", resign(k, t0, 1315, base_tags + [["maxspeed", "4"]], ""), "BAD_SPEED_LIMIT")
    refuse("unknown_type", resign(k, t0, 1315, base_tags[:5] + [["t", "tornado"]] + base_tags[6:], ""),
           "UNKNOWN_TYPE")
    refuse("comment_47_bytes", resign(k, t0, 1315, base_tags, "c" * 47), "COMMENT_TOO_LONG")
    refuse("comment_control_character", resign(k, t0, 1315, base_tags, "line\nbreak"), "NON_CANONICAL")
    refuse("wrong_kind", resign(k, t0, 1, base_tags, ""), "WRONG_KIND")
    refuse("created_at_over_uint32", dict(police, created_at=4_294_967_296), "TIMESTAMP_RANGE")
    refuse("vote_with_content", resign(key(7), t0 + 600, 1316, yes["tags"], "yes"), "NON_EMPTY_CONTENT")
    refuse("vote_with_position",
           resign(key(7), t0 + 600, 1316, yes["tags"] + [["g", "u2s8"]], ""), "NON_CANONICAL")
    refuse("vote_bad_status",
           resign(key(7), t0 + 600, 1316, [yes["tags"][0], ["status", "maybe"]], ""), "BAD_STATUS")
    refuse("tampered_signature", dict(police, sig=police["sig"][:-2] + "00"), "SIGNATURE_INVALID")

    doc = {
        "description": "Conformance vectors for nips/road-events.md (kinds 1315/1316, frame version 0). "
                       "Generated by tools/gen_road_event_vectors.py; do not edit by hand.",
        "version": 1,
        "limits": {"report_base": 117, "report_max_encode": 163, "report_max_decode": 165,
                   "max_comment_encode": 46, "max_comment_decode": 48, "vote": 134},
        "valid": valid,
        "decode_only": decode_only,
        "decode_invalid": decode_invalid,
        "encode_invalid": encode_invalid,
    }
    print(json.dumps(doc, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
