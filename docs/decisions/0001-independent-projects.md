# ADR 0001: independent project reactors

Accepted. Each project inherits a build-only parent and has no dependency on another project. This increases some local type duplication but preserves separate demos and failure boundaries. A portfolio runtime framework was rejected because it would couple unrelated products and obscure their core algorithms. One coordinator serializes Git writes in the shared main checkout.
