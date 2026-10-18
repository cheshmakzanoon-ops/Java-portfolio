# Performance methodology

The prescribed generator creates100000 journals across20 tenants and10000 accounts with seed20261017, CAD/USD/JPY, 20% four-line journals, duplicate retries every100 inputs and reversals every1000 (100000 original journals plus100 reversal journals and1000 duplicate submissions in the full run). It computes expected balances with the independent reference before invoking SQL and writes `target/benchmark/rN-wN/expected-balances.csv`.

Run `scripts/benchmark.sh 100000 3` on an otherwise idle host. It builds first, then runs1/8/32 bounded Hikari writer pools in three fresh-database rounds. Raw latency CSVs and result envelopes live under target/benchmark. `scripts/benchmark.sh 1000 1` is a smoke run and cannot satisfy the full workload gate.

Measured boundary: closed-loop JDBC transactions including retry and duplicate/reversal work associated with each input. Results report throughput, p50/p95/p99, elapsed time, retry count, sampled concurrent lock waiters, reporting backlog/replay duration, sampled peak Java heap, JVM flags and runtime, CPU, cores, cgroup limit and relevant database durability settings. The database and clients share the host; the API network path is excluded. There is no separated warmup because writes use a fresh durable dataset. Closed-loop latency underestimates open-loop overload queue delay. Lock waiter counts are sampled observations, not aggregate wait-duration measurements; sampled Java heap is not process RSS. Do not infer production capacity.

See STATUS and committed evidence for exactly which measurements were actually run. The first scaling bottleneck is the deliberate per-period lock; reducing it requires a new close/post proof rather than removing the fence for a prettier number.
