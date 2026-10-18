# Reviewer demonstration (three to five minutes)

Run `scripts/up.sh` once to build and start the isolated dependencies/API. Then run `scripts/demo.sh`; every expectation is asserted live and failure returns a nonzero exit. The replay has its own finite run/topic and injected clock, so the ordinary live API run cannot alter its schedule.

1. **First minute (about one minute explanation):** values 10 and 20 at seconds 0 and 20. Distinct progress records on both fixed partitions advance the watermark to 60. SQL contains provisional count 2/sum 30.
2. **Late correction and process crash (about one minute):** value 5 at second 50 arrives at watermark 60. The worker commits its state/result/checkpoint transaction, then the actual JVM halts with exit 74 before Kafka mirroring. Restart waits for the database-time lease and restores from SQL next positions.
3. **Silence, finality and resume (about one minute):** injected processing time advances thirty seconds while the source is eligible with zero backlog. Another source advances watermark to 100. The resumed source sends value 9 timestamped at 40, followed by the original 20 event ID. The tumbling window stays count 3/sum 35/final. The original retry adds nothing.
4. **Recovery proof (about one minute):** the script prints every sliding outcome, then compares a sorted digest with the independent batch membership oracle. Sliding `[-240000,60000)` is count 3/sum 35. Sliding starts -180000, -120000, -60000 and 0 are count 4/sum 44. It also kills an outbox publisher after Kafka send/before SQL delivery mark, and retries pending publications with stable revision keys.

The transcript lives in `.work/A04/runtime/demo-transcript.log`. Integration adds the complementary kill before SQL commit, obsolete-owner fencing, real broker retention deletion, bounded queue backpressure and protected HTTP token/tenant tests. Durability and exactly-once guarantees are restricted to the documented PostgreSQL transaction boundary.
