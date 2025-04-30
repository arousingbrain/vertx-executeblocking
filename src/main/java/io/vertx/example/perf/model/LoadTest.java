package io.vertx.example.perf.model;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.example.perf.config.PerformanceToolConfig;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Model class that tracks load test configuration, execution state, and metrics.
 * Maintains counters for active threads, completed iterations, and performance measurements.
 */
public class LoadTest {
    private final String id;                        // Unique identifier for this test
    private final int threads;                      // Number of concurrent users/threads
    private final int rampUpPeriod;                 // Time in seconds to ramp up threads
    private final int loopCount;                    // Number of iterations per thread
    private final long startTime;                   // Test start timestamp
    private final AtomicInteger completedIterations = new AtomicInteger(0); // Counter for completed requests
    private final AtomicInteger activeThreads = new AtomicInteger(0);       // Counter for active threads
    private final AtomicLong totalResponseTime = new AtomicLong(0);         // Sum of all response times
    private final AtomicInteger successCount = new AtomicInteger(0);        // Counter for successful requests
    private final AtomicInteger failureCount = new AtomicInteger(0);        // Counter for failed requests
    private volatile boolean isRunning = true;      // Flag to indicate if test is still running
    private long timerId = -1;                      // Timer ID for the timeout handler
    private final Object requestBody;               // Request body to use for test requests
    private final String targetUrl;                 // Target URL to test
    private final MultiMap headers;                 // Headers to forward to target service

    /**
     * Creates a new load test with the specified configuration.
     *
     * @param id Unique identifier for this test
     * @param threads Number of concurrent threads
     * @param rampUpPeriod Time in seconds to ramp up threads
     * @param loopCount Number of iterations per thread
     * @param requestBody Body content to send with each request
     * @param targetUrl URL of the service to test
     * @param headers Headers to include in requests
     */
    public LoadTest(String id, int threads, int rampUpPeriod, int loopCount, Object requestBody, String targetUrl, MultiMap headers) {
        this.id = id;
        this.threads = threads;
        this.rampUpPeriod = rampUpPeriod;
        this.loopCount = loopCount;
        this.startTime = System.currentTimeMillis();
        this.requestBody = requestBody;
        this.targetUrl = targetUrl;
        this.headers = headers;
    }

    /**
     * Gets current metrics for this load test.
     * Includes configuration details, progress, and performance measurements.
     *
     * @return JsonObject containing test metrics
     */
    public synchronized JsonObject getMetrics() {
        int totalCompleted = completedIterations.get();
        int totalExpected = threads * loopCount;
        long elapsedTimeMs = System.currentTimeMillis() - startTime;
        boolean timeoutReached = elapsedTimeMs >= PerformanceToolConfig.MAX_TEST_DURATION_MS;
        boolean stillRunning = isRunning && totalCompleted < totalExpected && !timeoutReached;
        
        return new JsonObject()
            .put("id", id)
            .put("threads", threads)
            .put("rampUpPeriod", rampUpPeriod)
            .put("loopCount", loopCount)
            .put("startTime", startTime)
            .put("elapsedTimeMs", elapsedTimeMs)
            .put("maxDurationMs", PerformanceToolConfig.MAX_TEST_DURATION_MS)
            .put("completedIterations", totalCompleted)
            .put("totalIterations", totalExpected)
            .put("activeThreads", activeThreads.get())
            .put("successCount", successCount.get())
            .put("failureCount", failureCount.get())
            .put("averageResponseTime", totalCompleted > 0 ? 
                totalResponseTime.get() / totalCompleted : 0)
            .put("completionPercentage", totalExpected > 0 ? 
                (totalCompleted * 100.0) / totalExpected : 0)
            .put("timeoutReached", timeoutReached)
            .put("isRunning", stillRunning)
            .put("targetUrl", targetUrl);
    }

    /**
     * Marks this load test as completed, stopping further iterations.
     */
    public void markCompleted() {
        this.isRunning = false;
    }

    /**
     * Sets the timer ID used for test timeout.
     *
     * @param timerId Vert.x timer ID
     */
    public void setTimerId(long timerId) {
        this.timerId = timerId;
    }

    /**
     * Gets the timer ID for test timeout.
     *
     * @return Timer ID
     */
    public long getTimerId() {
        return timerId;
    }
    
    /**
     * Gets the request body to be sent with each request.
     *
     * @return Request body (JSON or String)
     */
    public Object getRequestBody() {
        return requestBody;
    }
    
    /**
     * Gets the target URL for this test.
     *
     * @return Target URL
     */
    public String getTargetUrl() {
        return targetUrl;
    }
    
    /**
     * Gets the headers to be included in each request.
     *
     * @return Headers as MultiMap
     */
    public MultiMap getHeaders() {
        return headers;
    }
    
    /**
     * Gets the unique identifier for this test.
     * 
     * @return Test ID
     */
    public String getId() {
        return id;
    }
    
    /**
     * Gets the number of threads for this test.
     * 
     * @return Thread count
     */
    public int getThreads() {
        return threads;
    }
    
    /**
     * Gets the ramp-up period in seconds.
     * 
     * @return Ramp-up period
     */
    public int getRampUpPeriod() {
        return rampUpPeriod;
    }
    
    /**
     * Gets the number of iterations per thread.
     * 
     * @return Loop count
     */
    public int getLoopCount() {
        return loopCount;
    }
    
    /**
     * Checks if the test is still running.
     * 
     * @return true if the test is running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Increments the active thread counter.
     * 
     * @return New count of active threads
     */
    public int incrementActiveThreads() {
        return activeThreads.incrementAndGet();
    }
    
    /**
     * Decrements the active thread counter.
     * 
     * @return New count of active threads
     */
    public int decrementActiveThreads() {
        return activeThreads.decrementAndGet();
    }
    
    /**
     * Increments the completed iterations counter.
     */
    public void incrementCompletedIterations() {
        completedIterations.incrementAndGet();
    }
    
    /**
     * Increments the success counter.
     */
    public void incrementSuccessCount() {
        successCount.incrementAndGet();
    }
    
    /**
     * Increments the failure counter.
     */
    public void incrementFailureCount() {
        failureCount.incrementAndGet();
    }
    
    /**
     * Adds to the total response time.
     * 
     * @param responseTime Time in milliseconds
     */
    public void addResponseTime(long responseTime) {
        totalResponseTime.addAndGet(responseTime);
    }
    
    /**
     * Gets the test start time.
     * 
     * @return Start time in milliseconds
     */
    public long getStartTime() {
        return startTime;
    }
} 