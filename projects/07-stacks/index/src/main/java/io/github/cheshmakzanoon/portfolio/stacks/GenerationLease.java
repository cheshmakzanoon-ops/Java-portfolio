package io.github.cheshmakzanoon.portfolio.stacks;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;

public final class GenerationLease implements AutoCloseable {
  private final String id;
  private final DirectoryReader reader;
  private final IndexSearcher searcher;
  private final Runnable release;
  private final AtomicBoolean closed = new AtomicBoolean();

  GenerationLease(String id, DirectoryReader reader, Runnable release) {
    this.id = id;
    this.reader = reader;
    this.searcher = new IndexSearcher(reader);
    this.release = release;
  }

  public String id() {
    return id;
  }

  public IndexSearcher searcher() {
    if (closed.get()) throw new IllegalStateException("closed lease");
    return searcher;
  }

  public DirectoryReader reader() {
    if (closed.get()) throw new IllegalStateException("closed lease");
    return reader;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) release.run();
  }
}
