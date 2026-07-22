package com.minibrowser.concurrency

import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RunnableFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PriorityThreadPoolExecutor(
    corePoolSize: Int,
    maximumPoolSize: Int,
    keepAliveTime: Long,
    unit: TimeUnit,
    threadFactory: ThreadFactory
) : ThreadPoolExecutor(
    corePoolSize,
    maximumPoolSize,
    keepAliveTime,
    unit,
    PriorityBlockingQueue(),
    threadFactory
) {

    override fun <T> newTaskFor(runnable: Runnable, value: T): RunnableFuture<T> {
        var priority = 0
        if (runnable is Prioritized) {
            priority = runnable.getPriority()
        }
        return PriorityFutureTask(runnable, value, priority)
    }

    override fun <T> newTaskFor(callable: Callable<T>): RunnableFuture<T> {
        var priority = 0
        if (callable is Prioritized) {
            priority = callable.priority
        }
        return PriorityFutureTask(callable, priority)
    }

    interface Prioritized {
        val priority: Int
            get() = 0

        fun getPriority(): Int = priority
    }

    class PriorityRunnable(
        private val priority: Int,
        private val runnable: Runnable
    ) : Runnable, Comparable<PriorityRunnable> {

        override fun run() {
            runnable.run()
        }

        override fun compareTo(other: PriorityRunnable): Int {
            return other.priority.compareTo(this.priority)
        }
    }

    private class PriorityFutureTask<V> : FutureTask<V>, Comparable<PriorityFutureTask<V>> {
        private val priority: Int

        constructor(runnable: Runnable, result: V, priority: Int) : super(runnable, result) {
            this.priority = priority
        }

        constructor(callable: Callable<V>, priority: Int) : super(callable) {
            this.priority = priority
        }

        override fun compareTo(other: PriorityFutureTask<V>): Int {
            return other.priority.compareTo(this.priority)
        }
    }
}
