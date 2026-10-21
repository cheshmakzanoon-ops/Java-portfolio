package io.github.cheshmakzanoon.portfolio.durableflow;

import io.github.cheshmakzanoon.portfolio.durableflow.Data.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@SpringBootApplication
@RestController
public class Application {
  static {
    tools.jackson.core.StreamReadConstraints.overrideDefaultStreamReadConstraints(
        tools.jackson.core.StreamReadConstraints.builder()
            .maxNestingDepth(32)
            .maxStringLength(WorkflowLimits.TASK_INPUT_BYTES)
            .maxNumberLength(32)
            .build());
  }

  private final WorkflowStore store;

  public Application(WorkflowStore store) {
    this.store = store;
  }

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer strictRequestJson() {
    return builder ->
        builder
            .withCoercionConfig(
                tools.jackson.databind.type.LogicalType.Textual,
                config -> {
                  for (var shape :
                      List.of(
                          tools.jackson.databind.cfg.CoercionInputShape.Integer,
                          tools.jackson.databind.cfg.CoercionInputShape.Float,
                          tools.jackson.databind.cfg.CoercionInputShape.Boolean))
                    config.setCoercion(shape, tools.jackson.databind.cfg.CoercionAction.Fail);
                })
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.cfg.EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
  }

  @Bean
  static WorkflowStore store(Environment e) {
    WorkflowStore s =
        new WorkflowStore(
            e.getRequiredProperty("DB_URL"),
            e.getProperty("DB_USER", "durableflow"),
            e.getRequiredProperty("DB_PASSWORD"));
    s.migrate();
    return s;
  }

  /**
   * Bearer tokens are supplied explicitly, so they are not ambient browser credentials. Preserve
   * CSRF checks for cookies and browser-sent Basic credentials without changing CLI/Bearer APIs.
   */
  static boolean csrfForAmbientCredentials(jakarta.servlet.http.HttpServletRequest request) {
    if (java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod()))
      return false;
    if (request.getCookies() != null && request.getCookies().length > 0) return true;
    String cookie = request.getHeader("Cookie");
    if (cookie != null && !cookie.isBlank()) return true;
    String authorization = request.getHeader("Authorization");
    return authorization != null
        && authorization.regionMatches(true, 0, "Basic ", 0, 6)
        && (request.getHeader("Origin") != null || request.getHeader("Sec-Fetch-Site") != null);
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, Environment e) throws Exception {
    boolean dev = Arrays.asList(e.getActiveProfiles()).contains("dev");
    http.csrf(
            csrf ->
                csrf.requireCsrfProtectionMatcher(Application::csrfForAmbientCredentials)
                    .withObjectPostProcessor(
                        new ObjectPostProcessor<CsrfFilter>() {
                          @Override
                          public <O extends CsrfFilter> O postProcess(O filter) {
                            // Resource-server setup adds a bearer CSRF ignore rule. Restore the
                            // ambient-credential boundary on the fully assembled filter.
                            filter.setRequireCsrfProtectionMatcher(
                                Application::csrfForAmbientCredentials);
                            // A direct problem response avoids an authenticated /error dispatch.
                            filter.setAccessDeniedHandler(
                                (request, response, denied) ->
                                    writeProblem(response, 403, "Permission denied"));
                            return filter;
                          }
                        }))
        .authorizeHttpRequests(
            a -> a.requestMatchers("/health").permitAll().anyRequest().authenticated());
    if (dev) {
      String address = e.getProperty("server.address", "127.0.0.1");
      if (!Set.of("127.0.0.1", "::1", "localhost").contains(address))
        throw new IllegalStateException("dev must bind loopback");
      http.httpBasic(
          b ->
              b.authenticationEntryPoint(
                  (request, response, error) -> {
                    response.setHeader("WWW-Authenticate", "Basic realm=durableflow");
                    writeProblem(response, 401, "Authentication required");
                  }));
    } else {
      String issuer = e.getRequiredProperty("OIDC_ISSUER"),
          audience = e.getRequiredProperty("OIDC_AUDIENCE");
      NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(issuer),
              jwt ->
                  jwt.getAudience().contains(audience)
                          && jwt.getExpiresAt() != null
                          && jwt.getSubject() != null
                          && !jwt.getSubject().isBlank()
                          && jwt.getClaimAsString("tenant") != null
                          && jwt.getClaimAsString("tenant").matches("[a-zA-Z0-9-]{1,64}")
                      ? OAuth2TokenValidatorResult.success()
                      : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
      http.oauth2ResourceServer(
          o ->
              o.jwt(j -> j.decoder(new StrictJwtDecoder(decoder, issuer, audience)))
                  .authenticationEntryPoint(
                      (request, response, error) -> {
                        response.setHeader("WWW-Authenticate", "Bearer");
                        writeProblem(response, 401, "Authentication required");
                      })
                  .accessDeniedHandler(
                      (request, response, error) ->
                          writeProblem(response, 403, "Permission denied")));
    }
    http.sessionManagement(
        session ->
            session.sessionCreationPolicy(
                org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (request, response, error) -> {
                      response.setHeader(
                          "WWW-Authenticate", dev ? "Basic realm=durableflow" : "Bearer");
                      writeProblem(response, 401, "Authentication required");
                    })
                .accessDeniedHandler(
                    (request, response, error) ->
                        writeProblem(response, 403, "Permission denied")));
    http.addFilterBefore(
        new OncePerRequestFilter() {
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws IOException, ServletException {
            int limit =
                Set.of(
                            "/v1/worker/dispatch",
                            "/v1/worker/heartbeat",
                            "/v1/worker/complete",
                            "/v1/worker/fail")
                        .contains(request.getServletPath())
                    ? WorkflowLimits.WORKER_REQUEST_BYTES
                    : WorkflowLimits.PUBLIC_REQUEST_BYTES;
            byte[] bytes = request.getInputStream().readNBytes(limit + 1);
            if (bytes.length > limit) {
              writeProblem(response, 413, "Payload limit exceeded");
              return;
            }
            HttpServletRequestWrapper wrapped =
                new HttpServletRequestWrapper(request) {
                  public ServletInputStream getInputStream() {
                    ByteArrayInputStream in = new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                      public int read() {
                        return in.read();
                      }

                      public boolean isFinished() {
                        return in.available() == 0;
                      }

                      public boolean isReady() {
                        return true;
                      }

                      public void setReadListener(ReadListener listener) {}
                    };
                  }

                  public BufferedReader getReader() {
                    return new BufferedReader(
                        new InputStreamReader(
                            getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                  }
                };
            chain.doFilter(wrapped, response);
          }
        },
        BasicAuthenticationFilter.class);
    return http.build();
  }

  private static void writeProblem(HttpServletResponse response, int status, String title)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    new com.fasterxml.jackson.databind.ObjectMapper()
        .writeValue(
            response.getOutputStream(),
            Map.of("type", "about:blank", "title", title, "status", status));
  }

  @Bean
  @Profile("dev")
  UserDetailsService users(Environment env) {
    List<UserDetails> users = new ArrayList<>();
    for (String role : List.of("deployer", "operator", "viewer", "worker"))
      users.add(
          User.withUsername(role)
              .password("{noop}" + env.getProperty("DEMO_PASSWORD", "disposable-local-only"))
              .roles(role)
              .build());
    return new org.springframework.security.provisioning.InMemoryUserDetailsManager(users);
  }

  private String tenant(String role) {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    boolean allowed =
        a.getAuthorities().stream()
            .anyMatch(
                g ->
                    g.getAuthority().equals("ROLE_" + role)
                        || g.getAuthority().equals("SCOPE_" + role));
    if (!allowed)
      throw new org.springframework.security.access.AccessDeniedException("role required");
    if (a.getPrincipal() instanceof Jwt jwt) {
      String tenant = jwt.getClaimAsString("tenant");
      if (tenant == null || !tenant.matches("[a-zA-Z0-9-]{1,64}"))
        throw new org.springframework.security.access.AccessDeniedException("tenant missing");
      return tenant;
    }
    return "demo";
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    if (!store.healthy()) throw new IllegalStateException("database unavailable");
    return Map.of("status", "UP");
  }

  @PostMapping("/v1/definitions")
  public Map<String, String> deploy(@RequestBody Definition d) {
    return Map.of("hash", store.deploy(tenant("deployer"), d));
  }

  public record Start(String definitionHash, ExecutionInput input) {}

  @PostMapping("/v1/executions")
  public Map<String, UUID> start(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Start request) {
    return Map.of(
        "id", store.start(tenant("operator"), request.definitionHash(), key, request.input()));
  }

  @GetMapping("/v1/executions/{id}")
  public WorkflowStore.View get(@PathVariable UUID id) {
    return store.get(tenant("viewer"), id);
  }

  @GetMapping("/v1/executions/{id}/history")
  public List<Interpreter.Event> history(@PathVariable UUID id) {
    return store.history(tenant("viewer"), id);
  }

  @GetMapping("/v1/executions/{id}/history/page")
  public HistoryPage historyPage(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(required = false) Long snapshotVersion,
      @RequestParam(defaultValue = "100") int limit) {
    return store.historyPage(tenant("viewer"), id, after, snapshotVersion, limit);
  }

  public record Signal(String name, Value value) {}

  @PostMapping("/v1/executions/{id}/signals")
  public Map<String, Boolean> signal(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Signal signal) {
    return Map.of(
        "accepted", store.signal(tenant("operator"), id, key, signal.name(), signal.value()));
  }

  @PostMapping("/v1/executions/{id}/cancel")
  public void cancel(@PathVariable UUID id) {
    store.cancel(tenant("operator"), id);
  }

  public record Resolution(String task, String decision, String reason, List<Value> result) {}

  @PostMapping("/v1/executions/{id}/resolve")
  public void resolve(@PathVariable UUID id, @RequestBody Resolution r) {
    store.resolve(
        tenant("operator"),
        id,
        r.task(),
        r.decision(),
        SecurityContextHolder.getContext().getAuthentication().getName(),
        r.reason(),
        r.result());
  }

  @PostMapping("/v1/executions/{id}/retry-compensation")
  public void retryCompensation(@PathVariable UUID id, @RequestBody Resolution r) {
    store.retryCompensation(
        tenant("operator"),
        id,
        r.task(),
        SecurityContextHolder.getContext().getAuthentication().getName(),
        r.reason());
  }

  public record ClaimRequest(String worker, int batch, int leaseSeconds) {}

  @PostMapping("/v1/worker/claim")
  public List<WorkflowStore.Claim> claim(@RequestBody ClaimRequest r) {
    return store.claim(tenant("worker"), r.worker(), r.batch(), r.leaseSeconds());
  }

  private void check(WorkflowStore.Claim q) {
    if (!tenant("worker").equals(q.tenant()))
      throw new org.springframework.security.access.AccessDeniedException("tenant mismatch");
  }

  @PostMapping("/v1/worker/dispatch")
  public void dispatch(@RequestBody WorkflowStore.Claim q) {
    check(q);
    store.dispatch(q);
  }

  public record Completion(WorkflowStore.Claim claim, List<Value> result) {}

  @PostMapping("/v1/worker/complete")
  public void complete(@RequestBody Completion c) {
    check(c.claim());
    store.complete(c.claim(), c.result());
  }

  @PostMapping("/v1/worker/heartbeat")
  public void heartbeat(@RequestBody WorkflowStore.Claim q) {
    check(q);
    store.heartbeat(q, 10);
  }

  public record Failure(WorkflowStore.Claim claim, boolean definitive, String reason) {}

  @PostMapping("/v1/worker/fail")
  public void fail(@RequestBody Failure f) {
    check(f.claim());
    store.fail(f.claim(), f.definitive(), f.reason());
  }

  @ExceptionHandler(PersistenceFailure.class)
  public ResponseEntity<ProblemDetail> storageProblem(PersistenceFailure failure) {
    ProblemDetail problem = ProblemDetail.forStatus(503);
    problem.setTitle("Workflow storage unavailable");
    problem.setDetail("Retry with the same idempotency key after storage recovers");
    return ResponseEntity.status(503).body(problem);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    NoSuchElementException.class,
    IllegalStateException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.security.access.AccessDeniedException.class
  })
  public ResponseEntity<ProblemDetail> problem(RuntimeException error) {
    int status =
        error instanceof org.springframework.security.access.AccessDeniedException
            ? 403
            : error instanceof NoSuchElementException
                ? 404
                : error instanceof IllegalStateException ? 409 : 400;
    ProblemDetail p = ProblemDetail.forStatus(status);
    p.setTitle(status == 409 ? "State conflict" : "Invalid workflow request");
    p.setDetail(
        error.getMessage() != null && error.getMessage().equals("STALE_GENERATION")
            ? "STALE_GENERATION"
            : "Request rejected; check the contract and current execution state");
    return ResponseEntity.status(status).body(p);
  }
}
