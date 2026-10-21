# Dependencies and design references

Handoff uses OpenJDK, Spring Boot and Spring Security, PostgreSQL and its JDBC driver, HikariCP, Flyway, Jackson, JUnit Jupiter, jqwik, ArchUnit, and Testcontainers. Their versions and license metadata are listed in the [repository dependency inventory](../../../docs/dependencies/LICENSES.md). The workflow interpreter, graph validator, scheduling transactions, provider simulator, and fault scenarios are maintained within this project.

Two references inform the execution model:

- [PostgreSQL SELECT documentation](https://www.postgresql.org/docs/current/sql-select.html) describes `SKIP LOCKED` queue-consumer behavior and its inconsistent-view limitation.
- [Making retries safe with idempotent APIs](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/) explains stable request identity and conflicts when parameters change.

The project uses synthetic fixtures and a local provider simulator. It has no commercial provider connection or customer data. Design references and third-party libraries do not establish correctness; the project's replay, transaction, and independent effect-log tests exercise its guarantees.
