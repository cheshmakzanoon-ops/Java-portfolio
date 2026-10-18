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
10. Read PERFORMANCE.md and a JFR result. Identify force frequency, closed-loop coordinated omission, allocation and snapshot validation cost before proposing group commit.

Potential live change: adjust the max open-order cap, write a boundary test and run the existing generated agreement suite. Potential scaling work: group commit with the same pending-ID semantics, compact binary payloads after profiling, and external checkpoint storage. A multi-instrument or replicated exchange is a different failure/atomicity contract.
