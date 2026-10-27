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
| Payload | exactly one frame: 117–165 bytes (report) or 134 bytes (vote) |

The channel is public: anyone who knows the name can derive the key. It is a shared meeting point,
not a privacy feature. `0xFF52` lives in the firmware's unregistered testing range; a stable value
in `0100–FEFF` should be registered with MeshCore once hardware validation is done, with both apps
named in the request.

## Sending

1. The event must already be canonical and signed. The sender encodes it with
   `BinaryCodec.encodeReport` / `encodeConfirmation`; encoding refuses anything that would not
   rebuild byte for byte.
2. The frame goes out as one channel-data datagram (`0x3E` on the companion protocol) via the local
   API's `channel.sendData`, or directly when a client owns BLE.
3. The radio acknowledges the **command**; that is not delivery. There is no delivery receipt for
   flood traffic.
4. Limits: 163 bytes through BLE (the notification bound), 165 on the radio; comments over 46
   bytes do not fit a BLE-sent report and stay relay-only.

## Receiving

1. Decode the frame, rebuild the event and **verify the signature** before anything else.
2. Drop it if it is expired (`expiration`), older than its type's lifetime, or an event ID already
   seen on this transport.
3. Show it like any other report; provenance is "received over MeshCore".
4. Votes are self-contained (no report lookup); a vote heard before its report is applied when the
   report arrives, or dropped.

## Bridging

A node with internet may bridge in either direction. It never signs and never changes an event:

* **radio → relays**: publish the rebuilt event unchanged, with the original author, ID and
  signature.
* **relays → radio**: send only frames that encode exactly, are still live, and are near the driver.

Loop prevention is by event ID, not by content: a ledger records on which transports each ID was
seen and to which it was already sent (bounded LRU, persisted for the radio side so a restart does
not re-broadcast).

Recommended limits (both clients):

| Limit | Default |
|---|---|
| own events to radio | 6 / min |
| other authors to radio | 20 / h, 4 / author / h, 20 s gap |
| relay events put on radio | within 40 km of the driver |
| frames accepted from radio | 60 / min |
| waiting republish queue | 100, encrypted at rest |

Roadbeacon currently implements the rate limits and the radio→relay bridge; the 40 km area filter
for relay→radio is not wired yet (it sends anything canonical, which the radio's range bounds in
practice). Roadstr implements the area filter through its existing bridge policy.

## Local API and pairing

MeshCore Open's API is opt-in, loopback-only (`ws://127.0.0.1:8787/api/v1`) and tokenless:

* each client holds a P-256 key; the first connection shows **“Allow Roadbeacon (AB12 CD34 …)?”**
  in MeshCore Open, and the approved key is remembered;
* reconnects re-prove the key without a prompt;
* pairing can be revoked, which forgets the key.

Protocol, methods and errors: `documentation/LOCAL_API.md` in the MeshCore Open fork
(`feat/local-api` branch), upstream issue
[zjs81/meshcore-open#616](https://github.com/zjs81/meshcore-open/issues/616).

## Failure modes

| Situation | Behaviour |
|---|---|
| API not running / radio disconnected | `send` returns not-connected; the client retries with backoff |
| firmware older than channel data (ver < 11) | API reports unsupported; client stops trying |
| channel `#roadstr` missing | subscribe fails; the user creates it in MeshCore Open |
| duplicate frames | dropped by event ID |
| signature invalid | dropped before display or forwarding |
| clock far in the future | dropped as stale |

## Validation status

* Codec: unit tests in Roadbeacon (frames, comments, maxspeed, canonical refusals); Roadstr builds
  but its old codec tests have not been updated to the new frame.
* Local API: 8 server tests against a fake host (pairing, bad signature, deny, filters, limits).
* **Hardware: not tested.** No radio was available. The checklist is: pair a radio, create the
  channel, send a report A→B, compare event IDs, pull the BLE link mid-send, bridge offline→online,
  and record airtime with default limits.
