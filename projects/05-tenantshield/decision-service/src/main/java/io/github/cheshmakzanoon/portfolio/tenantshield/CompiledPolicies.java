package io.github.cheshmakzanoon.portfolio.tenantshield;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/** Immutable policy plans only: membership and authorization outcomes never enter this cache. */
final class CompiledPolicies {
  private record Key(String tenant, long version, String digest) {}

  private final Cache<Key, PolicyEngine.Compiled> cache =
      Caffeine.newBuilder().maximumSize(500).build();
  private final Function<String, PolicyEngine.Definition> decode;

  CompiledPolicies(ObjectMapper mapper) {
    this(content -> mapper.readValue(content, PolicyEngine.Definition.class));
  }

  CompiledPolicies(Function<String, PolicyEngine.Definition> decode) {
    this.decode = Objects.requireNonNull(decode);
  }

  PolicyEngine.Compiled resolve(String tenant, PolicyStore.Revision revision) {
    try {
      if (revision == null
          || revision.version() <= 0
          || revision.digest() == null
          || !revision.digest().matches("[0-9a-f]{64}")
          || revision.content() == null
          || revision.content().getBytes(StandardCharsets.UTF_8).length > 262144)
        throw new IllegalStateException("invalid stored policy metadata");
      return cache.get(
          new Key(tenant, revision.version(), revision.digest()),
          key -> {
            PolicyEngine.Compiled compiled = PolicyEngine.compile(decode.apply(revision.content()));
            if (!compiled.digest().equals(key.digest()))
              throw new IllegalStateException("immutable policy digest mismatch");
            return compiled;
          });
    } catch (RuntimeException failure) {
      throw new IllegalStateException("stored authorization policy is unavailable", failure);
    }
  }

  long size() {
    return cache.estimatedSize();
  }

  long size(String tenant) {
    return cache.asMap().keySet().stream().filter(key -> key.tenant().equals(tenant)).count();
  }

  void invalidate() {
    cache.invalidateAll();
  }
}
