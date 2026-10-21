# Stacks relevance-label review

## Initial collection

The review checked all 80 query/grade rows against the corpus and split before ranking metrics were reported. The collection contained 40 development, 20 validation, and 20 held-out queries; 40 identifier and 40 prose queries across 40 topics; and 160 documents with three judgments per query. Topic splits were disjoint.

- Corpus SHA-256: `a979b268c886f5181d29efd70f811c36f49449b408aa2bc6c7c9975dcc325dab`
- Judgment SHA-256: `d5555ddcd0bff0a9365e16f281984c5524775548b599a8f0fb8a59b407d6349d`

The collection was rejected as a final quality benchmark: primary source/test documents were empty stubs with repeated topic comments, prose queries repeated headings, and grading largely measured template differences. It remains smoke-test data. No ranking or tuning output was reviewed, and labels were not changed to improve metrics.

## Source-based collection

The 2026-10-18 review checked 70 substantive Java files, including 40 nearby-domain distractors with path/digest provenance. The 80 queries retained the 40/20/20 split. Review of query intent, primary method mappings, and supporting relevance found unsupported supporting labels, including JWT-validation labels on engine-only tests and RSA-signing labels on ingestion tests.

All 80 rows were audited, and 16 unsupported grade-1/grade-2 judgments were removed before ranking measurements. `projects/07-stacks/fixtures/V3-LABEL-AUDIT.json` records the reasons; the prior collection remains preserved. Primary labels, query text, and splits did not change.

- Corpus SHA-256: `0162c35bd70d995e2ab0feb14ccf96c7cec76666338b54a6d4a8689efd786ed9`
- Revised judgment SHA-256: `77a370782ec74c14036ad641f727fb41283e9baceb85ee2fdf2671a3d750b7ec`

Approval concerns label validity, not achieved relevance. Generic identifier queries follow their documented intent, and unjudged documents follow the declared metric convention. Development/validation selection precedes fixed held-out evaluation. Baselines, ablations, and per-query failures remain part of the record.
