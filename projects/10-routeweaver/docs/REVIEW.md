# Correctness and security review

Review findings are scoped to the inspected behavior and recorded source. Historical test and benchmark evidence remains linked from [validation status](STATUS.md) and [performance](PERFORMANCE.md).

| Finding | Resolution | Regression boundary |
| --- | --- | --- |
| Cancellation could arrive before solver startup; a solution clone changed null planning variables. | Cancellation remains latched until solve exits; cloning preserves null values. | Barrier-based cancellation and null-variable tests. |
| Problem identifiers could be unaddressable as single URL segments. | Require a bounded, URL-safe problem identifier. | Authenticated malformed-input requests fail before persistence. |
| Protected mode made its token-entry shell inaccessible. | Publish only the static shell and assets anonymously; API scopes remain required. | Real HTTP shell/asset success and unauthenticated API rejection. |
| JSON coercion admitted fractional integers and scalar-to-text conversion. | Disable coercion, null primitives, ordinal enums, duplicate fields, unknown fields, and trailing values. | Direct parser and signed-token HTTP negatives. |
| A different application binary could recover an old solve job under the old source label. | Refuse incompatible queued/recovered jobs before planning; retain historical incumbents and submitted identity. | PostgreSQL old-source refusal, preserved trace, stale-fence rejection, and same-source recovery. See [decision 003](DECISIONS/003-source-compatible-recovery.md). |
| Loopback development mode admitted cross-origin browser mutations and arbitrary Host names. | Validate loopback Host, exact Origin when supplied, and cross-site Fetch Metadata before assigning the demo identity. | Actual HTTP cross-origin reset, opaque/malformed Origin, cross-site request, and rebound-Host rejection before database access. |
| The UI could overlap progress requests and apply an old response after connection details changed. | Schedule the next poll only after the previous response; invalidate stale responses when problem or token changes. | Browser regression holds an outstanding response, changes identity, and verifies no old route or action state returns. |

The current dispatcher uses external script/style assets and a same-origin Content Security Policy without inline-script permission. User and server strings are rendered through text nodes. Browser tests include hostile-looking stop labels, keyboard access, narrow-screen layout, action gating, same-input baseline comparison, and explicit publication.

The sealed benchmark packet was independently checked against its raw manifest, outcomes, and published summaries. That review does not establish production security, hosted CI success, or a fresh benchmark for later code.
