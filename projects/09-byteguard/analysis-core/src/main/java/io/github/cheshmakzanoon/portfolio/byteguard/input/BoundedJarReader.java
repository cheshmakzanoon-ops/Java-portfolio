package io.github.cheshmakzanoon.portfolio.byteguard.input;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Streams owned bytes. No extraction, target classloading or network resolution. */
public final class BoundedJarReader {
  private final InputLimits limits;
  private final int release;
  private int entries;
  private long expanded;
  private final long started = System.nanoTime();

  public BoundedJarReader(InputLimits limits, int release) {
    this.limits = limits;
    this.release = release;
    if (release < 8 || release > 25)
      throw new IllegalArgumentException("target release must be 8..25");
  }

  public InputManifest read(List<Path> inputs, List<Path> classpath) throws IOException {
    ClasspathCatalog result = new ClasspathCatalog();
    List<String> diagnostics = new ArrayList<>();
    for (Path p : inputs) readPath(p, true, result, diagnostics);
    for (Path p : classpath) readPath(p, false, result, diagnostics);
    return new InputManifest(result.entries(), diagnostics, expanded);
  }

  private void check() throws InputFailure {
    if ((System.nanoTime() - started) / 1_000_000 > limits.deadlineMillis())
      throw new InputFailure("input deadline exceeded");
  }

  private void readPath(Path p, boolean application, ClasspathCatalog out, List<String> diagnostics)
      throws IOException {
    check();
    if (Files.isSymbolicLink(p))
      throw new InputFailure("symbolic input rejected: " + p.getFileName());
    if (!Files.exists(p)) throw new InputFailure("missing explicit input: " + p.getFileName());
    if (Files.isDirectory(p)) {
      try (var paths = Files.walk(p)) {
        for (Iterator<Path> iterator = paths.iterator(); iterator.hasNext(); ) {
          check();
          Path f = iterator.next();
          count();
          if (Files.isSymbolicLink(f)) throw new InputFailure("symbolic entry rejected");
          if (Files.isRegularFile(f)) {
            if (f.toString().endsWith(".class")) {
              try (InputStream in = Files.newInputStream(f)) {
                put(
                    p.relativize(f).toString().replace('\\', '/'),
                    bounded(in, limits.classBytes()),
                    application,
                    out);
              }
            } else if (f.toString().endsWith(".jar"))
              throw new InputFailure("nested archive not supported in class directory");
          }
        }
      }
      return;
    }
    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
      throw new InputFailure("explicit input must be a regular file");
    if (p.toString().endsWith(".class")) {
      count();
      try (InputStream in = Files.newInputStream(p)) {
        byte[] bytes = bounded(in, limits.classBytes());
        put(
            new org.objectweb.asm.ClassReader(bytes).getClassName() + ".class",
            bytes,
            application,
            out);
      } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
        throw new InputFailure("malformed classfile");
      }
      return;
    }
    if (!p.toString().endsWith(".jar") && !p.toString().endsWith(".zip"))
      throw new InputFailure("expected class, directory, JAR or ZIP");
    ArchiveDirectory.validate(p, limits.entries() - entries, this::check);
    Map<String, byte[]> raw = new TreeMap<>();
    Set<String> names = new HashSet<>();
    long archiveStart = expanded;
    try (ZipFile zip = new ZipFile(p.toFile())) {
      Enumeration<? extends ZipEntry> archiveEntries = zip.entries();
      while (archiveEntries.hasMoreElements()) {
        check();
        count();
        ZipEntry e = archiveEntries.nextElement();
        String n = e.getName();
        validateName(n);
        if (!names.add(n)) throw new InputFailure("duplicate archive entry: " + n);
        if (n.endsWith(".jar") || n.endsWith(".zip"))
          throw new InputFailure("nested archives forbidden: " + n);
        byte[] bytes;
        try (InputStream input = zip.getInputStream(e)) {
          bytes = bounded(input, e.isDirectory() ? 0 : limits.classBytes());
        }
        CRC32 crc = new CRC32();
        crc.update(bytes);
        if (e.getSize() != bytes.length || e.getCrc() != crc.getValue())
          throw new InputFailure("archive entry size or checksum mismatch: " + n);
        if (e.isDirectory()) continue;
        long compressed = e.getCompressedSize();
        if (bytes.length > Math.max(1, compressed) * (long) limits.expansionRatio())
          throw new InputFailure("expansion ratio exceeded: " + n);
        if (n.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
          if (raw.putIfAbsent("META-INF/MANIFEST.MF", bytes) != null)
            throw new InputFailure("ambiguous manifest entries");
        } else if (n.endsWith(".class")) raw.put(n, bytes);
      }
    } catch (ZipException | IllegalArgumentException e) {
      throw new InputFailure("corrupt archive: " + e.getMessage());
    }
    if (expanded - archiveStart > Math.max(1, Files.size(p)) * (long) limits.expansionRatio())
      throw new InputFailure("archive expansion ratio exceeded");
    Map<String, byte[]> selected = new MultiReleaseSelector().select(raw, release);
    for (var e : selected.entrySet()) put(e.getKey(), e.getValue(), application, out);
  }

  private void count() throws InputFailure {
    if (++entries > limits.entries()) throw new InputFailure("entry count exceeded");
  }

  private byte[] bounded(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      check();
      expanded += n;
      if (expanded > limits.expandedBytes() || out.size() + n > max)
        throw new InputFailure("expanded byte limit exceeded");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  private void put(String n, byte[] bytes, boolean app, ClasspathCatalog out) throws InputFailure {
    if (bytes.length < 8 || readInt(bytes, 0) != 0xcafebabe)
      throw new InputFailure("malformed classfile: " + n);
    int major = ((bytes[6] & 255) << 8) | (bytes[7] & 255);
    if (major < 45 || major > release + 44)
      throw new InputFailure("unsupported class version " + major + ": " + n);
    try {
      String actual = new org.objectweb.asm.ClassReader(bytes).getClassName() + ".class";
      if (!n.equals(actual))
        throw new InputFailure("class logical name mismatch: " + n + " != " + actual);
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      throw new InputFailure("malformed classfile: " + n);
    }
    out.register(new InputManifest.Entry(n, bytes, sha(bytes), app));
  }

  private static int readInt(byte[] b, int o) {
    return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255);
  }

  public static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void validateName(String n) throws InputFailure {
    if (n.length() > 4096
        || n.isEmpty()
        || n.startsWith("/")
        || n.contains("\\")
        || n.indexOf(0) >= 0
        || n.matches("^[A-Za-z]:.*")
        || Arrays.asList(n.split("/", -1)).contains("..")
        || Arrays.asList(n.split("/", -1)).contains("."))
      throw new InputFailure("unsafe archive path: " + n);
  }
}
