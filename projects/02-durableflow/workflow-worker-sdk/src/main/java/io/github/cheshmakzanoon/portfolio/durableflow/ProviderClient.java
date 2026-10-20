package io.github.cheshmakzanoon.portfolio.durableflow;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Fixed-destination transport; ambiguous responses must never become successful activity results.
 */
final class ProviderClient implements AutoCloseable {
  private static final int MAX_RESPONSE_BYTES = 4096;
  private final URI endpoint;
  private final HttpClient client;

  ProviderClient(URI endpoint) {
    if (endpoint == null
        || endpoint.getScheme() == null
        || !Set.of("http", "https").contains(endpoint.getScheme())
        || endpoint.getHost() == null
        || !"/effects".equals(endpoint.getPath())
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null)
      throw new IllegalArgumentException("configured provider endpoint required");
    this.endpoint = endpoint;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  String execute(String key, Definition.Safety safety, String mode, String parameters)
      throws IOException, InterruptedException, Worker.ProviderRejectedException {
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(3))
            .header("Idempotency-Key", key)
            .header("X-Safety", safety.name())
            .header("X-Failure-Mode", mode)
            .POST(HttpRequest.BodyPublishers.ofString(parameters))
            .build();
    HttpResponse<byte[]> response =
        client.send(
            request,
            HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
    if (response.statusCode() == 409)
      throw new IllegalArgumentException("provider parameter conflict");
    // Only the configured provider's explicit pre-effect refusal is definitive.
    // A generic error status can arrive after the provider has committed an effect.
    if (response.statusCode() == 503
        && response.headers().firstValue("X-Effect-Outcome").orElse("").equals("NOT_APPLIED"))
      throw new Worker.ProviderRejectedException();
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new IOException("provider did not acknowledge the effect");
    // Reject malformed text rather than persisting a receipt containing replacement characters.
    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
  }

  @Override
  public void close() {
    client.close();
  }
}
