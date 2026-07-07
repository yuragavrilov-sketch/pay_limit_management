package ru.copperside.paylimits.management.common.invariant.port;

import java.util.function.Supplier;

/**
 * Spring-free application port that runs a unit of work inside a single database transaction.
 *
 * <p>The limit-kind non-overlap invariant relies on {@code pg_advisory_xact_lock}, which is
 * released at transaction end. For the lock to actually serialize concurrent changes, the
 * enforcement sequence <em>advisory lock &rarr; conflict-check queries &rarr; the subsequent
 * write</em> must all execute in ONE transaction. Application services stay free of Spring's
 * {@code @Transactional}; they express that boundary by wrapping the sequence in
 * {@link #run(Supplier)}.
 */
public interface TransactionRunner {

    <T> T run(Supplier<T> work);

    default void run(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }
}
