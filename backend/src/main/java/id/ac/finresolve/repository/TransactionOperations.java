package id.ac.finresolve.repository;

import java.util.function.Supplier;

/**
 * Transaction boundary contract used by services.
 *
 * <p>Both callbacks join an existing transaction through Spring's REQUIRED propagation. Callers
 * receive materialized values only, never a live result set or database handle.
 */
public interface TransactionOperations {

    <T> T read(Supplier<T> callback);

    <T> T write(Supplier<T> callback);
}
