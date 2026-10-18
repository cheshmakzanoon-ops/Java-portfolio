package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceCatalogTest {
  @TempDir Path root;

  @Test
  void actualDocTreesExcludeExamplesTargetsAndPrivateBodiesAndSurvivePackagedWorker()
      throws Exception {
    String source =
        """
        package sample;
        /** Processes observations. <p>EXCLUDEDSECOND paragraph. */
        public class Processor {
          /** Computes a checksum using {@link java.lang.Missing friendlylabel}.
           * <pre>EXCLUDEDEXAMPLE</pre>
           * @param input supplied contents
           * @return computed result
           */
          public byte[] apply(byte[] input){return input;}
          /** PRIVATEDECOY */ private void hidden(){}
          public interface Nested { /** Opens observations. */ byte[] open(byte[] input); }
        }
        """;
    var parsed = SourceCatalogParser.parse(source);
    assertEquals(2, parsed.types().size());
    assertTrue(
        parsed.types().stream()
            .flatMap(t -> t.members().stream())
            .noneMatch(m -> m.name().equals("hidden")));
    try (var worker = new SourceParserClient(root.resolve("worker"))) {
      assertEquals(parsed, worker.catalog(source));
    }
    var catalog =
        SourceApiCatalog.fromParsed(
            List.of(parsed), "a".repeat(64), "b".repeat(64), "test", "actual-test-license");
    var api = catalog.unique("sample.Processor", "apply", List.of("byte[]")).orElseThrow();
    assertTrue(
        api.terms()
            .containsAll(
                SourceApiCatalog.terms(
                    "checksum friendlylabel supplied contents computed result")));
    assertTrue(
        Collections.disjoint(
            api.terms(),
            SourceApiCatalog.terms("EXCLUDEDSECOND EXCLUDEDEXAMPLE Missing PRIVATEDECOY")));
    assertEquals(Set.of("byte[]"), catalog.returns("sample.Processor", "apply", 1));
    assertTrue(catalog.unique("sample.Processor", "apply", List.of("int[]")).isEmpty());
    Path file = root.resolve("catalog.bin");
    catalog.writeNew(file);
    assertEquals(catalog.identity(), SourceApiCatalog.read(file).identity());
    assertThrows(FileAlreadyExistsException.class, () -> catalog.writeNew(file));
    byte[] bytes = Files.readAllBytes(file);
    bytes[bytes.length - 1] ^= 1;
    Files.write(file, bytes);
    assertThrows(IOException.class, () -> SourceApiCatalog.read(file));
  }

  @Test
  void unresolvedFormalTypesCannotCollapseDistinctOverloadsOrBecomeSupportedArguments()
      throws Exception {
    var parsed =
        SourceCatalogParser.parse(
            "package sample; public class Generic<T> {public void use(T value){} public void use(T[] value){} public void use(){}}");
    var catalog =
        SourceApiCatalog.fromParsed(List.of(parsed), "a".repeat(64), "b".repeat(64), "test", "");
    assertTrue(catalog.unique("sample.Generic", "use", List.of()).isPresent());
    assertTrue(catalog.unique("sample.Generic", "use", List.of("java.lang.Object")).isEmpty());
    assertTrue(catalog.unique("sample.Generic", "use", List.of("java.lang.Object[]")).isEmpty());
    assertEquals(Set.of("void"), catalog.returns("sample.Generic", "use", 1));
  }

  @Test
  void workerCodecRejectsTruncationTrailingCompressionAndOversizeBeforeDecode() throws Exception {
    var parsed =
        SourceCatalogParser.parse("package sample; public class Tiny {public void open(){}}");
    byte[] valid = SourceCatalogWire.encode(parsed);
    assertEquals(parsed, SourceCatalogWire.decode(valid));
    assertArrayEquals(valid, SourceCatalogWire.encode(parsed));
    assertThrows(
        IOException.class, () -> SourceCatalogWire.decode(Arrays.copyOf(valid, valid.length - 1)));
    assertThrows(
        IOException.class, () -> SourceCatalogWire.decode(Arrays.copyOf(valid, valid.length + 1)));
    assertThrows(
        IOException.class,
        () -> SourceCatalogWire.decode(new byte[SourceCatalogWire.FRAME_LIMIT + 1]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SourceFacts.Expression("call", "read", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SourceFacts.Expression("select", "field", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SourceFacts.Expression(
                "compare", "EQUAL_TO", List.of(SourceFacts.Expression.name("a"))));
  }
  private Path sourceArchive(String name, Map<String,byte[]> entries) throws Exception {
    Path path=root.resolve(name);
    try(var output=new java.util.zip.ZipOutputStream(Files.newOutputStream(path))) {
      for(var entry:new TreeMap<>(entries).entrySet()) {
        output.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
        output.write(entry.getValue());output.closeEntry();
      }
    }
    return path;
  }

  @Test
  void archivePreparationSelectsOnlyExportedSupportedBasePackagesAndBindsActualBytes() throws Exception {
    var bytes=java.nio.charset.StandardCharsets.UTF_8;
    var selected="package java.nio; /** Selected observations. */ public class CatalogFixture {/** Retained field observations. */ public static final int LIMIT=3; /** Opens a value. */public int open(int value){return value;}}".getBytes(bytes);
    var archive=sourceArchive("fixture.zip",Map.of(
        "java.base/java/nio/CatalogFixture.java",selected,
        "java.base/java/nio/package-info.java",new byte[]{(byte)255},
        "java.base/module-info.java",new byte[]{(byte)255},
        "java.base/java/time/Excluded.java",new byte[]{(byte)255},
        "java.base/jdk/internal/Excluded.java",new byte[]{(byte)255},
        "java.sql/java/sql/Excluded.java",new byte[]{(byte)255},
        "java.base/java/nio/readme.txt",new byte[]{(byte)255}));
    Path license=root.resolve("LICENSE");Files.writeString(license,"explicit synthetic fixture license");
    var catalog=SourceCatalogBuilder.prepareArchive(archive,license,root.resolve("archive-worker"));
    assertEquals(Set.of("java.nio.CatalogFixture"),catalog.types());
    assertEquals(Digests.sha256(archive),catalog.sourceHash());
    assertEquals(SourceParserClient.bundleIdentity(),catalog.workerHash());
    assertEquals(System.getProperty("java.runtime.version"),catalog.jdk());
    assertEquals(Files.readString(license),catalog.license());
    assertEquals("int",catalog.fieldType("java.nio.CatalogFixture","LIMIT"));
    assertTrue(catalog.field("java.nio.CatalogFixture","LIMIT").orElseThrow().terms().containsAll(SourceApiCatalog.terms("Retained field observations")));
    assertTrue(catalog.field("java.nio.CatalogFixture","absent").isEmpty());
    assertTrue(catalog.unique("java.nio.CatalogFixture","open",List.of("int")).isPresent());
    assertEquals(catalog.identity(),SourceCatalogBuilder.prepareArchive(archive,license,root.resolve("archive-worker-repeat")).identity());
  }

  @Test
  void archiveMalformedSelectedSourceMissingArchiveAndLicenseOverflowFailWithoutCatalog() throws Exception {
    Path license=root.resolve("small-license");Files.writeString(license,"fixture");
    assertThrows(IOException.class,()->SourceCatalogBuilder.prepareArchive(root.resolve("absent.zip"),license,root.resolve("missing-worker")));
    var malformed=sourceArchive("bad-utf8.zip",Map.of("java.base/java/nio/Bad.java",new byte[]{(byte)255}));
    assertThrows(IOException.class,()->SourceCatalogBuilder.prepareArchive(malformed,license,root.resolve("utf8-worker")));
    var oversize=sourceArchive("oversize.zip",Map.of("java.base/java/nio/Large.java",new byte[2_097_153]));
    assertThrows(IOException.class,()->SourceCatalogBuilder.prepareArchive(oversize,license,root.resolve("oversize-worker")));
    var empty=sourceArchive("empty.zip",Map.of());
    var largeLicense=root.resolve("large-license");Files.write(largeLicense,new byte[131073]);
    assertThrows(IOException.class,()->SourceCatalogBuilder.prepareArchive(empty,largeLicense,root.resolve("legal-worker")));
    assertThrows(IOException.class,()->SourceCatalogBuilder.prepareArchive(empty,root.resolve("absent-license"),root.resolve("no-legal-worker")));
  }

}
