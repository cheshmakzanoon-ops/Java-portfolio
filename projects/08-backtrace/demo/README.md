# Reproducible scenarios

Run `./scripts/portfolio.sh demo backtrace` from the repository root. The implementation is `../scripts/demo.sh`; it invokes the actual `workloads` module and offline CLI. No recorded result is replayed as a live demo.

| Scenario | Controlled regression | Required observed evidence |
| --- | --- | --- |
| CPU | Fourfold planted arithmetic work per operation | `plantedCpuHotspot` appears in observed execution samples |
| Allocation | Larger byte arrays with the same CPU component | Weighted byte-array allocation events are present |
| Monitor contention | Longer holds of `PlantedLock` | Observed monitor-enter duration on that lock class |
| Virtual threads | Four virtual workers contending on the same planted monitor | Supported monitor/park evidence; mounted execution attribution remains incomplete |

Each variant receives a fresh JVM, one second warmup and four seconds measurement. Generated recordings, producer settings, measurement JSON, normalized reports and differential HTML are under `../target/demo/`. Assertions inspect the events actually collected. The separate `../scripts/regression-trials.sh` executes seven randomized baseline/regressed pairs for CPU/allocation/contention and requires at least six identified pairs per cause. These short diagnostic studies do not establish the overhead acceptance gate.

`../scripts/benchmark.sh` runs the full independent overhead protocol described in `../docs/PERFORMANCE.md`; `--smoke` is explicitly ineligible for acceptance. `../scripts/down.sh` preserves reports. `../scripts/reset-demo.sh` deletes only generated demo output.
