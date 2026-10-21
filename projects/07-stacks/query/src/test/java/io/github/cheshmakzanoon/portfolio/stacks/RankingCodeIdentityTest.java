package io.github.cheshmakzanoon.portfolio.stacks;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RankingCodeIdentityTest {
  @TempDir Path root;
  private static final String PACKAGE = "io/github/cheshmakzanoon/portfolio/stacks/";

  private static URL origin(String spec) throws MalformedURLException {
    return new URL(
        null,
        spec,
        new URLStreamHandler() {
          @Override
          protected URLConnection openConnection(URL url) {
            throw new AssertionError("no network origin resolution");
          }
        });
  }

  @Test
  void ordinaryAndBootNestedOriginsBindWholeOuterArtifactWithoutOpeningUrls() throws Exception {
    Path outer = root.resolve("application with spaces.jar");
    assertEquals(outer, RankingCodeIdentity.artifactPath(outer.toUri().toURL()));
    assertEquals(
        outer,
        RankingCodeIdentity.artifactPath(origin("jar:" + outer.toUri() + "!/BOOT-INF/classes/")));
    String nested =
        "jar:nested:" + outer.toUri().getRawPath() + "/!BOOT-INF/lib/stacks-query.jar!/";
    assertEquals(outer, RankingCodeIdentity.artifactPath(origin(nested)));
    assertEquals(outer, RankingCodeIdentity.artifactPath(origin(nested.substring(4))));
    assertThrows(
        IOException.class,
        () -> RankingCodeIdentity.artifactPath(origin("https://example.invalid/classes/")));
    assertThrows(
        IOException.class,
        () -> RankingCodeIdentity.artifactPath(origin("nested:/missing-delimiter.jar")));
    Files.write(outer, new byte[] {1, 2, 3});
    String before = RankingCodeIdentity.fingerprint(outer);
    Files.write(outer, new byte[] {1, 2, 4});
    assertNotEquals(before, RankingCodeIdentity.fingerprint(outer));
    Path moved = root.resolve("moved.jar");
    Files.copy(outer, moved);
    assertEquals(RankingCodeIdentity.fingerprint(outer), RankingCodeIdentity.fingerprint(moved));
    try (var file = java.nio.channels.FileChannel.open(outer, StandardOpenOption.WRITE)) {
      file.position(536870912);
      file.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
    }
    assertThrows(IOException.class, () -> RankingCodeIdentity.fingerprint(outer));
  }

  @Test
  void
      explodedIdentityIncludesAllAnonymousNestedAndFuturePackageClassesAndRejectsMissingOrOversize()
          throws Exception {
    Path packageRoot = root.resolve("classes").resolve(PACKAGE);
    Files.createDirectories(packageRoot);
    Path original = packageRoot.resolve("CodeIdentifierAnalyzer.class"),
        anonymous = packageRoot.resolve("CodeIdentifierAnalyzer$1.class");
    Files.write(original, new byte[] {1});
    String one = RankingCodeIdentity.fingerprint(root.resolve("classes"));
    Files.write(anonymous, new byte[] {2});
    String two = RankingCodeIdentity.fingerprint(root.resolve("classes"));
    assertNotEquals(one, two);
    Path future = packageRoot.resolve("future/UnlistedFeature$Helper.class");
    Files.createDirectories(future.getParent());
    Files.write(future, new byte[] {3});
    assertNotEquals(two, RankingCodeIdentity.fingerprint(root.resolve("classes")));
    Files.write(anonymous, new byte[] {4});
    assertNotEquals(two, RankingCodeIdentity.fingerprint(root.resolve("classes")));
    Path link = packageRoot.resolve("Symlink.class");
    Files.createSymbolicLink(link, original);
    assertThrows(IOException.class, () -> RankingCodeIdentity.fingerprint(root.resolve("classes")));
    Files.delete(link);
    Path large = packageRoot.resolve("Large.class");
    try (var file =
        java.nio.channels.FileChannel.open(
            large, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      file.position(1048576);
      file.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
    }
    assertThrows(IOException.class, () -> RankingCodeIdentity.fingerprint(root.resolve("classes")));
    assertThrows(IOException.class, () -> RankingCodeIdentity.fingerprint(root.resolve("missing")));
    Path empty = root.resolve("empty");
    Files.createDirectories(empty.resolve(PACKAGE));
    assertThrows(IOException.class, () -> RankingCodeIdentity.fingerprint(empty));
    assertThrows(IOException.class, () -> RankingCodeIdentity.fingerprint(root));
  }
}
