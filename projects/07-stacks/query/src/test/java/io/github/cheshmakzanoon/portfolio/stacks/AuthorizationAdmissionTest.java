package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class AuthorizationAdmissionTest {
  @Test
  void missingOrInvalidRepositorySetIsAClientInputError() {
    var store = new AuthorizationFilter();
    assertThrows(
        IllegalArgumentException.class, () -> store.update("alice", "tenant", null, false));
    Set<String> withNull = new HashSet<>();
    withNull.add("repository");
    withNull.add(null);
    assertThrows(
        IllegalArgumentException.class, () -> store.update("alice", "tenant", withNull, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.update("alice", "tenant", Set.of("../cross-tenant"), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.update("alice", "tenant", Set.of("space in name"), false));
    assertThrows(SecurityException.class, () -> store.current("alice"));
  }

  @Test
  void principalQuotaProtectsCursorAndCacheIdentityWithoutRewritingValidPrincipals() {
    var store = new AuthorizationFilter();
    assertThrows(
        IllegalArgumentException.class,
        () -> store.update("a".repeat(1025), "tenant", Set.of("r"), false));
    assertThrows(
        IllegalArgumentException.class, () -> store.update(" \t ", "tenant", Set.of("r"), false));
    assertThrows(
        IllegalArgumentException.class, () -> store.update(null, "tenant", Set.of("r"), false));
    String maximal = "a".repeat(1024);
    var accepted = store.update(maximal, "tenant", Set.of("r"), false);
    assertEquals(maximal, accepted.principal());
    assertTrue(store.current(accepted));
    String unusual = "\uD800line\nprincipal";
    assertEquals(
        unusual,
        store.update(unusual, "tenant", Set.of("r"), false).principal(),
        "preserve actual UTF-16 identity rather than coercing or trimming it");
  }

  @Test
  void mutableCallerRepositorySetNeverRewritesIssuedPrivileges() {
    var store = new AuthorizationFilter();
    Set<String> requested = new HashSet<>(Set.of("public", "private"));
    var original = store.update("alice", "tenant", requested, false);
    requested.clear();
    requested.add("restricted");
    assertEquals(Set.of("public", "private"), original.repositories());
    assertTrue(store.current(original));
    assertDoesNotThrow(() -> store.require(original, "tenant", "public"));
    assertThrows(SecurityException.class, () -> store.require(original, "tenant", "restricted"));
    assertThrows(UnsupportedOperationException.class, () -> original.repositories().add("other"));
  }

  @Test
  void maximumRepositoryCountIsInclusiveAndEveryExtraRepositoryFails() {
    var store = new AuthorizationFilter();
    Set<String> sixtyFour = new HashSet<>();
    for (int i = 0; i < 64; i++) sixtyFour.add("repository" + i);
    var accepted = store.update("alice", "tenant", sixtyFour, false);
    assertEquals(64, accepted.repositories().size());
    sixtyFour.add("repository64");
    assertThrows(
        IllegalArgumentException.class, () -> store.update("bob", "tenant", sixtyFour, false));
    assertEquals(Set.copyOf(accepted.repositories()), store.current("alice").repositories());
    assertThrows(SecurityException.class, () -> store.current("bob"));
  }

  @Test
  void malformedUpdateDoesNotConsumeTheNextRevisionOrReplaceGrants() {
    var store = new AuthorizationFilter();
    var previous = store.update("alice", "tenant", Set.of("public"), false);
    assertThrows(
        IllegalArgumentException.class,
        () -> store.update("alice", "bad tenant", Set.of("private"), true));
    assertThrows(IllegalArgumentException.class, () -> store.update("alice", "tenant", null, true));
    assertTrue(store.current(previous));
    assertEquals(Set.of("public"), store.current("alice").repositories());
    var latest = store.update("alice", "tenant", Set.of("public"), false);
    assertEquals(previous.version() + 1, latest.version());
    assertFalse(store.current(previous));
    assertTrue(store.current(latest));
  }
}
