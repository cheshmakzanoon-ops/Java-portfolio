# Deterministic dispatch demonstration

Fixtures.demo() is the source generator for 60 synthetic jobs/six vehicles, seed 42, service zone America/Halifax and an urgent-only depot standby vehicle V5. All travel is fictional and directed. scripts/demo.sh drives the real HTTP service and independently validates results; it emits baseline routes/timelines, first/replanned route orders, score components, completed history, revision audit, stranded demand, input hashes/versions and stale publication status. The UI at the local service shows those routes, stop timelines and unassigned explanations.

For a three-to-five-minute walkthrough: load/display the baseline in the UI; explain hard/unassigned/operating/disruption scores; run the solve and inspect same-version comparison; advance to a real completed stop boundary; mark its vehicle failed and release only audited failed commitments; inspect onboard stranded cargo and the frozen surviving prefix; add an urgent job at the depot and show V5 serving it; finally show the old solve publication returning409. The automated assertions run without artificial delays; spend walkthrough time inspecting the actual live state.

From the repository root, use the complete project-relative paths:

```sh
projects/10-switchback/scripts/up.sh
projects/10-switchback/scripts/demo.sh
projects/10-switchback/scripts/restart-check.sh
projects/10-switchback/scripts/down.sh
```

Repeat only after projects/10-switchback/scripts/reset-demo.sh while idle. The restart scenario uses a separate problem ID and actual SIGKILL, preserving the same PostgreSQL volume; it requires a newer fence and nondegraded retained incumbent, never automatic publication. A backup/restore integration test checks preserved immutable input, published routes and queued/idempotent work.

Expected observations are invariants, not prerecorded output: zero hard violations in each published plan; exact remaining-job partition; no duplicates; unchanged completed history; intact surviving frozen prefix; audited release; STRANDED_CARGO for the failed onboard deliveries; URGENT on compatible standby V5; recorded versions1/2; stale publication409. Scripts fail on actual assertion errors. See evidence and STATUS for which source was actually exercised.
