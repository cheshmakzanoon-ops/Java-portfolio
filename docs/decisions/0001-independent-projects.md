# ADR 0001: independent project reactors

**Status:** Accepted

## Context

The portfolio demonstrates ten unrelated engineering problems. A shared runtime framework would couple their releases, hide their core algorithms, and make independent build and failure analysis harder.

## Decision

Each project inherits a build-only Maven parent and owns its domain types, adapters, configuration, persistence, tests, and lifecycle. Production code and artifacts cannot depend on another project. Shared tooling is limited to build policy, version pins, verification, and the project catalog.

## Consequences

Some local types and test helpers are duplicated. Each project remains independently buildable and operable, with a clear boundary for its consistency and security guarantees. A future shared runtime component requires a specific demonstrated need and a separate design decision.
