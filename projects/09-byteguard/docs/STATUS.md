# ByteGuard verification status

The current Linux implementation passes its local functional, accuracy, resource-bound and full-scan gates. Repository-wide clean-checkout, hosted CI and final release checks remain separate.

Source fingerprint: `a6055cdfb18245240a3341c4a712c256e5502541dcdb85523e662423d7edf09c`. Packaged CLI SHA-256: `390a7a39ef6041fdcbc347b5a0be437eead09a9feb0bb2277b11951a6783db93`. The measured artifact and runtime cache match this identity.

| Check | Actual result | Evidence |
| --- | --- | --- |
| Fresh clean reactor, integration and critical mutation profile | 65 methods, zero failures/errors/skips; command exit0 at2026-10-18T03:30:39Z; JUnit6.1.3/JDK25.0.4.1 | [verification](../evidence/verification.json) |
| Original core coverage, aggregate unit/evaluation tests | 1475/1564 lines (94.31%);893/1069 branches (83.54%); gates85%/75% | [coverage](../evidence/coverage.json) |
| Original critical algorithm mutations | 164/177 killed (92.66%);13 survivors; zero uncovered/timeouts/runner errors; unchanged70% gate | [mutation](../evidence/mutation.json) |
| Frozen v2 corpus | 60TP/60TN/0FP/0FN; each rule20TP/20TN | [all cases](../evidence/corpus-results.json) |
| Full10,000-class scan, three independent JVMs | 3.9325/4.0275/4.3934seconds; every run complete,10,000 unique findings; `-Xmx2g`, four-core quota | [benchmark envelope](../evidence/benchmark-envelope.json) |
| Real current CLI lifecycle | reset/up/demo/repeated-demo/down all exit0; both demos assert scanner exits1/0/3/0 | [demo](../evidence/demo.json) |
| Independent review | A08 final source/report/descriptor/raw-scan review closed, no unresolved concrete finding | [review](REVIEW.md) |

R11 adds linear JVM descriptor validation before argument arrays or entry-frame setup, bounded255 parameter slots including receiver/wide values,255 array dimensions, compact SHA-256 method scopes over raw UTF-16 code units and entry work charging. A valid long method identity and255-argument input completes in an actual64MiB child. The original65,627-byte invalid input now returns2 with failed SARIF invocation in0.5172seconds, without OOM; its SHA and actual child metrics are [preserved](../evidence/descriptor-regression/evidence.json). Distinct lone surrogates retain distinct scopes.

Reproduce the full verification from the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/09-byteguard/pom.xml -Dmaven.repo.local="$PWD/.work/A09/m2" -B -ntp -Pintegration,mutation clean verify
```

Run the benchmark only in a reserved quiet host; see [performance](PERFORMANCE.md). No target class is loaded or executed by scanning. Bounded supported analysis is the declared contract; object-insensitive cross-call heap and shell positional-argument false positives remain disclosed.

Historical failures remain intact: initial111/164 mutation failure, alias checkpoint/lambda failure, missing-time-utility benchmark preflight and the old enclosing-session exit1 despite reactor success. `verification-before-r11.json`, `benchmark-before-r11.json` and `history-before-r11/` retain old identities. The historical exit discrepancy is unexplained; a new full clean command independently returned0. Old success was not rewritten. Root-wide clean validation and final release acceptance remain open.
