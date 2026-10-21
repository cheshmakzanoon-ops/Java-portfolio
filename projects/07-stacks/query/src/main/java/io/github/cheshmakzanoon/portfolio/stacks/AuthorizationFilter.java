package io.github.cheshmakzanoon.portfolio.stacks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

public final class AuthorizationFilter {
  public record Access(
      String principal,
      String tenant,
      Set<String> repositories,
      long version,
      boolean administrator) {
    public Access {
      if (principal == null
          || principal.isBlank()
          || principal.length() > 1024
          || tenant == null
          || !tenant.matches("[a-zA-Z0-9_-]{1,64}")
          || repositories == null
          || repositories.size() > 64) throw new IllegalArgumentException("access context");
      for (String repository : repositories)
        if (repository == null || !repository.matches("[a-zA-Z0-9_-]{1,64}"))
          throw new IllegalArgumentException("repository context");
      repositories = Set.copyOf(repositories);
    }
  }

  private final Map<String, Access> principals = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();

  /**
   * Serialize updates to one principal through the map's per-key computation. Advancing the version
   * outside this computation allowed an older writer to publish after a newer writer, silently
   * restoring superseded grants.
   */
  public Access update(String principal, String tenant, Set<String> repositories, boolean admin) {
    // Fail validation before entering the map's critical section or consuming a revision.
    Access validated = new Access(principal, tenant, repositories, 0, admin);
    return principals.compute(
        principal,
        (key, previous) ->
            new Access(
                key,
                validated.tenant(),
                validated.repositories(),
                sequence.incrementAndGet(),
                validated.administrator()));
  }

  public Access current(String principal) {
    Access a = principals.get(principal);
    if (a == null) throw new SecurityException("access denied");
    return a;
  }

  public boolean current(Access a) {
    return a.equals(principals.get(a.principal()));
  }

  public Query apply(Query query, Access a) {
    BooleanQuery.Builder repos = new BooleanQuery.Builder();
    for (String r : new TreeSet<>(a.repositories()))
      repos.add(new TermQuery(new Term("repository", r)), BooleanClause.Occur.SHOULD);
    Query permitted = a.repositories().isEmpty() ? new MatchNoDocsQuery() : repos.build();
    return new BooleanQuery.Builder()
        .add(query, BooleanClause.Occur.MUST)
        .add(new TermQuery(new Term("tenant", a.tenant())), BooleanClause.Occur.FILTER)
        .add(permitted, BooleanClause.Occur.FILTER)
        .build();
  }

  public void require(Access a, String tenant, String repository) {
    if (!a.tenant().equals(tenant) || !a.repositories().contains(repository) || !current(a))
      throw new SecurityException("access denied");
  }
}
