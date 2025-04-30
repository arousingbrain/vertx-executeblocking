package io.vertx.example.perf.controller;

import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.example.perf.config.PerformanceToolConfig;
import io.vertx.example.perf.model.LoadTest;
import io.vertx.example.perf.service.LoadTestService;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.UUID;

/**
 * Controller for handling HTTP endpoints of the performance tool.
 * Defines routes and processes HTTP requests for load test operations.
 */
public class PerformanceToolController {
    private final LoadTestService loadTestService;
    
    // Header key that determines the operation to execute
    private static final String PERF_TOOL_HEADER = "perf_tool";
    private static final String INVOKE_OPERATION = "invoke";
    private static final String METRICS_OPERATION = "metrics";
    
    /**
     * Creates a new controller with the specified services.
     * 
     * @param loadTestService Service for managing load tests
     */
    public PerformanceToolController(LoadTestService loadTestService) {
        this.loadTestService = loadTestService;
    }
    
    /**
     * Sets up routes for the controller.
     * 
     * @param vertx Vertx instance
     * @param router Router to add routes to
     */
    public void setupRoutes(Vertx vertx, Router router) {
        // Add body handler for all routes
        router.route().handler(BodyHandler.create());
        
        // Single endpoint that handles all operations based on headers
        router.route("/").handler(this::handleRequest);
    }
    
    /**
     * Handles all performance tool requests based on the perf_tool header.
     * 
     * @param ctx Routing context containing the request
     */
    private void handleRequest(RoutingContext ctx) {
        String operation = ctx.request().getHeader(PERF_TOOL_HEADER);
        
        if (operation == null || operation.isEmpty()) {
            sendError(ctx, 400, "Missing required header: " + PERF_TOOL_HEADER);
            return;
        }
        
        switch (operation.toLowerCase()) {
            case INVOKE_OPERATION:
                handleInvoke(ctx);
                break;
            case METRICS_OPERATION:
                handleMetrics(ctx);
                break;
            default:
                sendError(ctx, 400, "Invalid operation: " + operation + ". Valid values are 'invoke' or 'metrics'");
                break;
        }
    }
    
    /**
     * Handles load test initiation requests.
     * 
     * Required headers:
     * - perf_tool: must be set to "invoke"
     * - targetUrl: URL of the service to test (required)
     * - threads: Number of concurrent users/threads (optional, default=1, max=20)
     * - rampUpPeriod: Time in seconds to ramp up threads (optional, default=0, max=3600)
     * - loopCount: Number of iterations per thread (optional, default=1, max=10000)
     * 
     * Request body:
     * - Any valid JSON or text content to be sent to the target URL
     * - All non-reserved headers are passed through to the target
     * 
     * @param ctx Routing context containing the request
     */
    private void handleInvoke(RoutingContext ctx) {
        try {
            // Extract configuration headers
            String threadsStr = ctx.request().getHeader("threads");
            String rampUpStr = ctx.request().getHeader("rampUpPeriod");
            String loopCountStr = ctx.request().getHeader("loopCount");
            String targetUrl = ctx.request().getHeader("targetUrl");

            // Get all headers to pass through (filtering out reserved configuration headers)
            MultiMap headers = ctx.request().headers();
            MultiMap forwardHeaders = MultiMap.caseInsensitiveMultiMap();
            headers.forEach(entry -> {
                if (!PerformanceToolConfig.RESERVED_HEADERS.contains(entry.getKey()) && 
                    !PERF_TOOL_HEADER.equalsIgnoreCase(entry.getKey())) {
                    forwardHeaders.add(entry.getKey(), entry.getValue());
                }
            });
    
            // Validate required parameters
            if (targetUrl == null || targetUrl.isEmpty()) {
                sendError(ctx, 400, "targetUrl header is required");
                return;
            }
    
            // Extract request body (try as JSON first, fall back to plain text)
            Object requestBody = null;
            if (ctx.getBody().length() > 0) {
                try {
                    // Try to parse as JSON
                    requestBody = ctx.getBodyAsJson();
                } catch (Exception e) {
                    // If not valid JSON, use as text
                    requestBody = ctx.getBodyAsString();
                }
            }
    
            // Parse and validate numeric header values with min/max constraints
            int threads = parseIntHeader(threadsStr, PerformanceToolConfig.DEFAULT_THREADS, 1, PerformanceToolConfig.MAX_THREADS);
            int rampUpPeriod = parseIntHeader(rampUpStr, PerformanceToolConfig.DEFAULT_RAMP_UP, 0, PerformanceToolConfig.MAX_RAMP_UP);
            int loopCount = parseIntHeader(loopCountStr, PerformanceToolConfig.DEFAULT_LOOP_COUNT, 1, PerformanceToolConfig.MAX_LOOP_COUNT);
    
            // Generate a unique ID for this test
            String testId = UUID.randomUUID().toString();
            LoadTest loadTest = new LoadTest(testId, threads, rampUpPeriod, loopCount, requestBody, targetUrl, forwardHeaders);
            
            // Start the load test
            loadTestService.startLoadTest(loadTest);
    
            // Respond immediately with test configuration details
            JsonObject response = new JsonObject()
                .put("message", "Load test started successfully")
                .put("testId", testId)
                .put("threads", threads)
                .put("rampUpPeriod", rampUpPeriod)
                .put("loopCount", loopCount)
                .put("targetUrl", targetUrl)
                .put("maxDurationMs", PerformanceToolConfig.MAX_TEST_DURATION_MS);
    
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            sendError(ctx, 500, "Error starting load test: " + e.getMessage());
        }
    }
    
    /**
     * Handles requests for load test metrics.
     * 
     * Required headers:
     * - perf_tool: must be set to "metrics"
     * 
     * Query parameters:
     * - testId: ID of the specific load test (optional)
     *   If omitted, returns metrics for all active tests
     * 
     * @param ctx Routing context containing the request
     */
    private void handleMetrics(RoutingContext ctx) {
        String testId = ctx.request().getParam("testId");
        
        if (testId == null || testId.isEmpty()) {
            // If no test ID provided, return a list of all active tests
            JsonObject response = loadTestService.getAllTestMetrics();
            
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
            return;
        }

        // Look up the specified test
        if (!loadTestService.testExists(testId)) {
            ctx.response()
                .setStatusCode(404)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("error", "Load test not found")
                    .put("testId", testId)
                    .encode());
            return;
        }

        // Return metrics for the requested test
        JsonObject metrics = loadTestService.getTestMetrics(testId);
        
        // Add appropriate message based on test status
        if (metrics.getBoolean("isRunning")) {
            metrics.put("message", "Load test is still running");
        } else if (metrics.getBoolean("timeoutReached")) {
            metrics.put("message", "Load test stopped due to timeout limit");
        } else {
            metrics.put("message", "Load test completed");
        }

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end(metrics.encode());
    }
    
    /**
     * Sends an error response to the client.
     * 
     * @param ctx Routing context
     * @param statusCode HTTP status code to return
     * @param message Error message
     */
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("error", message)
                .encode());
    }
    
    /**
     * Parses and validates an integer header value.
     * 
     * @param value Header value to parse
     * @param defaultValue Default value if header is missing
     * @param min Minimum allowed value
     * @param max Maximum allowed value
     * @return Parsed and validated integer value
     */
    private int parseIntHeader(String value, int defaultValue, int min, int max) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min) {
                return min;
            }
            if (parsed > max) {
                return max;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
} 