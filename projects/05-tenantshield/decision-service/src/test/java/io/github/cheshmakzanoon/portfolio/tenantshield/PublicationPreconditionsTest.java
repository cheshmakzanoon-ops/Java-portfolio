package io.github.cheshmakzanoon.portfolio.tenantshield;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

class PublicationPreconditionsTest {
  private final PolicyStore store = mock(PolicyStore.class);
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final DecisionController controller =
      new DecisionController(store, mapper, new RequestBounds());
  private final Jwt jwt =
      Jwt.withTokenValue("test-token")
          .header("alg", "RS256")
          .issuer("https://issuer.example")
          .subject("administrator")
          .build();
  private final PolicyStore.Principal principal =
      new PolicyStore.Principal("https://issuer.example", "administrator");

  @Test
  void malformedPreconditionsAreRejectedBeforeAnyDatabaseAccess() {
    List<String> invalid =
        Arrays.asList(
            null,
            "",
            "0",
            "1\"2\"3",
            "\"\"1\"\"",
            "\"01\"",
            "\"+1\"",
            "\"-1\"",
            "\" 1\"",
            "W/\"1\"",
            "*",
            "\"1\", \"2\"",
            "\"9223372036854775808\"",
            "\"" + "1".repeat(1000) + "\"");
    for (String header : invalid) {
      assertThatThrownBy(() -> controller.publish("harbor", 2, header, jwt))
          .as("publish with If-Match %s", header)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> controller.rollback("harbor", 1, header, jwt))
          .as("rollback with If-Match %s", header)
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(store);
  }

  @Test
  void publicationPreservesExactVersionIncludingInt64Boundary() {
    when(store.publish("harbor", principal, 1, 0)).thenReturn(1L);
    assertThat(controller.publish("harbor", 1, "\"0\"", jwt))
        .containsEntry("tenantEpoch", 1L)
        .containsEntry("policyVersion", 1L);
    controller.publish("harbor", 2, "\"9223372036854775807\"", jwt);
    verify(store).publish("harbor", principal, 2, Long.MAX_VALUE);
  }

  @Test
  void rollbackValidatesImmutableContentAndPreservesExpectedVersion() {
    PolicyEngine.Definition definition =
        new PolicyEngine.Definition(Map.of(), List.of(), List.of());
    PolicyEngine.Compiled policy = PolicyEngine.compile(definition);
    PolicyStore.Revision revision =
        new PolicyStore.Revision(1, mapper.writeValueAsString(definition), policy.digest());
    PolicyStore.Published published = new PolicyStore.Published(3, 4);
    when(store.readForAdmin("harbor", principal, 1)).thenReturn(revision);
    when(store.rollback("harbor", principal, 1, 2)).thenReturn(published);

    assertThat(controller.rollback("harbor", 1, "\"2\"", jwt)).isEqualTo(published);
    verify(store).rollback("harbor", principal, 1, 2);
  }
}
