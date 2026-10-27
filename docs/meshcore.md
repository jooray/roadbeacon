# MeshCore transport — how road events travel over LoRa

Status: implemented in Roadbeacon and Roadstr (test builds), MeshCore Open local API on a branch.
Not yet validated on a physical radio. This document is the operational half of the format; the
bytes are specified in [`nips/road-events.md`](../nips/road-events.md).

## Topology

```
phone A                         phone B
┌──────────────────┐            ┌──────────────────┐
│ Roadbeacon /     │  local     │ MeshCore Open    │
│ Roadstr          │◄──WS──────►│ (owns BLE)       │
└──────────────────┘  API       └────────┬─────────┘
                                        BLE
                                   ┌────┴─────┐
                                   │ MeshCore │  LoRa
                                   │  radio   │◄────────► other radios
                                   └──────────┘
```

One process owns the BLE link. MeshCore Open keeps the radio for chat and exposes a loopback API;
road-event clients subscribe to a channel and send through it. No app takes the radio over, so chat
keeps working while reports flow.

## Channel and data type

| | |
|---|---|
| Channel | `#roadstr` (a MeshCore hashtag channel) |
| Channel key | first 16 bytes of `SHA-256("#roadstr")` = `b7f4317db7b03aeb563b2c4bdc476d6a` |
| `data_type` | `0xFF52` |
| Payload | exactly one frame: 117–163 bytes (report; up to 165 accepted) or 134 bytes (vote) |

The channel is public: anyone who knows the name can derive the key. It is a shared meeting point,
not a privacy feature. `0xFF52` lives in the firmware's unregistered testing range; a stable value
in `0100–FEFF` should be registered with MeshCore once hardware validation is done, with both apps
named in the request.

## Sending

1. The event must already be canonical and signed. The sender encodes it with
   `BinaryCodec.encodeReport` / `encodeConfirmation`; encoding refuses anything that would not
   rebuild byte for byte, a comment over 46 bytes, and a comment with control characters.
2. The frame goes out as one channel-data datagram (`0x3E` on the companion protocol) via the local
   API's `channel.sendData`, or directly when a client owns BLE.
3. The radio acknowledges the **command**; that is not delivery. There is no delivery receipt for
   flood traffic.
4. Limit: 163 bytes per datagram through BLE and the local API (165 on the radio itself), which is
   why encoders stop at a 46-byte comment.

## Receiving

1. Decode the frame, rebuild the event and **verify the signature** before anything else.
2. Drop it if it is dated more than 5 minutes in the future, past its `expiration`, past its
   category's lifetime (a report), older than 30 days (a vote), or an event ID already seen.
3. Show it like any other report.
4. Votes are self-contained (no report lookup). A vote heard before its report is bridged to the
   relays at once and kept for 15 minutes for display; it is applied when the report arrives, from
   the radio or from a relay, or dropped.

## Bridging

A node with internet may bridge in either direction. It never signs and never changes an event:

* **radio → relays**: publish the rebuilt event unchanged, with the original author, ID and
  signature (subject to the receive limit below).
* **relays → radio**: only an event that encodes exactly, whose report is still on the map (votes
  included) and lies within 40 km of a position fix younger than 10 minutes. A vote uses its report's
  position. The bridge then waits 3–15 s at random and sends only if the event has not been heard on
  the radio meanwhile, so that of several bridges in range usually one transmits.
* **own events → radio**: sent when made; while the radio is away they wait (up to 20, at most an
  hour) and go out when it comes back.

Loop prevention is by event ID, not by content: a ledger (bounded LRU, in memory) records whether
an ID was heard on the radio, seen on a relay or already sent to the radio. A radio event that comes
back from a relay is never sent again; after a restart the "still on the map, near you, not heard
during the pause" rules keep re-sending rare.

Limits (Roadbeacon; Roadstr uses the same numbers):

| Limit | Value |
|---|---|
| own events to radio | 6 / min, backlog 20 for up to 1 h |
| relay events to radio | 20 / h, 4 / author / h, 20 s gap, random 3–15 s hold |
| relay events put on radio | within 40 km of a fix younger than 10 min |
| frames accepted from radio | 60 / min |
| votes waiting for their report | 64 (32 reports), 15 min |

The replay cursor is the `receivedAt` (MeshCore Open's clock) of the last frame processed, sent back
as `since` on the next subscribe; replays are in order and duplicates are dropped by event ID.

## Local API and pairing

MeshCore Open's API is opt-in, loopback-only (`ws://127.0.0.1:8787/api/v1`; Roadbeacon refuses any
other host) and tokenless:

* each client holds a P-256 key; the first connection shows **“Allow Roadbeacon (AB12 CD34 …)?”**
  in MeshCore Open. The name is self-declared, the fingerprint is not: Roadbeacon shows its own
  under *Settings → MeshCore → Pairing fingerprint* to compare;
* the client signs `meshcore-open-pair-v1:<nonce>`, built by itself from the challenge nonce, once
  per connection — never a string the server chose;
* reconnects re-prove the key without a prompt; every authenticated connection subscribes again;
* pairing can be revoked in MeshCore Open, which ends the live session (close 4003). A refused or
  revoked client retries every 2 minutes and shows "Not approved".

Protocol, methods and errors: `documentation/LOCAL_API.md` in the MeshCore Open fork
(`feat/local-api` branch), upstream issue
[zjs81/meshcore-open#616](https://github.com/zjs81/meshcore-open/issues/616).

## Failure modes

| Situation | Behaviour |
|---|---|
| API not running / radio disconnected | own events wait in the backlog; the client reconnects with backoff (5 s … 60 s) |
| pairing refused or revoked | "Not approved", retry every 2 min |
| firmware without channel data | MeshCore Open answers `unsupported_firmware`; nothing is sent |
| channel `#roadstr` missing | subscribe fails, status says so; create the channel in MeshCore Open |
| duplicate frames | dropped by event ID |
| signature invalid | dropped before display or forwarding |
| clock far in the future | dropped |

## Validation status

* Codec: Roadbeacon runs the shared vectors (`nips/vectors/road-events-v1.json`) on Android and web;
  Roadstr runs the same file.
* Bridge: `MeshBridgeTest` (fake radio, signed vector events): verification, dedupe, parking,
  staleness, cursor, backlog, relay→radio area/hold/gap, receive limit.
* Local API: server tests in MeshCore Open against a fake host.
* **Hardware: not tested.** No radio was available. The checklist is: pair a radio, create the
  channel, send a report A→B, compare event IDs, pull the BLE link mid-send, bridge offline→online,
  and record airtime with default limits.
