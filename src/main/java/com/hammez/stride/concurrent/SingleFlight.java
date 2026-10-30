package com.hammez.stride.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Runs an action once for all the callers that ask while it is running. The first caller runs it; the others wait,
 * then get the same result, or the same exception. Once it has finished, nothing is kept: the next caller runs it
 * again. So a failure reaches Garmin once, not once per waiting caller, and is never remembered.
 * <p>
 * For MCP tools, which the server runs at the same time. Shared by all layers, like {@code files}. Don't call
 * {@link #run} from inside its own action: it would wait for itself.
 */
public final class SingleFlight<T> {

    private final Object lock = new Object();
    private CompletableFuture<T> running;

    public T run(Supplier<T> action) {
        CompletableFuture<T> attempt;
        boolean mine = false;
        synchronized (lock) {
            if (running == null) {
                running = new CompletableFuture<>();
                mine = true;
            }
            attempt = running;
        }
        if (!mine) {
            return waitFor(attempt);
        }
        try {
            T result = action.get();
            attempt.complete(result);
            return result;
        } catch (Throwable e) {
            // Any exception, even a checked one thrown sneakily, so the waiting callers never wait forever.
            attempt.completeExceptionally(e);
            throw e;
        } finally {
            synchronized (lock) {
                running = null;
            }
        }
    }

    private static <T> T waitFor(CompletableFuture<T> attempt) {
        try {
            return attempt.join();
        } catch (CompletionException e) {
            // The action's own exception, as its first caller got it.
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw e;
        }
    }
}
