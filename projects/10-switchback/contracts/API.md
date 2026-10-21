# HTTP contract

All API operations except /health are protected and tenant bound. The static shell (`/`, `/index.html`) and its assets (`/app.js`, `/styles.css`) are anonymous and contain no tenant data. Input/output is JSON and integer seconds/meters/grams, UTC offset instants, with explicit serviceZone. Maximum raw body 16 MiB, including chunked requests; JSON integer units reject fractional/string/null values and text fields reject scalar coercion; malformed/unbounded domain input400, tenant denial403, tenant-scoped missing404, version/idempotency/revision conflict409, queue saturation429. Object schemas correspond to immutable domain records and Store/API request records, not arbitrary maps.

| Method | Path | Request / result |
|---|---|---|
| POST | /v1/problems | Model.Problem version1;201 immutable snapshot |
| GET | /v1/problems/{id}?version=N | Active or historical immutable snapshot |
| POST | /v1/problems/{id}/solve | expectedVersion,requestId,seed,budgetMs,steps;202 durable job |
| GET | /v1/solve-jobs/{id} | Durable state, fence, provenance, incumbent, stop condition |
| GET | /v1/solve-jobs/{id}/baseline | First validated authoritative candidate for this job |
| POST | /v1/solve-jobs/{id}/cancel | Actual observed cooperative state |
| GET | /v1/plans/{id} | revision,input version, immutable validated routes/score/explanations |
| POST | /v1/plans/{id}/replan | expectedVersion,expectedRevision,planningInstant,observations,urgent;201 new immutable revision |
| POST | /v1/plans/{id}/publish | solveJobId,expectedVersion,expectedRevision; compare-and-set plan |
| POST | /v1/demo/reset | trusted loopback development profile only; resets demo tenant |

Replan observation: vehicleId,completedPrefix,broken,freezeNext,releaseFailedCommitments. Complete prefix is an observed count from the prior published sequence; the service calculates current stop/location and onboard remaining load from that sequence. It rejects unsupported mid-road positions and fabricated future completions. New urgent jobs are depot cargo by definition. Published candidates expose complete route orders, computed stop arrival/service/departure/return times, and unmet-demand reasons. A search-failure reason explicitly does not claim global infeasibility.

Publication requires a non-null solve-job UUID, positive expected input version, and nonnegative expected plan revision. Replanning requires the same version/revision bounds, a whole-second planning instant, at most 25 observations, and at most 500 urgent jobs. Missing/null lists and malformed request envelopes fail with 400 before persistence access. Domain checks still validate the resulting full snapshot.

The trusted local development profile accepts only loopback Host values. An Origin header must exactly match the request scheme, host, and port; cross-site Fetch Metadata and opaque origins are refused with 403 `UNTRUSTED_LOCAL_REQUEST`. Local command-line clients may omit Origin. These checks are additional browser protections, not authentication for direct local clients.

Record components are required, including zero-valued revisions and seeds; omission is not an implicit default. Root null is invalid. Requested input versions must be positive. Unexpected server errors return 500 `INTERNAL_ERROR` with an opaque `incident` identifier, while request-shape/domain failures remain 400.

A graceful service shutdown may return running work to QUEUED at a higher fence while retaining its incumbent; cancellation already requested is acknowledged as CANCELLED. Neither transition publishes a plan. Database lease recovery remains the fallback if shutdown cannot write its release.
