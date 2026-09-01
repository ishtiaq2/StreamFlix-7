package demo;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * Genuinely runnable, zero external dependencies — needs nothing beyond
 * the JDK itself (virtual threads are a built-in java.util.concurrent
 * feature since JDK 21, finalized after a few years as a preview
 * feature). Everything else in this repo needs Spring Boot/DGS/gRPC from
 * Maven Central, which this sandbox can't reach (see ../README.md) — this
 * is the one piece that could actually be compiled and run here, and was.
 *
 * Models the exact shape of problem the video describes Netflix
 * evaluating virtual threads for: a service handling many concurrent,
 * BLOCKING, I/O-bound calls (a downstream HTTP call, a database query —
 * simulated here with Thread.sleep, which is deliberately the worst case
 * for platform threads and the case virtual threads exist to fix).
 *
 * Real, honest framing worth keeping in mind reading this: the video's
 * most interesting point about virtual threads wasn't "they're faster" —
 * it was that Netflix rolled them back after finding real cases where
 * they weren't safe, and only recently started re-evaluating them after
 * JDK 25 fixed the underlying problems. This demo shows the appeal;
 * it doesn't - and can't, in a 5-minute demo - show what actually broke
 * for them at production scale.
 */
public class VirtualThreadsDemo {

  private static final int CONCURRENT_TASKS = 10_000;
  private static final Duration SIMULATED_IO_LATENCY = Duration.ofMillis(50);

  public static void main(String[] args) throws InterruptedException {
    System.out.println("Simulating " + CONCURRENT_TASKS + " concurrent blocking I/O calls, "
        + SIMULATED_IO_LATENCY.toMillis() + "ms each.\n");

    runWithVirtualThreads();
    runWithFixedPlatformThreadPool();
  }

  /** Java 21+: one virtual thread per task. Cheap enough that "one per
   * request" is a genuinely reasonable default, not a resource risk. */
  private static void runWithVirtualThreads() throws InterruptedException {
    Instant start = Instant.now();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = IntStream.range(0, CONCURRENT_TASKS)
          .mapToObj(i -> executor.submit(VirtualThreadsDemo::simulateBlockingIo))
          .toList();
      for (Future<?> f : futures) {
        try {
          f.get();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    Duration elapsed = Duration.between(start, Instant.now());
    System.out.printf("virtual threads:      %5dms total, thread name pattern: %s%n",
        elapsed.toMillis(), sampleThreadName(true));
  }

  /** A realistic bounded platform-thread pool — the kind of size a real
   * service would actually run (not literally 10,000 OS threads, which
   * would be a self-inflicted resource problem on its own). This is the
   * honest baseline: not "platform threads are bad," but "a bounded pool
   * of them serializes this much blocking work, by design." */
  private static void runWithFixedPlatformThreadPool() throws InterruptedException {
    Instant start = Instant.now();
    int poolSize = 200;
    try (ExecutorService executor = Executors.newFixedThreadPool(poolSize)) {
      var futures = IntStream.range(0, CONCURRENT_TASKS)
          .mapToObj(i -> executor.submit(VirtualThreadsDemo::simulateBlockingIo))
          .toList();
      for (Future<?> f : futures) {
        try {
          f.get();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    Duration elapsed = Duration.between(start, Instant.now());
    System.out.printf("platform pool (n=%d): %5dms total, thread name pattern: %s%n",
        poolSize, elapsed.toMillis(), sampleThreadName(false));
  }

  private static void simulateBlockingIo() {
    try {
      Thread.sleep(SIMULATED_IO_LATENCY);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String sampleThreadName(boolean virtual) {
    Thread t = virtual ? Thread.ofVirtual().unstarted(() -> {}) : new Thread();
    return t.toString();
  }
}
