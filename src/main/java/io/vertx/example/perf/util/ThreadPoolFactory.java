package io.vertx.example.perf.util;

import io.vertx.core.Vertx;
import io.vertx.example.perf.config.PerformanceToolConfig;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Factory for creating and configuring thread pools.
 * Provides methods to create optimized thread pools for load testing.
 * Manages thread pool lifecycle based on active tests.
 * Enforces single test execution at a time.
 */
public class ThreadPoolFactory {
    
    // Singleton instance
    private static volatile ThreadPoolFactory instance;
    
    // The managed thread pool
    private volatile ThreadPoolExecutor threadPool;
    
    // Track current active test
    private volatile String currentTestId;
    private final AtomicInteger threadCount = new AtomicInteger(0);
    
    /**
     * Private constructor for singleton pattern
     */
    private ThreadPoolFactory() {
        // Private constructor
    }
    
    /**
     * Gets the singleton instance
     * 
     * @return ThreadPoolFactory instance
     */
    public static ThreadPoolFactory getInstance() {
        if (instance == null) {
            synchronized (ThreadPoolFactory.class) {
                if (instance == null) {
                    instance = new ThreadPoolFactory();
                }
            }
        }
        return instance;
    }
    
    /**
     * Checks if a test can be started.
     * Only one test can run at a time.
     * 
     * @return true if no test is currently running
     */
    public synchronized boolean canStartTest() {
        return currentTestId == null;
    }
    
    /**
     * Gets or creates a thread pool for test execution.
     * Only allows one test to run at a time.
     * 
     * @param testId ID of the test requesting the thread pool
     * @param totalThreads Total number of threads the test will use
     * @return ExecutorService instance
     * @throws IllegalStateException if another test is already running
     */
    public synchronized ExecutorService getThreadPool(String testId, int totalThreads) {
        if (currentTestId != null) {
            throw new IllegalStateException("Cannot start test. Test " + currentTestId + " is currently running.");
        }
        
        // Initialize thread pool if needed
        if (threadPool == null || threadPool.isShutdown()) {
            threadPool = createThreadPool();
            System.out.println("Created new thread pool");
        }
        
        // Track this test
        currentTestId = testId;
        threadCount.set(totalThreads);
        System.out.println("Test " + testId + " registered with " + totalThreads + " threads");
        
        return threadPool;
    }
    
    /**
     * Gets the ID of the currently running test, if any.
     * 
     * @return Current test ID or null if no test is running
     */
    public String getCurrentTestId() {
        return currentTestId;
    }
    
    /**
     * Notifies that a thread from the current test has completed.
     * When all threads complete, shuts down the pool.
     * 
     * @param testId ID of the test
     * @throws IllegalStateException if testId doesn't match current test
     */
    public synchronized void notifyThreadComplete(String testId) {
        if (!testId.equals(currentTestId)) {
            throw new IllegalStateException("Test ID mismatch. Expected " + currentTestId + " but got " + testId);
        }
        
        if (threadCount.decrementAndGet() <= 0) {
            // All threads for this test are done
            System.out.println("Test " + testId + " completed all threads");
            
            // Clean up
            currentTestId = null;
            if (threadPool != null && !threadPool.isShutdown()) {
                shutdownThreadPool(threadPool);
                threadPool = null;
                System.out.println("Thread pool shut down - test complete");
            }
        }
    }
    
    /**
     * Creates a configured thread pool for load test execution.
     * Uses a ThreadPoolExecutor with a bounded queue and caller-runs policy.
     * 
     * @return ThreadPoolExecutor instance
     */
    private ThreadPoolExecutor createThreadPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            PerformanceToolConfig.CORE_POOL_SIZE,
            PerformanceToolConfig.MAX_POOL_SIZE,
            PerformanceToolConfig.KEEP_ALIVE_TIME,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(PerformanceToolConfig.QUEUE_CAPACITY),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // Allow core threads to timeout to prevent resource waste
        pool.allowCoreThreadTimeOut(true);
        
        return pool;
    }
    
    /**
     * Safely shuts down a thread pool.
     * Attempts graceful shutdown first, then forces shutdown if necessary.
     * 
     * @param pool The thread pool to shut down
     */
    private void shutdownThreadPool(ThreadPoolExecutor pool) {
        // Try graceful shutdown first
        pool.shutdown();
        try {
            // Wait a short time for existing tasks to terminate
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                // Force shutdown if still running
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            // Re-interrupt current thread
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Sets up monitoring for the thread pool.
     * Periodically checks pool utilization and logs warnings if high.
     * 
     * @param vertx Vertx instance for scheduling
     * @return Timer ID for the monitoring task
     */
    public long setupThreadPoolMonitoring(Vertx vertx) {
        if (threadPool == null) {
            return -1;
        }
        
        return vertx.setPeriodic(PerformanceToolConfig.THREAD_MONITOR_INTERVAL, id -> {
            ThreadPoolExecutor pool = this.threadPool;
            if (pool != null && !pool.isShutdown()) {
                int queueSize = pool.getQueue().size();
                int activeCount = pool.getActiveCount();
                if (queueSize > PerformanceToolConfig.THREAD_QUEUE_WARNING_THRESHOLD || 
                    activeCount > PerformanceToolConfig.MAX_POOL_SIZE - 2) {
                    System.out.println("Thread pool utilization high - Queue: " + queueSize + 
                        ", Active threads: " + activeCount + 
                        ", Current test: " + currentTestId);
                }
            }
        });
    }
    
    /**
     * Gets the current active test count (0 or 1)
     * 
     * @return 1 if a test is running, 0 otherwise
     */
    public int getActiveTestCount() {
        return currentTestId != null ? 1 : 0;
    }
    
    /**
     * Gets the set of active test IDs
     * 
     * @return Set of active test IDs
     */
    public Set<String> getActiveTestIds() {
        return activeTests.keySet();
    }
} 