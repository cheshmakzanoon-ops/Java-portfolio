package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RankingIdentityRestartTest {
  @TempDir Path root;
  private static final String PACKAGE = "io.github.cheshmakzanoon.portfolio.indexforge.";
  private static final String DIRECTORY = PACKAGE.replace('.', '/');

  @Test
  void sameNamedProfileAndSameModelWithActuallyChangedWeightRejectOldRestartCursor()
      throws Exception {
    double[] scales = new double[10], weights = new double[10];
    Arrays.fill(scales, 1);
    weights[0] = 1;
    var model =
        new LinearRankingModel(new double[10], scales, weights, "a".repeat(64), "b".repeat(64), 1);
    Path modelPath = root.resolve("model.bin");
    model.writeNew(modelPath);
    var configuration = RankingConfiguration.from("PAIRWISE", modelPath.toString());
    Path data = root.resolve("data");
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), true);
    assertEquals(1, access.version());
    float oldScore;
    String cursor, baselineCursor, oldIdentity;
    try (var engine =
        new SearchEngine(
            data, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.CODE_HIGH, configuration)) {
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (String name : List.of("A", "B")) {
        String source =
            "class "
                + name
                + " { byte[] hash(byte[] input) throws Exception {"
                + "return MessageDigest.getInstance(\"SHA-256\").digest(input);}}";
        entries.add(
            new SnapshotManifest.Entry(
                name + ".java",
                engine.contentStore().put(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "java",
                "source",
                List.of("hash", "digest"),
                "",
                ""));
      }
      engine.ingest(
          access,
          "real-code",
          new SnapshotManifest("t", "r", "v1", true, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      var request = new SearchEngine.Request("cryptographic digest", 1, null, Map.of());
      var response = engine.search("reader", request);
      assertEquals(2, response.totalHits());
      cursor = response.cursor();
      assertNotNull(cursor);
      oldScore = response.results().getFirst().score();
      oldIdentity = engine.rankingIdentity();
      assertTrue(
          response.results().getFirst().explanation().contains("linearModel=" + model.identity()));
      var baseline = engine.search("reader", request, QueryPlanner.Mode.BASELINE);
      assertEquals(2, baseline.totalHits());
      baselineCursor = baseline.cursor();
      assertNotNull(baselineCursor);
      assertTrue(baseline.scoringVersion().startsWith("bm25-content-v2/"));
    }
    Path original =
        Path.of(RankingProfile.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path changed = root.resolve("changed-query-classes");
    copyClasses(original, changed);
    assertEquals(
        RankingCodeIdentity.fingerprint(original),
        RankingCodeIdentity.fingerprint(changed),
        "moving identical classes does not change identity");
    Path profile = changed.resolve(DIRECTORY + "RankingProfile.class");
    byte[] bytecode = Files.readAllBytes(profile);
    assertTrue(changeFloatConstant(bytecode, 12, 24) > 0);
    Files.write(profile, bytecode);
    assertNotEquals(
        RankingCodeIdentity.fingerprint(original), RankingCodeIdentity.fingerprint(changed));
    URL[] origins = {
      changed.toUri().toURL(),
      DocumentMapper.class.getProtectionDomain().getCodeSource().getLocation(),
      SnapshotManifest.class.getProtectionDomain().getCodeSource().getLocation()
    };
    try (var loader = new ChildFirst(origins)) {
      Class<?> profileClass = loader.loadClass(PACKAGE + "RankingProfile");
      Object selectedProfile = profileClass.getField("CODE_HIGH").get(null);
      assertEquals("CODE_HIGH", selectedProfile.toString());
      assertEquals(24, profileClass.getField("codeFeatures").getFloat(selectedProfile));
      assertEquals(12, RankingProfile.CODE_HIGH.codeFeatures);
      assertNotEquals(
          RankingProfile.CODE_HIGH.parametersIdentity(),
          profileClass.getMethod("parametersIdentity").invoke(selectedProfile));
      Class<?> authClass = loader.loadClass(PACKAGE + "AuthorizationFilter");
      Object restartedAuth = authClass.getConstructor().newInstance();
      Object restartedAccess =
          authClass
              .getMethod("update", String.class, String.class, Set.class, boolean.class)
              .invoke(restartedAuth, "reader", "t", Set.of("r"), true);
      assertEquals(
          1L,
          restartedAccess.getClass().getMethod("version").invoke(restartedAccess),
          "permission version/scope stay unchanged; the rejection must bind changed inference");
      Class<?> configClass = loader.loadClass(PACKAGE + "RankingConfiguration");
      Object restartedConfig =
          configClass
              .getMethod("from", String.class, String.class)
              .invoke(null, "PAIRWISE", modelPath.toString());
      Object sameModel = configClass.getMethod("model").invoke(restartedConfig);
      assertEquals(model.identity(), sameModel.getClass().getMethod("identity").invoke(sameModel));
      Class<?> modeClass = loader.loadClass(PACKAGE + "QueryPlanner$Mode");
      Class<?> engineClass = loader.loadClass(PACKAGE + "SearchEngine");
      Object engine =
          engineClass
              .getConstructor(Path.class, authClass, modeClass, profileClass, configClass)
              .newInstance(
                  data,
                  restartedAuth,
                  modeClass.getField("SOURCE_AWARE").get(null),
                  selectedProfile,
                  restartedConfig);
      try (AutoCloseable close = (AutoCloseable) engine) {
        assertNotEquals(oldIdentity, engineClass.getMethod("rankingIdentity").invoke(engine));
        Class<?> requestClass = loader.loadClass(PACKAGE + "SearchEngine$Request");
        Constructor<?> request =
            requestClass.getConstructor(String.class, int.class, String.class, Map.class);
        Method search = engineClass.getMethod("search", String.class, requestClass);
        Object fresh =
            search.invoke(
                engine, "reader", request.newInstance("cryptographic digest", 1, null, Map.of()));
        List<?> results = (List<?>) fresh.getClass().getMethod("results").invoke(fresh);
        float newScore =
            ((Number) results.getFirst().getClass().getMethod("score").invoke(results.getFirst()))
                .floatValue();
        assertTrue(
            newScore > oldScore,
            "the actual CODE_HIGH hint weight changed, not only an injected identity string");
        var mismatch =
            assertThrows(
                InvocationTargetException.class,
                () ->
                    search.invoke(
                        engine,
                        "reader",
                        request.newInstance("cryptographic digest", 1, cursor, Map.of())));
        assertInstanceOf(IllegalArgumentException.class, mismatch.getCause());
        Method explicit = engineClass.getMethod("search", String.class, requestClass, modeClass);
        var baselineMismatch =
            assertThrows(
                InvocationTargetException.class,
                () ->
                    explicit.invoke(
                        engine,
                        "reader",
                        request.newInstance("cryptographic digest", 1, baselineCursor, Map.of()),
                        modeClass.getField("BASELINE").get(null)));
        assertInstanceOf(IllegalArgumentException.class, baselineMismatch.getCause());
      }
    }
    assertEquals(model.identity(), LinearRankingModel.read(modelPath).identity());
  }

  private static void copyClasses(Path origin, Path target) throws IOException {
    assertTrue(
        Files.isDirectory(origin),
        "this regression runs in the declared Maven exploded test workflow");
    try (var walk = Files.walk(origin.resolve(DIRECTORY))) {
      for (Path source :
          walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class")).toList()) {
        Path destination = target.resolve(origin.relativize(source));
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
      }
    }
  }

  private static int changeFloatConstant(byte[] bytes, float before, float after)
      throws IOException {
    ByteBuffer input = ByteBuffer.wrap(bytes);
    assertEquals(0xcafebabe, input.getInt());
    input.position(8);
    int count = Short.toUnsignedInt(input.getShort()), changed = 0;
    for (int i = 1; i < count; i++) {
      int tag = Byte.toUnsignedInt(input.get());
      switch (tag) {
        case 1 -> {
          int size = Short.toUnsignedInt(input.getShort());
          input.position(input.position() + size);
        }
        case 3 -> input.position(input.position() + 4);
        case 4 -> {
          int at = input.position();
          float value = input.getFloat();
          if (value == before) {
            input.putFloat(at, after);
            changed++;
          }
        }
        case 5, 6 -> {
          input.position(input.position() + 8);
          i++;
        }
        case 7, 8, 16, 19, 20 -> input.position(input.position() + 2);
        case 9, 10, 11, 12, 17, 18 -> input.position(input.position() + 4);
        case 15 -> input.position(input.position() + 3);
        default -> throw new IOException("unknown class constant tag " + tag);
      }
    }
    return changed;
  }

  private static final class ChildFirst extends URLClassLoader {
    ChildFirst(URL[] urls) {
      super(urls, RankingIdentityRestartTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null && name.startsWith(PACKAGE)) {
          try {
            loaded = findClass(name);
          } catch (ClassNotFoundException absent) {
            /* dependency parent */
          }
        }
        if (loaded == null) loaded = super.loadClass(name, false);
        if (resolve) resolveClass(loaded);
        return loaded;
      }
    }
  }
}
