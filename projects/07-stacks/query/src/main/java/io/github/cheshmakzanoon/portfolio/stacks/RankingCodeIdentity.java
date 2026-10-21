package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * Complete supported artifact identity; exploded development roots include every original class.
 */
final class RankingCodeIdentity {
  private static final String PACKAGE = "io/github/cheshmakzanoon/portfolio/stacks";
  private static final long ARTIFACT_BYTES = 536_870_912;
  static final String SHA256 = capture();

  private RankingCodeIdentity() {}

  private static String capture() {
    try {
      // Each original production module is an origin, never a handwritten transitive class list.
      // A supported shaded CLI or Boot executable resolves every origin to its entire outer JAR.
      List<Class<?>> anchors =
          List.of(
              RankingCodeIdentity.class,
              DocumentMapper.class,
              SnapshotManifest.class,
              org.apache.lucene.util.Version.class,
              org.apache.lucene.analysis.en.PorterStemFilter.class);
      StringBuilder result = new StringBuilder("complete-inference-v2\n");
      Map<Path, String> captured = new HashMap<>();
      for (Class<?> anchor : anchors) {
        var domain = anchor.getProtectionDomain();
        if (domain == null
            || domain.getCodeSource() == null
            || domain.getCodeSource().getLocation() == null)
          throw new IOException("missing inference origin");
        Path path = artifactPath(domain.getCodeSource().getLocation()).toAbsolutePath().normalize();
        String hash = captured.get(path);
        if (hash == null) {
          hash = fingerprint(path);
          captured.put(path, hash);
        }
        result.append(anchor.getName()).append(':').append(hash).append('\n');
      }
      return Digests.sha256(result.toString());
    } catch (IOException | IllegalArgumentException unavailable) {
      throw new UncheckedIOException(
          "complete inference identity unavailable",
          unavailable instanceof IOException io ? io : new IOException(unavailable));
    }
  }

  /** Supports ordinary file origins and Spring Boot jar:nested outer executable origins. */
  static Path artifactPath(URL location) throws IOException {
    String spec = location.toExternalForm();
    while (spec.startsWith("jar:")) spec = spec.substring(4);
    try {
      if (spec.startsWith("file:")) {
        int nested = spec.indexOf("!/");
        return Path.of(URI.create(nested < 0 ? spec : spec.substring(0, nested)));
      }
      if (spec.startsWith("nested:")) {
        int nested = spec.indexOf("/!");
        if (nested < 0) throw new IOException("missing outer Boot artifact");
        return Path.of(URI.create("file:" + spec.substring("nested:".length(), nested)));
      }
    } catch (IllegalArgumentException invalid) {
      throw new IOException("invalid inference origin", invalid);
    }
    throw new IOException("unsupported inference origin protocol");
  }

  static String fingerprint(Path origin) throws IOException {
    if (Files.isSymbolicLink(origin)) throw new IOException("symbolic inference origin");
    if (Files.isRegularFile(origin, LinkOption.NOFOLLOW_LINKS))
      return hashFile(origin, ARTIFACT_BYTES).sha256();
    if (!Files.isDirectory(origin, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("missing inference origin");
    Path packageRoot = origin.resolve(PACKAGE);
    if (!Files.isDirectory(packageRoot, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("missing original class package");
    List<Path> classes = new ArrayList<>();
    long total = 0;
    int visited = 0;
    try (var walk = Files.walk(packageRoot)) {
      var iterator = walk.iterator();
      while (iterator.hasNext()) {
        Path path = iterator.next();
        if (++visited > 4096) throw new IOException("inference origin entry quota");
        if (Files.isSymbolicLink(path)) throw new IOException("symbolic original class path");
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || !path.toString().endsWith(".class")) continue;
        if (classes.size() >= 2048
            || Files.size(path) > 1_048_576
            || (total += Files.size(path)) > 67_108_864)
          throw new IOException("original class identity quota");
        classes.add(path);
      }
    } catch (UncheckedIOException failed) {
      throw failed.getCause();
    }
    if (classes.isEmpty()) throw new IOException("empty original inference package");
    classes.sort(Comparator.comparing(p -> origin.relativize(p).toString().replace('\\', '/')));
    StringBuilder out = new StringBuilder("exploded-original-v2\n");
    long readBytes = 0;
    for (Path path : classes) {
      var hashed = hashFile(path, Math.min(1_048_576, 67_108_864 - readBytes));
      readBytes += hashed.bytes();
      out.append(origin.relativize(path).toString().replace('\\', '/'))
          .append(':')
          .append(hashed.sha256())
          .append('\n');
    }
    return Digests.sha256(out.toString());
  }

  private record Hashed(String sha256, long bytes) {}

  private static Hashed hashFile(Path path, long limit) throws IOException {
    if (Files.size(path) > limit) throw new IOException("inference artifact byte quota");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long total = 0;
      try (var input =
          Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if ((total += read) > limit) throw new IOException("inference artifact byte quota");
          digest.update(buffer, 0, read);
        }
      }
      return new Hashed(HexFormat.of().formatHex(digest.digest()), total);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
