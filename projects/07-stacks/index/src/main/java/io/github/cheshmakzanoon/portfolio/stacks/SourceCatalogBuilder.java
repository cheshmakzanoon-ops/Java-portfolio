package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Explicit offline preparation from this pinned JDK's source, never from evaluation fixtures. */
public final class SourceCatalogBuilder {
  private SourceCatalogBuilder() {}

  public static SourceApiCatalog prepare(Path workerDirectory) throws IOException {
    Path home = Path.of(System.getProperty("java.home"));
    return prepareArchive(
        home.resolve("lib/src.zip"), home.resolve("legal/java.base/LICENSE"), workerDirectory);
  }

  // Package-local archive boundary for adversarial fixture tests. The public preparation entry
  // always selects the installed JDK above; no CLI or runtime override can select fixture prose.
  static SourceApiCatalog prepareArchive(Path source, Path license, Path workerDirectory)
      throws IOException {
    if (!Files.isRegularFile(source) || Files.size(source) > 134_217_728)
      throw new IOException("installed JDK source ZIP missing/quota");
    String sourceHash = Digests.sha256(source);
    Set<String> exported = new HashSet<>();
    for (var e : Object.class.getModule().getDescriptor().exports())
      if (!e.isQualified()) exported.add(e.source());
    List<String> selected = new ArrayList<>();
    long total = 0;
    int visited = 0;
    List<SourceCatalogFacts.Unit> units = new ArrayList<>();
    String workerHash;
    try (var archive = new ZipFile(source.toFile());
        var worker = new SourceParserClient(workerDirectory)) {
      var entries = archive.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (++visited > 32768) throw new IOException("JDK source entry quota");
        String name = entry.getName();
        if (entry.isDirectory()
            || !name.startsWith("java.base/")
            || !name.endsWith(".java")
            || name.endsWith("/module-info.java")
            || name.endsWith("/package-info.java")) continue;
        String pkg = name.substring("java.base/".length(), name.lastIndexOf('/')).replace('/', '.');
        if (!exported.contains(pkg) || !roots(pkg)) continue;
        if (entry.getSize() < 0
            || entry.getSize() > 2_097_152
            || (total += entry.getSize()) > 134_217_728)
          throw new IOException("selected API source byte quota");
        selected.add(name);
      }
      Collections.sort(selected);
      long descriptions = 0;
      for (String name : selected) {
        var entry = archive.getEntry(name);
        byte[] bytes;
        try (var input = archive.getInputStream(entry)) {
          bytes = input.readNBytes(2_097_153);
        }
        if (bytes.length > 2_097_152 || bytes.length != entry.getSize())
          throw new IOException("API source expansion quota");
        var crc = new CRC32();
        crc.update(bytes);
        if (crc.getValue() != entry.getCrc()) throw new IOException("JDK API ZIP CRC");
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        var unit = worker.catalog(text);
        descriptions += retainedBytes(unit);
        if (descriptions > 134_217_728) throw new IOException("API preparation working-set quota");
        units.add(unit);
      }
      workerHash = worker.identity();
    }
    byte[] legal;
    try (var input = Files.newInputStream(license)) {
      legal = input.readNBytes(131073);
    }
    if (legal.length > 131072) throw new IOException("JDK license quota");
    return SourceApiCatalog.fromParsed(
        units,
        sourceHash,
        workerHash,
        System.getProperty("java.runtime.version"),
        new String(legal, StandardCharsets.UTF_8));
  }

  // Conservative retained signature/prose accounting, not a claim about JVM total RSS.
  static long retainedBytes(SourceCatalogFacts.Unit unit) {
    long bytes = 3L * unit.packageName().length() + 128;
    for (String imported : unit.imports()) bytes += 3L * imported.length() + 64;
    for (var type : unit.types()) {
      bytes += 3L * (type.name().length() + type.description().length()) + 256;
      for (var member : type.members()) {
        bytes +=
            3L
                    * (member.name().length()
                        + member.returns().length()
                        + member.description().length())
                + 512;
        for (String parameter : member.parameters()) bytes += 3L * parameter.length() + 64;
      }
    }
    return bytes;
  }

  private static boolean roots(String pkg) {
    for (String root : List.of("java.io", "java.lang", "java.nio", "java.security", "java.util"))
      if (pkg.equals(root) || pkg.startsWith(root + ".")) return true;
    return false;
  }
}
