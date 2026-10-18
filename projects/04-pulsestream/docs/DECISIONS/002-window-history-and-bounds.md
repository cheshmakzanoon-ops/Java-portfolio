# ADR 002: Shared panes, finite run histories

Accepted: retain one-minute panes until all five dependent sliding windows finalize. Keep finalized outputs and append-only dedup/quarantine/outbox in SQL, rather than retaining finalized windows in the engine. Keep durable dedup payload hashes for the complete finite run; no wall-clock TTL.

Late classification belongs to a window contribution, not to the whole measurement. After watermark 100 seconds, an event at second 40 cannot change tumbling `[0,60)` or sliding `[-240,60)` but can change the four sliding windows ending 120/180/240/300 seconds. Shared panes can therefore change after a former dependent window has finalized; the final SQL result is immutable and is never recomputed from that pane.

Key admission is at most 50,000 over the run lifetime; open panes and durable event IDs have separately configurable caps. Reject rather than silently evict unfinished state. Dedup grows approximately linearly with unique event IDs and is additionally loaded into the finite engine state at recovery. Archival is an explicit local operational SQL procedure; it is not automatic retention.
