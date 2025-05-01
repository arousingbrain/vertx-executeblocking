package io.vertx.example.perf;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.example.perf.service.HttpClientService;
import io.vertx.example.perf.service.LoadTestService;
import io.vertx.example.perf.util.ThreadPoolFactory;
import io.vertx.example.perf.model.LoadTest;
import io.vertx.example.perf.config.PerformanceToolConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A performance testing tool for HTTP services.
 * Provides standalone functionality for load testing based on headers.
 * Uses OkHttp client for efficient HTTP connections and thread management.
 * 
 * The operations are determined by the 'perf_tool' header:
 * - 'invoke': Initiates a load test
 * - 'metrics': Retrieves metrics for one or all tests
 */
public class PerformanceTool {
    
    // Singleton instance
    private static volatile PerformanceTool instance;
    
    // Services
    private static HttpClientService httpClientService;
    private static LoadTestService loadTestService;
    
    // Map to track test status
    private static final Map<String, Boolean> activeTests = new ConcurrentHashMap<>();
    
    /**
     * Private constructor to prevent instantiation.
     */
    private PerformanceTool() {
        // Private constructor to enforce singleton pattern
        initialize();
    }
    
    /**
     * Gets the singleton instance of PerformanceTool.
     * 
     * @return The singleton instance
     */
    public static PerformanceTool getInstance() {
        if (instance == null) {
            synchronized (PerformanceTool.class) {
                if (instance == null) {
                    instance = new PerformanceTool();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initializes the PerformanceTool.
     * Sets up services and resources.
     */
    private void initialize() {
        try {
            // Initialize services
            httpClientService = new HttpClientService();
            loadTestService = new LoadTestService(null, null, httpClientService);
            
            System.out.println("PerformanceTool initialized successfully");
        } catch (Exception e) {
            System.err.println("Error initializing PerformanceTool: " + e.getMessage());
            throw new RuntimeException("Failed to initialize PerformanceTool", e);
        }
    }
    
    /**
     * Handles a request from the event bus.
     * Processes the request based on the perf_tool header value.
     * 
     * @param headers The request headers
     * @param requestBody The request body (optional)
     * @return JsonObject containing the response
     * @throws IllegalStateException if trying to start a test while another is running
     */
    public JsonObject handleRequest(MultiMap headers, Object requestBody) {
        String operation = headers.get("perf_tool");
        
        if ("invoke".equalsIgnoreCase(operation)) {
            // Check if a test is already running
            ThreadPoolFactory threadPoolFactory = ThreadPoolFactory.getInstance();
            if (!threadPoolFactory.canStartTest()) {
                String currentTestId = threadPoolFactory.getCurrentTestId();
                throw new IllegalStateException("Cannot start new test. Test " + currentTestId + 
                    " is currently running. Please wait for it to complete or check its status using the metrics operation.");
            }
            
            // Extract load test parameters from headers
            String targetUrl = headers.get("targetUrl");
            if (targetUrl == null || targetUrl.isEmpty()) {
                throw new IllegalArgumentException("Missing required header: targetUrl");
            }
            
            // Parse parameters with defaults and limits
            int threads = parseIntHeader(headers.get("threads"), 
                PerformanceToolConfig.DEFAULT_THREADS, 1, PerformanceToolConfig.MAX_THREADS);
            int rampUpPeriod = parseIntHeader(headers.get("rampUpPeriod"), 
                PerformanceToolConfig.DEFAULT_RAMP_UP, 0, PerformanceToolConfig.MAX_RAMP_UP);
            int loopCount = parseIntHeader(headers.get("loopCount"), 
                PerformanceToolConfig.DEFAULT_LOOP_COUNT, 1, PerformanceToolConfig.MAX_LOOP_COUNT);
            
            // Start the load test
            String testId = startLoadTest(targetUrl, threads, rampUpPeriod, loopCount, requestBody, headers);
            
            // Return test configuration details
            return new JsonObject()
                .put("message", "Load test started successfully")
                .put("testId", testId)
                .put("threads", threads)
                .put("rampUpPeriod", rampUpPeriod)
                .put("loopCount", loopCount)
                .put("targetUrl", targetUrl)
                .put("maxDurationMs", PerformanceToolConfig.MAX_TEST_DURATION_MS);
                
        } else if ("metrics".equalsIgnoreCase(operation)) {
            // Get test ID from headers if present
            String testId = headers.get("testId");
            
            if (testId != null && !testId.isEmpty()) {
                // Get metrics for specific test
                JsonObject metrics = getTestMetrics(testId);
                if (metrics == null) {
                    throw new IllegalArgumentException("Test not found: " + testId);
                }
                return metrics;
            } else {
                // Get metrics for all tests
                return getAllTestMetrics();
            }
        } else {
            throw new IllegalArgumentException("Invalid perf_tool operation: " + operation + 
                ". Valid values are 'invoke' or 'metrics'");
        }
    }
    
    /**
     * Parses an integer header value with limits.
     * 
     * @param value The header value string
     * @param defaultValue Default value if header is missing or invalid
     * @param minValue Minimum allowed value
     * @param maxValue Maximum allowed value
     * @return The parsed and validated integer value
     */
    private int parseIntHeader(String value, int defaultValue, int minValue, int maxValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        
        try {
            int intValue = Integer.parseInt(value);
            return Math.min(Math.max(intValue, minValue), maxValue);
        } catch (NumberFormatException e) {
            return defaultValue;
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
        String testId = UUID.randomUUID().toString();
        LoadTest loadTest = new LoadTest(testId, threads, rampUpPeriod, loopCount, requestBody, targetUrl, headers);
        
        // Track this test as active
        activeTests.put(testId, true);
        
        // Start the load test
        loadTestService.startLoadTest(loadTest);
        
        // Return the test ID
        return testId;
    }
    
    /**
     * Gets metrics for a specific test.
     * 
     * @param testId ID of the test to get metrics for
     * @return Test metrics or null if test not found
     */
    public JsonObject getTestMetrics(String testId) {
        JsonObject metrics = loadTestService.getTestMetrics(testId);
        
        // If the test is completed, remove it from active tracking
        if (metrics != null && !metrics.getBoolean("running", true)) {
            activeTests.remove(testId);
        }
        
        return metrics;
    }
    
    /**
     * Gets metrics for all active tests.
     * 
     * @return JSON object containing metrics for all tests
     */
    public JsonObject getAllTestMetrics() {
        JsonObject allMetrics = loadTestService.getAllTestMetrics();
        
        // Clean up completed tests from tracking
        allMetrics.forEach(entry -> {
            JsonObject testMetrics = (JsonObject) entry.getValue();
            if (!testMetrics.getBoolean("running", true)) {
                activeTests.remove(entry.getKey());
            }
        });
        
        return allMetrics;
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
     * Checks if a test is still running.
     * 
     * @param testId ID of the test to check
     * @return true if the test is still running, false otherwise
     */
    public boolean isTestRunning(String testId) {
        return activeTests.containsKey(testId);
    }
    
    /**
     * Shuts down the PerformanceTool.
     * Cleans up resources.
     * Should be called when the application is shutting down.
     */
    public static void shutdown() {
        try {
            // Close the HTTP client
            if (httpClientService != null) {
                httpClientService.shutdown();
            }
            
            // Clear tracking map
            activeTests.clear();
            
            // Clear the singleton instance
            instance = null;
            
            System.out.println("PerformanceTool shutdown complete");
        } catch (Exception e) {
            System.err.println("Error during PerformanceTool shutdown: " + e.getMessage());
        }
    }
} 