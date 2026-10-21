# Attribution

The repository's Apache-2.0 license covers original Stacks code and authored fixtures. Frozen corpus records copy original portfolio source and retain repository-relative path plus content digest; they do not imply an external production deployment or third-party customer data.

Supporting libraries are centrally version-managed by the root parent: Apache Lucene10.3.2 supplies indexing/BM25/postings/segments and the standard English stopword/Porter stemming filters used by the optional prose field; Spring Boot4.1.1/Spring Security provide HTTP, JWT/JWKS cryptography and application lifecycle; JUnit6.1.3/JaCoCo/PIT provide executable evidence. Their own distributions retain their license/notice files; the shaded CLI retains bundled META-INF license/notice resources. Do not call Lucene's inverted-index implementation or Nimbus's cryptography original Stacks algorithms.

Identifier tokenization, query AST/planning, bounded feature reranking, cursor context binding, ingestion/generation state management and relevance metric formulas are handwritten here. SHA256/HMAC/RSA primitives come from the JDK and supporting security libraries.
