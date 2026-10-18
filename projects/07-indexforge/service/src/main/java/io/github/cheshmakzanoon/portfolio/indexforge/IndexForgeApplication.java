package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class IndexForgeApplication {
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(IndexForgeApplication.class);
    application.setDefaultProperties(
        Map.of(
            "server.address",
            "127.0.0.1",
            "server.port",
            "18700",
            "spring.profiles.default",
            "dev",
            "server.tomcat.threads.max",
            "16",
            "server.tomcat.accept-count",
            "32",
            "server.max-http-request-header-size",
            "16KB"));
    application.run(args);
  }

  @Bean
  AuthorizationFilter authorization(Environment env) {
    AuthorizationFilter auth = new AuthorizationFilter();
    String users =
        env.matchesProfiles("dev")
            ? env.getProperty("indexforge.users", "alice:demo:public:true;bob:other:public:false")
            : env.getRequiredProperty("indexforge.users");
    for (String spec : users.split(";")) {
      String[] p = spec.split(":", -1);
      if (p.length != 4)
        throw new IllegalArgumentException("users format subject:tenant:repo,repo:admin");
      auth.update(p[0], p[1], Set.of(p[2].split(",")), Boolean.parseBoolean(p[3]));
    }
    return auth;
  }

  @Bean(destroyMethod = "close")
  SearchEngine searchEngine(Environment env, AuthorizationFilter auth) throws IOException {
    return new SearchEngine(
        Path.of(env.getProperty("indexforge.data", ".runtime/indexforge")),
        auth,
        QueryPlanner.Mode.valueOf(env.getProperty("indexforge.ranking-mode", "BASELINE")));
  }

  @Bean
  @Profile("dev")
  LocalOidcIssuer localIssuer(Environment env) throws IOException {
    String binding = env.getProperty("server.address", "127.0.0.1");
    if (!InetAddress.getByName(binding).isLoopbackAddress())
      throw new IllegalArgumentException("disposable development issuer requires loopback binding");
    LocalOidcIssuer issuer =
        new LocalOidcIssuer(Integer.parseInt(env.getProperty("indexforge.issuer-port", "18701")));
    Path data = Path.of(env.getProperty("indexforge.data", ".runtime/indexforge"));
    Files.createDirectories(data);
    DurableFiles.atomicWrite(
        data.resolve("dev-token"),
        issuer
            .token("alice", "demo", Instant.now().plusSeconds(3600), "indexforge")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    try {
      Files.setPosixFilePermissions(
          data.resolve("dev-token"),
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
    }
    return issuer;
  }
}
