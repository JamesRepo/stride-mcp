package com.hammez.stride.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class SingleFlightTest {

    private static final int WAITING_CALLERS = 5;

    private final SingleFlight<String> flight = new SingleFlight<>();
    private final AtomicInteger runs = new AtomicInteger();
    private final CountDownLatch finish = new CountDownLatch(1);

    @Test
    void callersDuringARunGetItsResult() throws Exception {
        List<CompletableFuture<String>> results = whileRunning(() -> "result " + runs.get());

        for (CompletableFuture<String> result : results) {
            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("result 1");
        }
        assertThat(runs).hasValue(1);
    }

    @Test
    void callersDuringARunGetItsExceptionAndDontRunItAgain() throws Exception {
        var failure = new IllegalStateException("rate limited");
        List<CompletableFuture<String>> results = whileRunning(() -> {
            throw failure;
        });

        for (CompletableFuture<String> result : results) {
            assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS)).cause().isSameAs(failure);
        }
        assertThat(runs).hasValue(1);
    }

    @Test
    void nothingIsKeptOnceARunHasFinished() {
        assertThatThrownBy(() -> flight.run(() -> {
            runs.incrementAndGet();
            throw new IllegalStateException("first");
        })).hasMessage("first");

        assertThat(flight.run(() -> "run " + runs.incrementAndGet())).isEqualTo("run 2");
        assertThat(flight.run(() -> "run " + runs.incrementAndGet())).isEqualTo("run 3");
    }

    @Test
    void aCheckedExceptionThrownSneakilyStillReachesTheWaitingCallers() throws Exception {
        var failure = new IOException("a bug");
        List<CompletableFuture<String>> results = whileRunning(() -> SingleFlightTest.<RuntimeException>sneaky(failure));

        // The waiting callers get it inside a CompletionException, which the tools treat as a bug, as they would it.
        for (CompletableFuture<String> result : results) {
            assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS)).rootCause().isSameAs(failure);
        }
        assertThat(runs).hasValue(1);
    }

    /**
     * Runs the action through the flight on one thread, holds it there until {@link #WAITING_CALLERS} more callers
     * are waiting on the flight, then lets it finish. The first result is the runner's.
     */
    private List<CompletableFuture<String>> whileRunning(Supplier<String> action) throws Exception {
        var results = new ArrayList<CompletableFuture<String>>();
        var started = new CountDownLatch(1);
        results.add(call(() -> {
            runs.incrementAndGet();
            started.countDown();
            await(finish);
            return action.get();
        }));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        var waiting = new ArrayList<Thread>();
        for (int i = 0; i < WAITING_CALLERS; i++) {
            var result = new CompletableFuture<String>();
            waiting.add(Thread.ofPlatform().start(() -> complete(result, () -> {
                runs.incrementAndGet();
                return "a second run";
            })));
            results.add(result);
        }
        // Parked in the flight, waiting for the first run.
        Instant giveUp = Instant.now().plus(Duration.ofSeconds(10));
        while (!waiting.stream().allMatch(thread -> thread.getState() == Thread.State.WAITING)) {
            assertThat(Instant.now()).as("callers waiting on the flight").isBefore(giveUp);
            Thread.sleep(5);
        }
        finish.countDown();
        return results;
    }

    private CompletableFuture<String> call(Supplier<String> action) {
        var result = new CompletableFuture<String>();
        Thread.ofPlatform().start(() -> complete(result, action));
        return result;
    }

    private void complete(CompletableFuture<String> result, Supplier<String> action) {
        try {
            result.complete(flight.run(action));
        } catch (Throwable e) {
            result.completeExceptionally(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Throws any exception, checked or not, as the compiler can't see. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> String sneaky(Throwable e) throws T {
        throw (T) e;
    }
}
