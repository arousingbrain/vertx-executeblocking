package io.vertx.example.perf;

import io.vertx.core.Vertx;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.example.perf.service.HttpClientService;
import io.vertx.example.perf.service.LoadTestService;
import io.vertx.example.perf.util.ThreadPoolFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A performance testing tool for HTTP services using Vert.x.
 * Singleton implementation that can be used by external Verticles.
 * Provides functionality for load testing based on headers.
 * Uses OkHttp client for efficient HTTP connections and thread management.
 * 
 * The operations are determined by the 'perf_tool' header:
 * - 'invoke': Initiates a load test
 * - 'metrics': Retrieves metrics for one or all tests
 */
public class PerformanceTool {
    
    // Singleton instance
    private static volatile PerformanceTool instance;
    
    // Thread pool for executing load test operations
    private ExecutorService executorService;
    
    // Services
    private HttpClientService httpClientService;
    private LoadTestService loadTestService;
    
    // Vert.x instance
    private Vertx vertx;
    
    // Thread pool monitoring timer ID
    private long monitoringTimerId = -1;
    
    /**
     * Private constructor to prevent direct instantiation.
     * Use getInstance() method to get the singleton instance.
     * 
     * @param vertx Vert.x instance to use
     */
    private PerformanceTool(Vertx vertx) {
        this.vertx = vertx;
        initialize();
    }
    
    /**
     * Gets the singleton instance of PerformanceTool.
     * Creates a new instance if one doesn't exist.
     * 
     * @param vertx Vert.x instance to use
     * @return Singleton instance of PerformanceTool
     */
    public static PerformanceTool getInstance(Vertx vertx) {
        if (instance == null) {
            synchronized (PerformanceTool.class) {
                if (instance == null) {
                    instance = new PerformanceTool(vertx);
                }
            }
        }
        return instance;
    }
    
    /**
     * Initializes the PerformanceTool.
     * Sets up thread pool, HTTP client, and services.
     */
    private void initialize() {
        try {
            // Initialize the thread pool
            ThreadPoolExecutor threadPool = (ThreadPoolExecutor) ThreadPoolFactory.createThreadPool();
            executorService = threadPool;
            
            // Set up monitoring
            monitoringTimerId = ThreadPoolFactory.setupThreadPoolMonitoring(vertx, threadPool);
            
            // Initialize services
            httpClientService = new HttpClientService();
            loadTestService = new LoadTestService(vertx, executorService, httpClientService);
            
            System.out.println("PerformanceTool initialized successfully");
        } catch (Exception e) {
            System.err.println("Error initializing PerformanceTool: " + e.getMessage());
            throw new RuntimeException("Failed to initialize PerformanceTool", e);
        }
    }
    
    /**
     * Initiates a load test with the given parameters.
     * 
     * @param targetUrl URL of the service to test
     * @param threads Number of concurrent users/threads
     * @param rampUpPeriod Time in seconds to ramp up threads
     * @param loopCount Number of iterations per thread
     * @param requestBody Body to send in the request
     * @param headers Headers to include in the request
     * @return ID of the created test
     */
    public String startLoadTest(String targetUrl, int threads, int rampUpPeriod, int loopCount, 
                               Object requestBody, MultiMap headers) {
        return loadTestService.startLoadTest(
            new io.vertx.example.perf.model.LoadTest(
                java.util.UUID.randomUUID().toString(), 
                threads, 
                rampUpPeriod, 
                loopCount, 
                requestBody, 
                targetUrl, 
                headers
            )
        );
    }
    
    /**
     * Gets metrics for a specific test.
     * 
     * @param testId ID of the test to get metrics for
     * @return Test metrics or null if test not found
     */
    public JsonObject getTestMetrics(String testId) {
        return loadTestService.getTestMetrics(testId);
    }
    
    /**
     * Gets metrics for all active tests.
     * 
     * @return JSON object containing metrics for all tests
     */
    public JsonObject getAllTestMetrics() {
        return loadTestService.getAllTestMetrics();
    }
    
    /**
     * Checks if a test exists.
     * 
     * @param testId ID of the test to check
     * @return true if the test exists, false otherwise
     */
    public boolean testExists(String testId) {
        return loadTestService.testExists(testId);
    }
    
    /**
     * Shuts down the PerformanceTool.
     * Cleans up resources and stops the thread pool.
     */
    public void shutdown() {
        try {
            // Cancel the monitoring timer
            if (monitoringTimerId != -1) {
                vertx.cancelTimer(monitoringTimerId);
            }
            
            // Close the HTTP client
            if (httpClientService != null) {
                httpClientService.shutdown();
            }
            
            // Properly shut down the executor service
            ThreadPoolFactory.shutdownThreadPool(executorService);
            
            // Reset the singleton instance
            synchronized (PerformanceTool.class) {
                instance = null;
            }
            
            System.out.println("PerformanceTool shutdown complete");
        } catch (Exception e) {
            System.err.println("Error during PerformanceTool shutdown: " + e.getMessage());
        }
    }
} 