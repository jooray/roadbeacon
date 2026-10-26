NIP-XX — moved, and the layout changed
======================================

This path used to hold the road-event specification. It now lives at
[`road-events.md`](road-events.md).

Two things changed in the 0.4.0 revision:

* The **layout** is now the one the independent Roadstr client writes natively (`lat, lon, g, g, g,
  t, expiration[, maxspeed]`, six decimals, two-tag votes, no `alt`). The previous layout is
  deprecated and described in Appendix A of the new document.
* The **frame** was redefined over that layout: 117–165 bytes for a report, a self-contained 134-byte
  vote. The old 110-byte frame is obsolete.

The path is kept because other implementations link to it. See
[`../docs/ecosystem.md`](../docs/ecosystem.md) for the migration and the two apps.
