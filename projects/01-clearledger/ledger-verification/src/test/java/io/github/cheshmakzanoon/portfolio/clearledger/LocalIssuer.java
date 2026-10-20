package io.github.cheshmakzanoon.portfolio.clearledger;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Disposable local OIDC issuer; its discovery and JWKS endpoints really serve RSA public keys. */
final class LocalIssuer implements AutoCloseable {
  final RSAKey key;
  final HttpServer server;
  final String issuer;

  LocalIssuer() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("local-oidc").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/.well-known/openid-configuration",
        e -> {
          byte[] b =
              ("{\"issuer\":\""
                      + issuer
                      + "\",\"jwks_uri\":\""
                      + issuer
                      + "/jwks\",\"authorization_endpoint\":\""
                      + issuer
                      + "/authorize\",\"token_endpoint\":\""
                      + issuer
                      + "/token\",\"response_types_supported\":[\"id_token\"],\"subject_types_supported\":[\"public\"],\"id_token_signing_alg_values_supported\":[\"RS256\"]}")
                  .getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.createContext(
        "/jwks",
        e -> {
          byte[] b = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.start();
  }

  String token(
      UUID tenant,
      String scopes,
      String issuerValue,
      String audience,
      Instant expiry,
      boolean badKey)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuerValue)
            .subject("synthetic-reviewer")
            .audience(audience)
            .issueTime(new Date())
            .expirationTime(Date.from(expiry))
            .claim("tenant", tenant.toString())
            .claim("scope", scopes)
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("local-oidc").build(), claims);
    jwt.sign(new RSASSASigner(badKey ? new RSAKeyGenerator(2048).generate() : key));
    return jwt.serialize();
  }

  String token(UUID tenant, String scopes) throws Exception {
    return token(tenant, scopes, issuer, "clearledger", Instant.now().plusSeconds(300), false);
  }

  String identityToken(Object subject, Object tenantValue) throws Exception {
    var claims = new HashMap<String, Object>();
    claims.put("iss", issuer);
    claims.put("aud", List.of("clearledger"));
    claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
    claims.put("scope", "ledger.read");
    if (subject != null) claims.put("sub", subject);
    if (tenantValue != null) claims.put("tenant", tenantValue);
    var jwt =
        new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("local-oidc").build(),
            new Payload(claims));
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  String rawIdentityToken(UUID tenant, String subjectJson) throws Exception {
    String json =
        "{\"iss\":\""
            + issuer
            + "\",\"aud\":[\"clearledger\"],\"exp\":"
            + Instant.now().plusSeconds(300).getEpochSecond()
            + ",\"scope\":\"ledger.read\",\"tenant\":\""
            + tenant
            + "\",\"sub\":"
            + subjectJson
            + "}";
    var jwt =
        new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("local-oidc").build(),
            new Payload(json));
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  public void close() {
    server.stop(0);
  }
}
