/*
 * Copyright (c) 2020 Warp Studios
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package co.bywarp.foresight;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Foresight is a utility which in some ways
 * mirrors design principles from the Promise
 * concept in JavaScript. It allows for async
 * execution of tasks through supplying a task
 * and then using one of the many compute() or
 * computeResult() methods to retrieve the result.
 *
 * @author Mike M
 */
public class Foresight<T> {

    private Supplier<T> supplier;
    private CompletableFuture<T> task;
    private T result;
    private UUID taskId;
    private boolean fulfilled;
    private boolean timed;
    private boolean blocking;

    public Foresight(Supplier<T> supplier) {
        this.supplier = supplier;
        this.taskId = UUID.randomUUID();
    }

    /**
     * Creates a Foresight object with the provided supplier.
     *
     * @param supplier the supplier to execute
     * @param <T> the expected return type of the supplier
     * @return the newly created Foresight object
     */
    public static <T> Foresight<T> of(Supplier<T> supplier) {
        return new Foresight<>(supplier);
    }

    /**
     * Enables IO-blocking (non-async execution.)
     * @return this object for further chaining
     */
    public Foresight<T> block() {
        this.blocking = true;
        return this;
    }

    /**
     * Enables timing reports in stdout.
     * @return this object for further chaining
     */
    public Foresight<T> timed() {
        this.timed = true;
        return this;
    }

    /**
     * Forcibly cancels this task.
     */
    public void cancel() {
        this.cancel(true);
    }

    /**
     * Cancels this task with an optional
     * parameter to forcibly cancel if
     * still running.
     *
     * @throws IllegalStateException if one of the following
     * conditions is met:
     * <ul>
     *     <li>This is a blocking task</li>
     *     <li>This task has already been fulfilled</li>
     *     <li>The associated future has already been marked as done</li>
     * </ul>
     *
     * @param interrupt whether or not to interrupt the task
     */
    public void cancel(boolean interrupt) {
        if (blocking) {
            throw new IllegalStateException("Cannot cancel blocking Foresight");
        }

        if (this.fulfilled && this.result != null) {
            throw new IllegalStateException("Cannot cancel fulfilled Foresight");
        }

        if (this.task.isDone()) {
            throw new IllegalStateException("Cannot cancel completed Foresight");
        }

        this.task.cancel(interrupt);
        this.fulfilled = true;
        this.result = null;
    }

    /**
     * Computes the result of the supplied task
     * and returns it as inside of a {@link Consumer}.
     * @param withResult what to do when it completes successfully
     */
    public void compute(Consumer<T> withResult) {
        this.compute(withResult, null, null);
    }

    /**
     * Computes the result of the supplied task
     * and returns it inside of a {@link Consumer}
     * with another consumer provided in case the
     * task fails during execution.
     *
     * @param withResult what to do when it completes successfully
     * @param except what to do if it fails during execution
     */
    public void compute(Consumer<T> withResult, Consumer<Throwable> except) {
        this.compute(withResult, except, null);
    }

    /**
     * Computes the result of the supplied task
     * and returns it inside of a {@link Consumer}
     * with another consumer provided in case the
     * task fails during execution, and the fallback
     * result if that happens.
     *
     * @param withResult what to do if it completes successfully
     * @param except what to do if it fails during execution
     * @param orElse what to return if it fails during execution
     */
    public void compute(Consumer<T> withResult, Consumer<Throwable> except, T orElse) {
        if (this.fulfilled && this.result != null) {
            throw new IllegalStateException("Foresight has already been fulfilled");
        }

        long start = System.currentTimeMillis();
        if (blocking) {
            try {
                withResult.accept(supplier.get());
                recordTimings(start);
            } catch (Exception ex) {
                except.accept(ex);
            }
        }

        this.task = CompletableFuture
                .supplyAsync(supplier)
                .exceptionally(err -> {
                    if (except == null) {
                        return null;
                    }

                    except.accept(err);
                    return orElse;
                })
                .thenApply(result -> {
                    this.result = result;
                    this.fulfilled = true;
                    this.recordTimings(start);
                    withResult.accept(result);
                    return result;
                });
    }

    /**
     * Much like {@link Foresight#compute(Consumer)} and related methods,
     * it will compute the result, but instead of wrapping it inside of a consumer,
     * the result is directly returned from this method.
     *
     * @return the result of the supplied task
     */
    public T computeResult() {
        return computeResult(null, null);
    }

    /**
     * Much like the {@link Foresight#compute(Consumer)} and related methods,
     * it will compute the result, but instead of wrapping it inside of a consumer,
     * the result is directly returned from this method, along with a callback in
     * case the task fails during execution.
     *
     * @param except what to do if it fails during execution
     * @return the result of the supplied task
     */
    public T computeResult(Consumer<Throwable> except) {
        return computeResult(except, null);
    }

    /**
     * Much like the {@link Foresight#compute(Consumer)} and related methods,
     * it will compute the result, but instead of wrapping it inside of a consumer,
     * the result is directly returned from this method, along with a fallback object
     * to return in case execution fails in some way.
     *
     * @param orElse what to return if it fails during execution
     * @return the result of the supplied task
     */
    public T computeResult(T orElse) {
        return computeResult(null, orElse);
    }

    /**
     * Much like the {@link Foresight#compute(Consumer)} and related methods,
     * it will compute the result, but instead of wrapping it inside of a consumer,
     * the result is directly returned from this method, along with a callback in
     * case the task fails during execution, and a fallback object to return
     * in case execution fails in some way.
     *
     * @param except what to do if it fails during execution
     * @param orElse what to return if it fails during execution
     * @return the result of the supplied task
     */
    public T computeResult(Consumer<Throwable> except, T orElse) {
        if (this.fulfilled && this.result != null) {
            throw new IllegalStateException("Foresight has already been fulfilled");
        }

        long start = System.currentTimeMillis();
        if (blocking) {
            try {
                this.recordTimings(start);
                return supplier.get();
            } catch (Exception ex) {
                return null;
            }
        }

        this.task = CompletableFuture
                .supplyAsync(supplier)
                .exceptionally(err -> {
                    if (except == null) {
                        return null;
                    }

                    except.accept(err);
                    return orElse;
                })
                .thenApply(result -> {
                    this.result = result;
                    this.fulfilled = true;
                    this.recordTimings(start);
                    return result;
                });

        return task.join();
    }

    private void recordTimings(long start) {
        if (!timed) {
            return;
        }

        System.out.println("[" + taskId + "] took " + (System.currentTimeMillis() - start) + "ms.");
    }

}
