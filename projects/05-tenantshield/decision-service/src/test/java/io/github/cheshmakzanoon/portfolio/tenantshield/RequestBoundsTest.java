package io.github.cheshmakzanoon.portfolio.tenantshield;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class RequestBoundsTest {
  @Test
  void encodedTenantAliasesShareTheSameQuota() throws Exception {
    RequestBounds bounds = new RequestBounds();
    var encoded = request();
    encoded.setRequestURI("/v1/tenants/har%62or/decisions");
    bounds.doFilter(encoded, new MockHttpServletResponse(), (a, b) -> {});
    assertThat(bounds.tenantStats("harbor").get("maximumInFlight")).isEqualTo(1);
    assertThat(bounds.tenantStats("har%62or").get("maximumInFlight")).isZero();
    var invalid = request();
    invalid.setRequestURI("/v1/tenants/harbor;alias/decisions");
    var response = new MockHttpServletResponse();
    bounds.doFilter(
        invalid,
        response,
        (a, b) -> {
          throw new AssertionError();
        });
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void unknownLengthAndDishonestLengthBodiesAreLimitedBeforeDownstreamAllocation()
      throws Exception {
    RequestBounds bounds = new RequestBounds();
    for (int length : List.of(-1, 1, 262145)) {
      MockHttpServletRequest request =
          new MockHttpServletRequest() {
            @Override
            public int getContentLength() {
              return length;
            }

            @Override
            public long getContentLengthLong() {
              return length;
            }
          };
      request.setMethod("POST");
      request.setRequestURI("/v1/tenants/harbor/decisions");
      request.setContent(new byte[262145]);
      MockHttpServletResponse response = new MockHttpServletResponse();
      AtomicBoolean called = new AtomicBoolean();
      bounds.doFilter(request, response, (a, b) -> called.set(true));
      assertThat(called).isFalse();
      assertThat(response.getStatus()).isEqualTo(413);
    }
    MockHttpServletRequest valid = request();
    valid.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean called = new AtomicBoolean();
    bounds.doFilter(
        valid,
        response,
        (a, b) -> {
          assertThat(a.getInputStream().readAllBytes()).containsExactly((byte) '{', (byte) '}');
          called.set(true);
        });
    assertThat(called).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void concurrentBoundRejectsExcessAndReleasesPermitsAfterCompletion() throws Exception {
    RequestBounds bounds = new RequestBounds();
    CountDownLatch entered = new CountDownLatch(64), release = new CountDownLatch(1);
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (int n = 0; n < 64; n++)
        futures.add(
            threads.submit(
                () -> {
                  try {
                    bounds.doFilter(
                        request(),
                        new MockHttpServletResponse(),
                        (a, b) -> {
                          entered.countDown();
                          try {
                            if (!release.await(10, TimeUnit.SECONDS))
                              throw new AssertionError("latch deadline");
                          } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                          }
                        });
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      MockHttpServletResponse over = new MockHttpServletResponse();
      bounds.doFilter(
          request(),
          over,
          (a, b) -> {
            throw new AssertionError("over-capacity request reached chain");
          });
      assertThat(over.getStatus()).isEqualTo(429);
      assertThat(bounds.maximumInFlight()).isEqualTo(64);
      release.countDown();
      for (var f : futures) f.get(10, TimeUnit.SECONDS);
    }
    MockHttpServletResponse after = new MockHttpServletResponse();
    AtomicBoolean called = new AtomicBoolean();
    bounds.doFilter(request(), after, (a, b) -> called.set(true));
    assertThat(called).isTrue();
    assertThat(bounds.tenantStats("harbor").get("rejections")).isEqualTo(1);
  }

  static MockHttpServletRequest request() {
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setMethod("POST");
    r.setRequestURI("/v1/tenants/harbor/decisions");
    r.setContent(new byte[0]);
    return r;
  }
}
