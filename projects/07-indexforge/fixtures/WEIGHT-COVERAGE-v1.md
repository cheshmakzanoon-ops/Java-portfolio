# Bounded field-weight/coverage experiment v1

Frozen before any experiment measurement. Authored after concept-v1 development/validation results (.623410/.509058 nDCG and .783333 validation identifierMRR); all earlier failures remain preserved. No held-out ranking has run and no labels change. No corpus/project/path prior or query-ID lookup is allowed.

Six fixed presets, in columns identifier/symbol/path/declaration/prose/concept/coverage weights:

| Preset | Identifier | Symbol | Path | Declaration | Prose | Concept | Coverage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CURRENT | 3 | 8 | 2 | 32 | 1.5 | .75 | 0 |
| CONTEXTUAL | 2 | 6 | 1 | 32 | 2 | 2 | 4 |
| SEMANTIC | 1 | 4 | 1 | 24 | 3 | 4 | 4 |
| NAVIGATIONAL | 2 | 12 | 4 | 64 | 1.5 | 1 | 2 |
| CONTENT_FOCUSED | 1 | 2 | .5 | 16 | 1 | .5 | 4 |
| COORDINATED | 3 | 8 | 2 | 32 | 1.5 | .75 | 8 |

Content weight remains1. Lucene BM25 defaults remain unchanged. These are conventional, deliberately bounded field-weight alternatives, not learned claims. All presets are frozen together before results; development diagnoses general behavior and validation selects only among this fixed set. No further continuous tuning is performed inside this experiment.

Original coverage feature: for an ordinary plain multiword query, normalize distinct terms using the existing identifier/English-stop/Porter pipeline, at most64. Read only each authorized candidate's indexed prose term vector; bonus = coverageWeight * (distinct matched query terms / distinct query terms)^2. There is no full-source reload for500 candidates, no hidden-document content feature, no corpus prior and no new candidate filter. Explicit Boolean, phrase, field and single-identifier semantics are unchanged. BASELINE, NO_PROSE and NO_COVERAGE disable the feature; CURRENT has zero coverage weight. English stop/stemming and Lucene term-vector mechanics remain reused algorithms.

Prespecified selection objective per mode/profile: require validation nDCG and identifierMRR at least their baseline values; maximize min(validation nDCG/.75, validation identifierMRR/.90), prefer baseline on equal objective. Compare the same objective across the six fixed profiles, deterministic enum order for remaining ties. Baseline is eligible by definition. Selected profile/mode and compiled-model/settings/fixture identities must be frozen before the held-out run. Held-out baseline and all feature ablations remain published, and baseline is retained if selected candidate fails to improve nDCG without identifierMRR regression. Hard .75/.90 targets remain mandatory and are reported as failed when not achieved.

Tests require exact independent distinct-term coverage on full/partial-match documents, the NO_COVERAGE original-score oracle, all explicit syntax controls, unrelated-topic rejection and unchanged baseline behavior. Raw collection statistics never appear in user explanations; matched coverage is a feature of the authorized document and user query. Tenant-reader BM25 statistical score/order coupling remains explicitly documented.

Recorded before measurements at 2026-10-18T02:26:25.930279+00:00.
