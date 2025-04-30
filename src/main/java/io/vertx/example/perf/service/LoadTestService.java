package io.vertx.example.perf.service;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.example.perf.config.PerformanceToolConfig;
import io.vertx.example.perf.model.LoadTest;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Service responsible for managing and executing load tests.
 * Handles test creation, execution, and lifecycle management.
 */
public class LoadTestService {
    // Maps test IDs to their corresponding test objects
    private final ConcurrentHashMap<String, LoadTest> activeTests = new ConcurrentHashMap<>();
    
    // Services and resources
    private final Vertx vertx;
    private final ExecutorService executorService;
    private final HttpClientService httpClientService;
    
    /**
     * Creates a new load test service.
     * 
     * @param vertx Vert.x instance for async operations
     * @param executorService Thread pool for executing load tests
     * @param httpClientService Service for HTTP requests
     */
    public LoadTestService(Vertx vertx, ExecutorService executorService, HttpClientService httpClientService) {
        this.vertx = vertx;
        this.executorService = executorService;
        this.httpClientService = httpClientService;
    }
    
    /**
     * Creates and starts a new load test with the given parameters.
     * 
     * @param loadTest The load test to execute
     * @return The ID of the created test
     */
    public String startLoadTest(LoadTest loadTest) {
        // Register the test
        String testId = loadTest.getId();
        activeTests.put(testId, loadTest);
        
        // Set a timeout to automatically stop the test after MAX_TEST_DURATION_MS
        long timerId = vertx.setTimer(PerformanceToolConfig.MAX_TEST_DURATION_MS, id -> {
            stopTest(loadTest);
        });
        loadTest.setTimerId(timerId);
        
        // Start the load test asynchronously
        executeLoadTest(loadTest);
        
        return testId;
    }
    
    /**
     * Executes a load test with the configured parameters.
     * Handles thread creation and ramp-up period.
     * 
     * @param loadTest Load test configuration and metrics
     */
    private void executeLoadTest(LoadTest loadTest) {
        // Calculate delay between thread starts if ramp-up is enabled
        long delayBetweenThreadsMs = loadTest.getRampUpPeriod() > 0 && loadTest.getThreads() > 1 ?
            (loadTest.getRampUpPeriod() * 1000) / (loadTest.getThreads() - 1) : 0;

        // Launch each thread with appropriate delay for ramp-up
        for (int i = 0; i < loadTest.getThreads(); i++) {
            final int threadIndex = i;
            // Schedule thread start based on its position in the ramp-up sequence
            vertx.setTimer(i * delayBetweenThreadsMs, timerId -> {
                executeThread(loadTest, threadIndex);
            });
        }
    }
    
    /**
     * Executes a single thread within a load test.
     * Manages iterations, metrics collection, and thread lifecycle.
     * 
     * @param loadTest Load test configuration and metrics
     * @param threadIndex Index of this thread within the test
     */
    private void executeThread(LoadTest loadTest, int threadIndex) {
        // Increment active thread counter as this thread starts
        loadTest.incrementActiveThreads();
        
        // Submit work to thread pool instead of blocking Vert.x event loop
        executorService.submit(() -> {
            try {
                // Perform all iterations for this thread
                for (int i = 0; i < loadTest.getLoopCount(); i++) {
                    // Exit early if test was stopped or timeout reached
                    if (!loadTest.isRunning()) {
                        break;
                    }
                    
                    // Check if max duration reached
                    if (System.currentTimeMillis() - loadTest.getStartTime() >= PerformanceToolConfig.MAX_TEST_DURATION_MS) {
                        break;
                    }
                    
                    // Track request execution time
                    long startTime = System.currentTimeMillis();
                    
                    // Execute the HTTP request and update success/failure metrics
                    try {
                        httpClientService.executeRequest(loadTest, threadIndex, i);
                        loadTest.incrementSuccessCount();
                    } catch (Exception e) {
                        loadTest.incrementFailureCount();
                        System.err.println("Request failed: " + e.getMessage());
                    }
                    
                    // Update timing metrics
                    long elapsed = System.currentTimeMillis() - startTime;
                    loadTest.addResponseTime(elapsed);
                    loadTest.incrementCompletedIterations();
                }
            } catch (Exception e) {
                System.err.println("Thread " + threadIndex + " failed: " + e.getMessage());
            } finally {
                // Decrement active thread counter when this thread completes
                int remainingThreads = loadTest.decrementActiveThreads();
                
                // If this was the last active thread, mark the test as completed
                if (remainingThreads == 0) {
                    // Use Vert.x event loop for thread-safe state update
                    vertx.runOnContext(v -> loadTest.markCompleted());
                }
            }
        });
    }
    
    /**
     * Stops a running load test.
     * 
     * @param loadTest The load test to stop
     */
    public void stopTest(LoadTest loadTest) {
        if (loadTest.isRunning()) {
            System.out.println("Stopping test " + loadTest.getId() + " due to timeout (" + 
                PerformanceToolConfig.MAX_TEST_DURATION_MS / 1000 + " seconds limit reached)");
            loadTest.markCompleted();
        }
    }
    
    /**
     * Gets metrics for a specific test.
     * 
     * @param testId ID of the test to get metrics for
     * @return Test metrics or null if test not found
     */
    public JsonObject getTestMetrics(String testId) {
        LoadTest loadTest = activeTests.get(testId);
        if (loadTest == null) {
            return null;
        }
        return loadTest.getMetrics();
    }
    
    /**
     * Gets metrics for all active tests.
     * 
     * @return JSON object containing metrics for all tests
     */
    public JsonObject getAllTestMetrics() {
        JsonObject response = new JsonObject();
        activeTests.forEach((id, test) -> {
            response.put(id, test.getMetrics());
        });
        return response;
    }
    
    /**
     * Checks if a test exists.
     * 
     * @param testId ID of the test to check
     * @return true if the test exists, false otherwise
     */
    public boolean testExists(String testId) {
        return activeTests.containsKey(testId);
    }
} 