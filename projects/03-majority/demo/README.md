# Asserted live fault scenario

Run `../scripts/demo.sh`. This creates a fresh project-owned ignored directory and starts three actual Java25 members with separate stable disks and TLS identities. It exits nonzero on any assertion failure and always terminates its owned JVMs.

Expected observations: committed PUT; failed CAS with its exact observed value; successful CAS; isolated leader GET cannot return OK; isolated mutation has unknown outcome and cannot acknowledge; two communicating members elect and commit; healed state digests agree; the independent bounded checker returns PASS including the timed-out mutation; snapshots publish durably; killing all JVMs and restarting preserves acknowledged register/session state. A duplicate request after restart returns its original result.

The output JSON and checked invocation/response history are generated from live operations. Timeouts are included in the history as potentially applied operations; they are never deleted to improve a correctness result. The throughput driver runs separately and does not reuse fault-demo timings as a benchmark.
