# Implementation provenance

The interpreter, DSL, JDBC scheduling/fencing logic, fake provider, fixtures, fault drivers and assertions are original portfolio implementation produced with assistance from Codex coding agents under the repository owner's execution brief. Review findings and observed failures are recorded rather than presented as production incident history.

Supporting libraries are OpenJDK, Spring Boot/Security, PostgreSQL JDBC, HikariCP, Flyway, Jackson, JUnit Jupiter, jqwik, ArchUnit and Testcontainers, under their respective licenses; shared version/license metadata belongs to the root dependency inventory. No Temporal/Camunda runtime, copied workflow engine, commercial provider or real customer data is used.

Design references include PostgreSQL's documented SKIP LOCKED queue-consumer behavior and AWS's idempotent API guidance linked in the master brief. Those references motivate explicit uncertainty and request-parameter conflicts; they are not substitutes for the independent provider effect-log tests.
