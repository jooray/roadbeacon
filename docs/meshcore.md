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
| `data_type` | `0x0150` "Road Events", sent and received |
| Payload | exactly one frame: 117–163 bytes (report; up to 165 accepted) or 134 bytes (vote) |

The channel is public: anyone who knows the name can derive the key. It is a shared meeting point,
not a privacy feature. `0x0150` "Road Events" is allocated in MeshCore's registry
(`docs/number_allocations.md`, [meshcore-dev/MeshCore#3750](https://github.com/meshcore-dev/MeshCore/pull/3750)),
with both apps named. Test builds before 0.5.2 (Roadbeacon) and the first Roadstr road-test builds
sent the development value `0xFF52`; it is no longer sent or accepted, so those builds must be
updated.

## Sending

1. The event must already be canonical and signed. The sender encodes it with
   `BinaryCodec.encodeReport` / `encodeConfirmation`; encoding refuses anything that would not
   rebuild byte for byte, a comment over 46 bytes, and a comment with control characters.
2. The frame goes out as one channel-data datagram (`0x3E` on the companion protocol) via the local
   API's `channel.sendData`, or directly when a client owns BLE. Without a `region` MeshCore Open
   applies the channel's or the radio's own flood scope, which is the default. Roadbeacon passes
   `region` only when *Settings → MeshCore → Flood region* is set; MeshCore Open refuses a name it
   does not know (`unknown_region`) rather than send a flood no repeater forwards.
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

After `hello` the client follows the radio rather than the socket (`MeshApiSession` holds the
decisions, `MeshApiSessionTest` checks them):

1. `hello.device.connected` and the `device` message say whether MeshCore Open is connected to the
   radio and has loaded its channels. The initial state comes from `hello`; after it, MeshCore Open
   sends `device` unprompted whenever that changes or the channel list changes. It carries `device`
   (as in `hello`) and `channels`.
2. Radio connected: `events.subscribe` with `channel: "#roadstr"`, `dataTypes: [0x0150]` and
   `since` (the cursor). Not connected: no subscription; the status says so, the socket stays open,
   and the next `device` with `connected: true` subscribes.
3. A subscription stays in place while the radio is away and comes back. A `device` message with
   `connected: true` subscribes only a connection that holds no subscription (the earlier one
   failed or never happened); subscribing again would deliver every event twice (and a connection
   may hold at most 16).
4. `unknown_channel` while the radio is connected means the radio has no `#roadstr`. The client
   calls `channels.join {"name":"#roadstr"}` once per connection. MeshCore Open answers at once if
   the channel exists, otherwise asks the user (up to 2 minutes, with a notification when in the
   background), so this request waits 150 s instead of the usual 15 s. On success the client
   subscribes. On `denied` it shows "Add #roadstr in MeshCore Open" and does not ask again for
   10 minutes, or until *Use MeshCore radio* is switched off and on. Join errors: `invalid_params`
   (the name must match `^#[a-z0-9-]{1,31}$`), `not_connected`, `denied`, `no_free_slot`,
   `channel_conflict`, `rate_limited`, `pairing_pending`.
5. A server that never sends `device` (an older MeshCore Open) gets a slow fallback: a connection
   left without a subscription reads `device.info` and subscribes again every 60 s.
6. `channel.data` events carry `hops`: the flood hop count (`0` = heard from the sender's radio),
   `null` for a direct path or an own send. Roadbeacon keeps it with the datagram and does not show
   it yet.

*Settings → MeshCore → Open MeshCore Open* launches `com.meshcore.meshcore_open` for pairing,
connecting the radio or adding the channel.

Protocol, methods and errors: `documentation/LOCAL_API.md` in the MeshCore Open fork
(jooray/meshcore-open, branch `feat/local-api`; `device`, `channels.join` and `hops` are
additions to protocol 1), upstream issue [zjs81/meshcore-open#616](https://github.com/zjs81/meshcore-open/issues/616).

## Failure modes

| Situation | Behaviour |
|---|---|
| API not running | own events wait in the backlog; the client reconnects with backoff (5 s … 60 s) |
| pairing refused or revoked | "Not approved", retry every 2 min |
| MeshCore Open not connected to the radio (`connected: false`, or `unknown_channel` while so) | "MeshCore Open is not connected to the radio"; socket kept, subscribes on the next `device` with `connected: true` |
| channel `#roadstr` missing (`unknown_channel` while connected) | `channels.join` once per connection; "Waiting for you to allow in MeshCore Open" while the prompt is open |
| user denies adding the channel | "Add #roadstr in MeshCore Open"; not asked again for 10 min or until the switch is toggled; a `device` listing `#roadstr` subscribes |
| join fails otherwise (`no_free_slot`, `channel_conflict`, `rate_limited`, …) | "The radio has no #roadstr channel; add it in MeshCore Open (code)" |
| subscription refused with another code | "MeshCore Open refused the subscription (code)" |
| server without `device` messages | `device.info` and a new subscription every 60 s while unsubscribed |
| flood region unknown to MeshCore Open (`unknown_region`) | sends refused; status names the region until a send goes through |
| firmware without channel data | MeshCore Open answers `unsupported_firmware`; nothing is sent |
| duplicate frames | dropped by event ID |
| signature invalid | dropped before display or forwarding |
| clock far in the future | dropped |

## Validation status

* Codec: Roadbeacon runs the shared vectors (`nips/vectors/road-events-v1.json`) on Android and web;
  Roadstr runs the same file.
* Bridge: `MeshBridgeTest` (fake radio, signed vector events): verification, dedupe, parking,
  staleness, cursor, backlog, relay→radio area/hold/gap, receive limit.
* Local API: server tests in MeshCore Open against a fake host; `MeshApiSessionTest` for the
  client's decisions (radio state, one subscription per connection, join, denial pause, fallback
  retry, data types, region, hops).
* **Hardware: not tested.** No radio was available. The checklist is: pair a radio, create the
  channel, send a report A→B, compare event IDs, pull the BLE link mid-send, bridge offline→online,
  and record airtime with default limits.
