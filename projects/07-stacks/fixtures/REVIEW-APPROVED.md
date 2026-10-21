# Independent preregistration review

Coordinator `/root` approved v3 before any ranking metrics on2026-10-18. Reviewed complete-source primary method mappings, near-domain distractors and semantic correction audit.80queries remain split40development/20validation/20held-out.

- Corpus SHA256:0162c35bd70d995e2ab0feb14ccf96c7cec76666338b54a6d4a8689efd786ed9.
- Judgments SHA256:77a370782ec74c14036ad641f727fb41283e9baceb85ee2fdf2671a3d750b7ec.

Coordinator instruction: "Proceed dev/validation selection then fixed heldout once; preserve baseline/ablations/failures, retainbaseline unless improves heldout. Do not alterlabels based on results."

These are authored-corpus judgments, not universal relevance. Generic identifiers are judged by their documented intent. For executable pooled metrics, unjudged documents receive gain0; this convention does not establish that an unjudged document is universally irrelevant. Graded gain is2^grade−1; reciprocal rank/recall consider any nonzero grade relevant. Corpus/split/labels remain frozen regardless of output.
