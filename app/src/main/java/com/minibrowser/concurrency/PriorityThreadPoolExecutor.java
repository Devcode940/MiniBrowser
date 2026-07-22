package com.minibrowser.concurrency;

import androidx.annotation.NonNull;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PriorityThreadPoolExecutor extends ThreadPoolExecutor {

    public PriorityThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, ThreadFactory threadFactory) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, new PriorityBlockingQueue<>(), threadFactory);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        int priority = 0;
        if (runnable instanceof Prioritized) {
            priority = ((Prioritized) runnable).getPriority();
        }
        return new PriorityFutureTask<>(runnable, value, priority);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        int priority = 0;
        if (callable instanceof Prioritized) {
            priority = ((Prioritized) callable).getPriority();
        }
        return new PriorityFutureTask<>(callable, priority);
    }

    public interface Prioritized {
        int getPriority();
    }

    public static class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {
        private final int priority;
        private final Runnable runnable;

        public PriorityRunnable(int priority, Runnable runnable) {
            this.priority = priority;
            this.runnable = runnable;
        }

        @Override
        public void run() {
            runnable.run();
        }

        @Override
        public int compareTo(PriorityRunnable other) {
            return Integer.compare(other.priority, this.priority); // Highest priority runs first
        }
    }

    private static class PriorityFutureTask<V> extends FutureTask<V> implements Comparable<PriorityFutureTask<V>> {
        private final int priority;

        public PriorityFutureTask(Runnable runnable, V result, int priority) {
            super(runnable, result);
            this.priority = priority;
        }

        public PriorityFutureTask(Callable<V> callable, int priority) {
            super(callable);
            this.priority = priority;
        }

        @Override
        public int compareTo(@NonNull PriorityFutureTask<V> o) {
            return Integer.compare(o.priority, this.priority);
        }
    }
}
