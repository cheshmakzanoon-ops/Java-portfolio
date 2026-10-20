package io.github.cheshmakzanoon.portfolio.tenantshield;

import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/v1/tenants/{tenant}")
public class DecisionController {
  public record ResourceRef(String type, String id) {}

  public record Request(String action, ResourceRef resource, Map<String, Object> context) {}

  public record Response(
      String decision,
      String reason,
      long tenantEpoch,
      long policyVersion,
      long resourceVersion,
      List<String> matchedRuleIds,
      UUID decisionId) {}

  public record Example(
      Set<String> roles,
      String action,
      String resourceType,
      Map<String, Object> fields,
      boolean allowed,
      String reason) {}

  public record Draft(PolicyEngine.Definition policy, List<Example> examples) {}

  public record MembershipUpdate(
      Set<String> roles,
      String department,
      Long clearance,
      Boolean suspended,
      boolean administrative,
      boolean active) {}

  public record Simulation(PolicyEngine.Definition policy, Example example) {}

  private final RequestBounds bounds;
  private final PolicyStore store;
  private final ObjectMapper mapper;
  private final CompiledPolicies policies;
  private final SnapshotAuthorization authorization;

  public DecisionController(PolicyStore store, ObjectMapper mapper, RequestBounds bounds) {
    this.bounds = bounds;
    this.store = store;
    this.mapper = mapper;
    this.policies = new CompiledPolicies(mapper);
    this.authorization = new SnapshotAuthorization(policies);
  }

  private PolicyStore.Principal principal(Jwt jwt) {
    return new PolicyStore.Principal(jwt.getIssuer().toString(), jwt.getSubject());
  }

  @PostMapping("/decisions")
  public Response decide(
      @PathVariable String tenant, @AuthenticationPrincipal Jwt jwt, @RequestBody Request request) {
    SnapshotAuthorization.Context context = validate(request);
    PolicyStore.Outcome o =
        store.decide(
            tenant,
            principal(jwt),
            request.action(),
            request.resource().id(),
            s ->
                authorization.decide(
                    tenant, s, request.action(), request.resource().type(), context));
    return new Response(
        o.decision().allowed() ? "ALLOW" : "DENY",
        o.decision().reason(),
        o.snapshot().epoch(),
        o.snapshot().policy() == null ? 0 : o.snapshot().policy().version(),
        o.snapshot().resource() == null ? 0 : o.snapshot().resource().version(),
        o.decision().matchedRuleIds(),
        o.id());
  }

  private static SnapshotAuthorization.Context validate(Request r) {
    if (r == null
        || r.action() == null
        || !r.action().matches("[A-Za-z0-9:_-]{1,128}")
        || r.resource() == null
        || r.resource().id() == null
        || !r.resource().id().matches("[A-Za-z0-9:_-]{1,128}")
        || r.resource().type() == null
        || !r.resource().type().matches("[A-Za-z0-9:_-]{1,128}"))
      throw new IllegalArgumentException("invalid decision request");
    return SnapshotAuthorization.Context.from(r.context());
  }

  private PolicyEngine.Compiled compiled(String tenant, PolicyStore.Revision revision) {
    return policies.resolve(tenant, revision);
  }

  @PostMapping("/policy-versions")
  public Map<String, Long> draft(
      @PathVariable String tenant, @AuthenticationPrincipal Jwt jwt, @RequestBody Draft request) {
    PolicyEngine.Compiled policy = PolicyEngine.compile(request.policy());
    if (request.examples() == null
        || request.examples().isEmpty()
        || request.examples().size() > 100)
      throw new IllegalArgumentException("1..100 regression examples required");
    for (Example e : request.examples()) {
      PolicyEngine.Decision d =
          PolicyEngine.decide(
              policy, e.roles(), e.action(), e.resourceType(), normalize(e.fields()));
      if (d.allowed() != e.allowed() || !d.reason().equals(e.reason()))
        throw new IllegalArgumentException("policy regression example failed");
    }
    String content = mapper.writeValueAsString(request.policy());
    if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
      throw new IllegalArgumentException("policy size limit");
    return Map.of("version", store.draft(tenant, principal(jwt), content, policy));
  }

  @PostMapping("/policy-versions/{version}/publish")
  public Map<String, Long> publish(
      @PathVariable String tenant,
      @PathVariable long version,
      @RequestHeader("If-Match") String prior,
      @AuthenticationPrincipal Jwt jwt) {
    long expected = expectedVersion(prior);
    return Map.of(
        "tenantEpoch",
        store.publish(tenant, principal(jwt), version, expected),
        "policyVersion",
        version);
  }

  @PostMapping("/policy-versions/{version}/rollback")
  public PolicyStore.Published rollback(
      @PathVariable String tenant,
      @PathVariable long version,
      @RequestHeader("If-Match") String prior,
      @AuthenticationPrincipal Jwt jwt) {
    long expected = expectedVersion(prior);
    PolicyStore.Revision old = store.readForAdmin(tenant, principal(jwt), version);
    compiled(tenant, old);
    return store.rollback(tenant, principal(jwt), version, expected);
  }

  private static long expectedVersion(String value) {
    if (value == null || !value.matches("\"(0|[1-9][0-9]{0,18})\""))
      throw new IllegalArgumentException("If-Match must contain one quoted active version");
    try {
      return Long.parseLong(value, 1, value.length() - 1, 10);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("If-Match active version exceeds int64 range", e);
    }
  }

  @PutMapping("/memberships/{subject}")
  public Map<String, Long> membership(
      @PathVariable String tenant,
      @PathVariable String subject,
      @AuthenticationPrincipal Jwt jwt,
      @RequestBody MembershipUpdate request) {
    if (subject.length() > 128
        || request.roles() == null
        || request.roles().size() > 100
        || request.department() != null && request.department().length() > 256)
      throw new IllegalArgumentException("membership limits");
    return Map.of(
        "tenantEpoch",
        store.membership(
            tenant,
            principal(jwt),
            new PolicyStore.Principal(jwt.getIssuer().toString(), subject),
            new PolicyStore.Membership(
                request.roles(),
                request.department(),
                request.clearance(),
                request.suspended(),
                request.administrative(),
                request.active())));
  }

  @PostMapping("/simulate")
  public PolicyEngine.Decision simulate(
      @PathVariable String tenant, @AuthenticationPrincipal Jwt jwt, @RequestBody Simulation r) {
    return store.simulate(
        tenant,
        principal(jwt),
        m -> {
          PolicyEngine.Compiled p = PolicyEngine.compile(r.policy());
          Example e = r.example();
          return PolicyEngine.decide(
              p, e.roles(), e.action(), e.resourceType(), normalize(e.fields()));
        });
  }

  @GetMapping("/audit")
  public List<PolicyStore.Audit> audit(
      @PathVariable String tenant, @AuthenticationPrincipal Jwt jwt) {
    return store.audits(tenant, principal(jwt));
  }

  static Map<String, Object> normalize(Map<String, Object> input) {
    Map<String, Object> out = new HashMap<>();
    input.forEach(
        (k, v) ->
            out.put(
                k,
                PolicyLanguage.FIELDS.get(k) == PolicyLanguage.Type.ENUM
                        && v instanceof String label
                    ? PolicyLanguage.Label.valueOf(label)
                    : v instanceof Integer n ? n.longValue() : v));
    return out;
  }

  @GetMapping("/metrics")
  public Map<String, Object> metrics(
      @PathVariable String tenant, @AuthenticationPrincipal Jwt jwt) {
    return store.simulate(
        tenant,
        principal(jwt),
        membership ->
            Map.of(
                "queryCount",
                store.queryCount(tenant),
                "cacheSize",
                policies.size(tenant),
                "requests",
                bounds.tenantStats(tenant)));
  }

  public long cacheSize() {
    return policies.size();
  }

  public void evict() {
    policies.invalidate();
  }

  @ExceptionHandler(PolicyStore.Forbidden.class)
  public ResponseEntity<ProblemDetail> forbidden() {
    return problem(403, "Forbidden", "tenant membership or administrative permission required");
  }

  @ExceptionHandler(PolicyStore.Conflict.class)
  public ResponseEntity<ProblemDetail> conflict() {
    return problem(
        409, "Conflict", "active policy changed or transaction conflicted; reread before retry");
  }

  @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
  public ResponseEntity<ProblemDetail> invalid() {
    return problem(400, "Invalid request", "request or policy validation failed");
  }

  @ExceptionHandler({PolicyStore.StoreFailure.class, IllegalStateException.class})
  public ResponseEntity<ProblemDetail> unavailable() {
    return problem(503, "Unavailable", "authorization dependency unavailable; operation refused");
  }

  private static ResponseEntity<ProblemDetail> problem(int status, String title, String detail) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
    p.setTitle(title);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
  }
}
