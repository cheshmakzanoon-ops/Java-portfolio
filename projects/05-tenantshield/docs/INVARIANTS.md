# Invariants and proof locations

| Invariant | Implementation | Verification |
|---|---|---|
| No allow without grant and matching ABAC condition | PolicyEngine.decide | inheritedGrantAndAbacBothRequired; generatedExpressionsAndIndexedDecisionsAgreeWithOracle |
| Explicit deny wins; UNKNOWN is conservative and not UNKNOWN remains UNKNOWN | Truth / combining | unknownNegationAndExplicitDenyAreConservative; 2,500 seeded oracle cases |
| Policy parsing cannot execute code and has finite work | PolicyLanguage parser and evaluation budget | rejectsUnsafeOrIllTypedOrOversizedPolicies |
| Role inheritance is tenant-local, acyclic and immutable | PolicyEngine.compile/visit; revision-scoped role FKs | rejectsRoleCyclesMissingParentsDuplicatesAndUnknownPredicates; immutableClosureAndStableTrace |
| Validated token subject is sole request identity | Spring resource server; DecisionController.principal | JwtSecurityTest; realOidcAndTenantBindingRejectAdversarialRequests |
| RLS context is transaction-local; reused connections do not leak tenant | PolicyStore.transaction / migration RLS | pooledTenantIsolationImmutableRevisionsAndSnapshotRevocationBoundary |
| Revocation before a new snapshot denies even with warm AST | Snapshot transaction; epoch trigger | latched before/after snapshot IT; real demo |
| Publication preconditions have one unambiguous int64 version | DecisionController.expectedVersion | PublicationPreconditionsTest; ambiguousJsonAndPublicationPreconditionsReturnBadRequest |
| JSON request identity and values have one interpretation | Spring-managed application mapper | JsonConfigurationTest; ambiguousJsonAndPublicationPreconditionsReturnBadRequest |
| Active policy version and epoch increase; old AST is immutable | expected-version publication / revision trigger | concurrentPublicationKeepsVersionedCacheImmutable; stale publish/immutable write assertions |
| Every allow requires persisted redacted audit | same transaction, before response | requiredAuditAndDatabaseOutageNeverReturnAllow |
| An unavailable DB cannot authorize | bounded socket/query timeout, 503 mapping | pausedDatabaseReturnsExplicitFailure |
| A protected operation obtains a new decision | ProtectedDocuments.read | scripts/demo.sh allow, revoke, protected denial assertions |

Test names are executable method names, not claims of successful execution. Current observed runs and open gates are recorded in STATUS and evidence.
