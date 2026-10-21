# Local force before mutation

The finite simulator uses one platform writer, a mature ArrayBlockingQueue and one force per command. Group commit could improve throughput, but batch size one gives a smaller crash-state surface and an unambiguous baseline. Pending identities are still coalesced before sequence allocation, so adding batching cannot rely solely on applied outcomes for deduplication.

We rejected timestamp priority, floating-point money, a concurrent mutable order book and a homegrown lock-free queue. Admission order is deterministic, checked integer reserves have exact semantics, and index ownership is inspectable. Immutable aggregate depth avoids copying every retained execution/result on every acknowledgement; full state is captured only at an idle verification/snapshot boundary.

The complete journal remains authoritative even with snapshots. Validating the log still costs sequential I/O; snapshots avoid replaying the validated prefix but do not promise constant-time startup. CRCs detect accidental corruption, not hostile storage modification; no cryptographic authenticity is claimed.

A resting order can only have GTC policy. A positive-quantity replacement explicitly rejects a request to change that policy to IOC/FOK with TIF_CHANGE; a caller cancels and submits a new identity to change policy. This avoids silently ignoring a requested policy on the priority-preserving reduction path. Zero remaining remains cancellation. The HTTP adapter requires the explicit newRemainingQtyLots field to distinguish remaining from lifetime total quantity.
