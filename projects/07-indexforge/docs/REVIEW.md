# Review record

This record summarizes defects and their regression boundaries. Historical certification envelopes remain under `evidence/`; their source hashes and test totals do not certify later changes.

| Finding | Correction and regression |
| --- | --- |
| An ambiguous publication could leave an old job retryable after a newer job completed. | Committed job identity and checksummed receipt reconciliation preserve the newer generation across live retry and restart. |
| Selecting a nested file changed its document key relative to a full-root snapshot. | All selected paths remain relative to the configured repository root. |
| Raw score explanations exposed statistics from denied repositories. | Public explanations contain authorized-document features rather than raw Lucene statistics. Residual score/order dependence is documented. |
| Filter-map display text allowed cache/cursor identity collisions. | Versioned binary encoding uses lengths and sorted tuples; warmed-cache, facet, crossed-cursor, reorder, restart, and revocation tests exercise the boundary. |
| Inference identity omitted transitive classes and numeric profile values. | Complete supported artifact origins and actual numeric profiles bind scoring, cache, and cursor identity. |
| Every enum was treated as final for virtual dispatch. | Constant-specific class bodies prevent exact instance binding; static bindings and ordinary final enums retain supported behavior. |
| Supplementary identifier admission disagreed with facts and wire limits. | Identifier scanning consumes code points and applies the same 256-unit UTF-16 quota before parsing; exact-boundary and mixed-script regressions cover it. |
| A regex admitted supplementary queries longer than the usage graph's UTF-16 bound. | The identifier lane applies the same 128-unit bound; real cold and warmed searches cover the boundary and safe fallback to ordinary search. |

The relevance review rejected the initial smoke corpus before measurement and removed unsupported secondary labels before freezing the measured corpus. Both later final studies failed the declared quality targets and retained baseline. Existing protocols and seals remain historical records; no new quality or performance result is implied by this maintenance review.
