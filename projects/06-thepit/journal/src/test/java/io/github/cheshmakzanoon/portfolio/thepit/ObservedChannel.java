package io.github.cheshmakzanoon.portfolio.thepit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.*;

/** Real-file adapter that observes force/close and permits deterministic partial I/O and faults. */
final class ObservedChannel extends FileChannel {
  final FileChannel delegate;
  int forces, closes, releases;
  int chunk = Integer.MAX_VALUE;
  boolean noLock;
  IOException lockFailure, releaseFailure, closeFailure, forceFailure;

  ObservedChannel(FileChannel delegate) {
    this.delegate = delegate;
  }

  @Override
  public void force(boolean metadata) throws IOException {
    forces++;
    delegate.force(metadata);
    if (forceFailure != null) throw forceFailure;
  }

  @Override
  public FileLock tryLock(long position, long size, boolean shared) throws IOException {
    if (lockFailure != null) throw lockFailure;
    if (noLock) return null;
    FileLock acquired = delegate.tryLock(position, size, shared);
    if (acquired == null) return null;
    return new FileLock(this, position, size, shared) {
      @Override
      public boolean isValid() {
        return acquired.isValid();
      }

      @Override
      public void release() throws IOException {
        releases++;
        acquired.release();
        if (releaseFailure != null) throw releaseFailure;
      }
    };
  }

  @Override
  protected void implCloseChannel() throws IOException {
    closes++;
    delegate.close();
    if (closeFailure != null) throw closeFailure;
  }

  @Override
  public int write(ByteBuffer buffer) throws IOException {
    int limit = buffer.limit();
    buffer.limit(Math.min(limit, buffer.position() + Math.min(chunk, buffer.remaining())));
    try {
      return delegate.write(buffer);
    } finally {
      buffer.limit(limit);
    }
  }

  @Override
  public int read(ByteBuffer buffer, long position) throws IOException {
    int limit = buffer.limit();
    buffer.limit(Math.min(limit, buffer.position() + Math.min(chunk, buffer.remaining())));
    try {
      return delegate.read(buffer, position);
    } finally {
      buffer.limit(limit);
    }
  }

  @Override
  public int read(ByteBuffer buffer) throws IOException {
    return delegate.read(buffer);
  }

  @Override
  public long read(ByteBuffer[] buffers, int offset, int length) throws IOException {
    return delegate.read(buffers, offset, length);
  }

  @Override
  public long write(ByteBuffer[] buffers, int offset, int length) throws IOException {
    return delegate.write(buffers, offset, length);
  }

  @Override
  public int write(ByteBuffer buffer, long position) throws IOException {
    return delegate.write(buffer, position);
  }

  @Override
  public long position() throws IOException {
    return delegate.position();
  }

  @Override
  public FileChannel position(long position) throws IOException {
    delegate.position(position);
    return this;
  }

  @Override
  public long size() throws IOException {
    return delegate.size();
  }

  @Override
  public FileChannel truncate(long size) throws IOException {
    delegate.truncate(size);
    return this;
  }

  @Override
  public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
    return delegate.transferTo(position, count, target);
  }

  @Override
  public long transferFrom(ReadableByteChannel source, long position, long count)
      throws IOException {
    return delegate.transferFrom(source, position, count);
  }

  @Override
  public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
    return delegate.map(mode, position, size);
  }

  @Override
  public FileLock lock(long position, long size, boolean shared) throws IOException {
    return delegate.lock(position, size, shared);
  }
}
