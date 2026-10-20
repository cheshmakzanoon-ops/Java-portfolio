package io.github.cheshmakzanoon.portfolio.indexforge;

import java.io.*;
import java.net.URI;
import java.util.*;
import javax.tools.*;

/** Parse-only compiler I/O boundary shared by structural and JDK-catalog parsing. */
final class SourceCompilerBoundary {
  private SourceCompilerBoundary() {}

  static final class MemorySource extends SimpleJavaFileObject {
    private final String source;

    MemorySource(String source) {
      super(URI.create("string:///Opaque.java"), Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignored) {
      return source;
    }
  }

  static final class DeniedFiles extends ForwardingJavaFileManager<StandardJavaFileManager> {
    DeniedFiles(StandardJavaFileManager manager) {
      super(manager);
    }

    @Override
    public boolean hasLocation(Location location) {
      return false;
    }

    @Override
    public ClassLoader getClassLoader(Location location) {
      return null;
    }

    @Override
    public Iterable<JavaFileObject> list(
        Location l, String p, Set<JavaFileObject.Kind> k, boolean recurse) {
      return List.of();
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location l, String n, JavaFileObject.Kind k)
        throws IOException {
      throw new IOException("external source lookup denied");
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
        Location l, String n, JavaFileObject.Kind k, FileObject sibling) throws IOException {
      throw new IOException("source output denied");
    }

    @Override
    public FileObject getFileForInput(Location l, String p, String n) throws IOException {
      throw new IOException("external file lookup denied");
    }

    @Override
    public FileObject getFileForOutput(Location l, String p, String n, FileObject sibling)
        throws IOException {
      throw new IOException("file output denied");
    }

    @Override
    public Location getLocationForModule(Location l, String m) {
      return null;
    }

    @Override
    public Location getLocationForModule(Location l, JavaFileObject f) {
      return null;
    }

    @Override
    public Iterable<Set<Location>> listLocationsForModules(Location l) {
      return List.of();
    }

    @Override
    public String inferModuleName(Location l) {
      return null;
    }
  }
}
