# Fifteen-minute technical walkthrough

1. Run scripts/demo.sh and explain why a timed-out cancellation can still execute.
2. Trace HttpApi subject/scopes through Gateway pending coalescing, journal force, Book transition and acknowledgement.
3. Find Book's price TreeMaps, linked FIFO and direct identity index. Cancel the middle of a price level and explain the pointer updates.
4. Explain the difference between quantity reduction and increased remaining quantity, and why maker price releases buy price improvement.
5. Show the FOK preflight and its self-trade blocker. Explain why earlier IOC/GTC fills may stand before a self-blocker but FOK changes nothing.
6. Prove additions cannot overflow after preflight using the aggregate supply bound. Run the Long.MAX_VALUE transfer test.
7. Read the preserved zero-replace property counterexample. Explain why independently structured oracles catch errors that implementation-mirroring tests miss.
8. Inspect CrashIT and distinguish process termination, injected storage failure and hardware power loss.
9. Read the journal header checksum and explain why a corrupt length is not treated as a torn tail. Show safe snapshot fallback.
10. Open [the six-run CSV](../evidence/reference-20261018/system-runs.csv) and [PERFORMANCE.md](PERFORMANCE.md). Explain why median core 113,277 commands/s, durable 1,706 commands/s and JMH 1.194 million partial fills/s are different boundaries. Show all three repetitions; explain why the faster second core run is retained and the sample count does not justify a population guarantee.
11. Explain why a 27-order peak does not exercise the 100,000 cap, and why one million retained outcomes can still produce multi-GiB whole-process RSS. Distinguish loop-end heap, live-thread allocation deltas, JFR sampled allocation weights and native-inclusive RSS.
12. Trace a JFR stack through public-view construction or digest verification. Explain why its whole-process CPU samples cannot measure blocked force latency, and why a logged 502.504 ms GC pause is not automatically a measured-loop acknowledgement sample. Point to the actual batch size one, force counts, loop CPU/GC counters and warm-JVM/hot-cache recovery boundaries before proposing group commit.

Potential live change after the measurement freeze is released: adjust the max open-order cap, write a boundary test and run the existing generated agreement suite. Potential scaling experiments: group commit with the same pending-ID semantics, lower-allocation immutable publication, streaming full-state digests, and compact payloads after profiling. None is an implemented measured speedup. A multi-instrument or replicated exchange requires a different failure/atomicity contract. Clean-checkout and repository-wide release evidence remains a separate final gate.
