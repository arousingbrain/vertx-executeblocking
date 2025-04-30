package io.vertx.example.perf.util;

import io.vertx.core.Vertx;
import io.vertx.example.perf.config.PerformanceToolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating and configuring thread pools.
 * Provides methods to create optimized thread pools for load testing.
 */
public class ThreadPoolFactory {
    
    /**
     * Creates a configured thread pool for load test execution.
     * Uses a ThreadPoolExecutor with a bounded queue and caller-runs policy.
     * 
     * @return ExecutorService instance
     */
    public static ExecutorService createThreadPool() {
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
            PerformanceToolConfig.CORE_POOL_SIZE,
            PerformanceToolConfig.MAX_POOL_SIZE,
            PerformanceToolConfig.KEEP_ALIVE_TIME,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(PerformanceToolConfig.QUEUE_CAPACITY),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // Allow core threads to timeout to prevent resource waste
        threadPool.allowCoreThreadTimeOut(true);
        
        return threadPool;
    }
    
    /**
     * Safely shuts down a thread pool.
     * Attempts graceful shutdown first, then forces shutdown if necessary.
     * 
     * @param executorService The thread pool to shut down
     */
    public static void shutdownThreadPool(ExecutorService executorService) {
        if (executorService != null) {
            // Try graceful shutdown first
            executorService.shutdown();
            try {
                // Wait a short time for existing tasks to terminate
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    // Force shutdown if still running
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                // Re-interrupt current thread
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Sets up monitoring for a thread pool.
     * Periodically checks pool utilization and logs warnings if high.
     * 
     * @param vertx Vertx instance for scheduling
     * @param threadPool Thread pool to monitor
     * @return Timer ID for the monitoring task
     */
    public static long setupThreadPoolMonitoring(Vertx vertx, ThreadPoolExecutor threadPool) {
        return vertx.setPeriodic(PerformanceToolConfig.THREAD_MONITOR_INTERVAL, id -> {
            int queueSize = threadPool.getQueue().size();
            int activeCount = threadPool.getActiveCount();
            if (queueSize > PerformanceToolConfig.THREAD_QUEUE_WARNING_THRESHOLD || 
                activeCount > PerformanceToolConfig.MAX_POOL_SIZE - 2) {
                System.out.println("Thread pool utilization high - Queue: " + queueSize + 
                    ", Active threads: " + activeCount);
            }
        });
    }
} 