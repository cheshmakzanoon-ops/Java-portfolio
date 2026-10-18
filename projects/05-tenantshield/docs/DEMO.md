# Reviewer demonstration (3–5 minutes)

Prepare the real local stack with `projects/05-tenantshield/scripts/up.sh` from the repository root. The first Keycloak start includes Quarkus augmentation and realm import and may take several minutes; run preparation before the timed walkthrough. The script verifies source-matching artifacts, migrates PostgreSQL using a separate owner process, and waits for both services. JDK 25, Docker Compose, Python 3 and curl are required. The [execution checkpoint](STATUS.md) records which complete runs have actually passed.

Run `projects/05-tenantshield/scripts/demo.sh`. It obtains real disposable Keycloak tokens without printing them and asserts each HTTP result. Narrate the sequence:

1. Restore Alice's Harbor membership, compile the fixture with regression examples, and publish a new immutable policy revision under an expected prior version for Harbor and Orchard.
2. Permit Harbor Alice to read Harbor doc-7. Show the returned policy version, resource version and tenant epoch. Deny the same principal against Orchard because no Orchard membership exists.
3. Deny a mismatched-department document, and deny a document with missing classification as INDETERMINATE. Explain that missing classification is not silently treated as zero.
4. Suspend Alice and show EXPLICIT_DENY overriding an otherwise matching allow. Restore the membership and demonstrate a successful protected document request.
5. Warm the compiled AST cache, revoke membership, wait for the successful mutation response, then start a new decision. It must deny at an epoch higher than the warmed-cache decision.
6. Read the audit using the current administrator and show the higher-epoch denial and hashed principal reference. Finish with the protected document endpoint returning HTTP 403 for Alice.

Each step prints PASS only after an actual assertion succeeds. Any unexpected status/reason/epoch causes a nonzero exit; the success envelope `evidence/demo.json` is written only after all checks complete. This document describes the required live sequence, not a prerecorded successful run. Observed output is added to evidence only after executing the script.

`projects/05-tenantshield/scripts/down.sh` stops only this project's processes and Compose services and retains database data. To reset only Harbor/Orchard fixtures, use `scripts/reset-demo.sh` under this project and then `up.sh`; full workload tenants are retained. Use the [technical walkthrough](INTERVIEW.md) for the longer compiler, transaction and failure discussion.

Observed real run: 21 assertions passed on 2026-10-18 (see [actual output](../evidence/demo-output.txt) and [source-stamped envelope](../evidence/demo.json)). The protected request first returned 200, then returned 403 after the committed membership revocation. A separate real database-pause probe observed 403/503/403 before/during/after recovery ([evidence](../evidence/protected-outage.json)). Full performance and final review remain separate gates.
