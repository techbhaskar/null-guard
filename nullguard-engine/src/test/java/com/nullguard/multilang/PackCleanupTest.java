package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackCleanupTest {
  @TempDir Path root;

  Path directory() throws IOException {
    Path directory = Files.createDirectories(root.resolve("pack/nested"));
    Files.writeString(directory.resolve("stdout.json"), "{}");
    return root.resolve("pack");
  }

  @Test
  void nestedOutputTreeIsRemovedAfterEnumerationHandlesClose() throws Exception {
    Path directory = directory();
    PackManager.deleteTree(directory);
    assertFalse(Files.exists(directory));
    PackManager.deleteTree(directory);
  }

  @Test
  void temporaryFileLockDoesNotDiscardSuccessfulCleanup() throws Exception {
    Path directory = directory();
    var locked = new AtomicBoolean(true);
    PackManager.deleteTree(directory, true, path -> {
      if (locked.getAndSet(false)) throw new AccessDeniedException(path.toString());
      Files.deleteIfExists(path);
    });
    assertFalse(Files.exists(directory));
  }

  @Test
  void persistentLockRemainsAnErrorAfterBoundedRetries() throws Exception {
    Path directory = directory();
    var failure = new AccessDeniedException("still locked");
    long start = System.nanoTime();
    assertSame(failure, assertThrows(IOException.class,
        () -> PackManager.deleteTree(directory, true, path -> { throw failure; })));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
    assertTrue(Files.exists(directory));
  }

  @Test
  void nonRetryableDeletionErrorsArePreserved() throws Exception {
    Path directory = directory();
    var failure = new AccessDeniedException("not retryable");
    assertSame(failure, assertThrows(IOException.class,
        () -> PackManager.deleteTree(directory, false, path -> { throw failure; })));
  }

  @Test
  void interruptionStopsRetriesAndPreservesInterruptStatus() throws Exception {
    Path directory = directory();
    Thread.currentThread().interrupt();
    try {
      var failure = assertThrows(IOException.class,
          () -> PackManager.deleteTree(directory, true,
              path -> { throw new AccessDeniedException(path.toString()); }));
      assertInstanceOf(InterruptedException.class, failure.getCause());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
