package io.github.cheshmakzanoon.portfolio.indexforge;

import java.util.*;

/** Only pinned JDK documentation/signatures; never supplied by relevance queries or labels. */
public final class SourceCatalogFacts {
  private SourceCatalogFacts() {}

  public record Member(
      String name, List<String> parameters, String returns, String description, boolean field) {
    public Member {
      SourceFacts.text(name);
      SourceFacts.text(returns);
      parameters = List.copyOf(parameters);
      if (parameters.size() > 64 || description == null || description.length() > 8192)
        throw new IllegalArgumentException("API description/signature quota");
      parameters.forEach(SourceFacts::text);
    }
  }

  public record Type(String name, String description, List<Member> members) {
    public Type {
      SourceFacts.text(name);
      members = List.copyOf(members);
      if (members.size() > 4096 || description == null || description.length() > 8192)
        throw new IllegalArgumentException("API type quota");
    }
  }

  public record Unit(String packageName, List<String> imports, List<Type> types) {
    public Unit {
      SourceFacts.text(packageName);
      imports = List.copyOf(imports);
      types = List.copyOf(types);
      if (imports.size() > 256 || types.size() > 128)
        throw new IllegalArgumentException("API unit quota");
      imports.forEach(SourceFacts::text);
    }
  }
}
