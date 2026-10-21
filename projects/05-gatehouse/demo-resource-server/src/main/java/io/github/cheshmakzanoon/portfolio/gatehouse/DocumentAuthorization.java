package io.github.cheshmakzanoon.portfolio.gatehouse;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * A bounded, fail-closed client for the decision protocol; transport success alone grants nothing.
 */
final class DocumentAuthorization {
  private static final int MAX_RESPONSE = 65536;
  private static final Set<String> DENIAL_REASONS =
      Set.of(
          "NO_MEMBERSHIP",
          "RESOURCE_NOT_FOUND",
          "NO_ACTIVE_POLICY",
          "EXPLICIT_DENY",
          "INDETERMINATE",
          "NO_RBAC_GRANT",
          "NO_ABAC_ALLOW");
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxDocumentLength(MAX_RESPONSE)
                          .maxNestingDepth(8)
                          .maxTokenCount(4096)
                          .maxStringLength(4096)
                          .maxNumberLength(32)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(
              DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
              DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .withCoercionConfig(
              LogicalType.Textual,
              config ->
                  config
                      .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                      .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
          .build();

  record Decision(
      String decision,
      String reason,
      long tenantEpoch,
      long policyVersion,
      long resourceVersion,
      List<String> matchedRuleIds,
      UUID decisionId) {
    Decision {
      if (tenantEpoch < 0 || policyVersion < 0 || resourceVersion < 0 || decisionId == null)
        throw new IllegalArgumentException("invalid decision metadata");
      matchedRuleIds = List.copyOf(matchedRuleIds);
      if (matchedRuleIds.size() > 200) throw new IllegalArgumentException("decision rule limit");
      String previous = null;
      for (String id : matchedRuleIds) {
        if (!id.matches("[A-Za-z0-9_:.\\-]{1,128}")
            || previous != null && previous.compareTo(id) >= 0)
          throw new IllegalArgumentException("invalid decision rule identities");
        previous = id;
      }
      if ("ALLOW".equals(decision)) {
        if (!"MATCHED_ALLOW".equals(reason)
            || tenantEpoch == 0
            || policyVersion == 0
            || resourceVersion == 0
            || matchedRuleIds.isEmpty())
          throw new IllegalArgumentException("incomplete allow decision");
      } else if (!"DENY".equals(decision) || !DENIAL_REASONS.contains(reason))
        throw new IllegalArgumentException("invalid authorization outcome");
    }

    boolean allowed() {
      return decision.equals("ALLOW");
    }
  }

  private final RestClient client;

  DocumentAuthorization(String baseUrl) {
    var transport =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    transport.setReadTimeout(Duration.ofSeconds(5));
    client = RestClient.builder().baseUrl(baseUrl).requestFactory(transport).build();
  }

  Decision decide(String tenant, String resource, String bearer) {
    return client
        .post()
        .uri("/v1/tenants/{tenant}/decisions", tenant)
        .header("Authorization", "Bearer " + bearer)
        .body(
            Map.of(
                "action",
                "document:read",
                "resource",
                Map.of("type", "document", "id", resource),
                "context",
                Map.of("purpose", "support")))
        .exchange(
            (request, response) -> {
              MediaType contentType = response.getHeaders().getContentType();
              if (response.getStatusCode().value() != 200
                  || contentType == null
                  || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON))
                throw new IOException("invalid decision response status or content type");
              byte[] body = response.getBody().readNBytes(MAX_RESPONSE + 1);
              if (body.length > MAX_RESPONSE)
                throw new IOException("decision response exceeds limit");
              Decision decision = JSON.readValue(body, Decision.class);
              if (decision == null) throw new IOException("missing decision response");
              return decision;
            });
  }
}
