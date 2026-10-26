NIP-XX — moved
==============

This file used to hold the specification of the road-event format (kinds 1315/1316).

It now lives at [`road-events.md`](road-events.md). The old path is kept because other
implementations link to it, and because the format itself is still called "the
jooray/roadstr format" in older documents.

The format is **v1 and frozen**: the `alt` tag still reads `"Roadstr: …"`, which is part of
every event ID and required by the 110-byte frame. Renaming the app did not rename the format.

See [`road-events.md`](road-events.md) for the specification and
[`../docs/ecosystem.md`](../docs/ecosystem.md) for the coexistence plan with the independent
Roadstr app.
