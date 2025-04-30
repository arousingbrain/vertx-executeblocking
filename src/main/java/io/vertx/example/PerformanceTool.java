package io.vertx.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.core.MultiMap;

// OkHttp imports
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.RequestBody;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.ConnectionPool;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;
import java.io.IOException;

public class PerformanceTool extends AbstractVerticle {

    private static final int MAX_THREADS = 20; // Maximum 20 threads/users
    private static final int MAX_LOOP_COUNT = 10000;
    private static final int MAX_RAMP_UP = 3600; // max 1 hour ramp-up in seconds
    private static final long MAX_TEST_DURATION_MS = 300000; // 5 minutes max duration
    
    // Thread pool configuration
    private static final int CORE_POOL_SIZE = 4;
    private static final int MAX_POOL_SIZE = 20;
    private static final long KEEP_ALIVE_TIME = 60000; // 60 seconds
    
    // Store all load test instances by ID
    private final ConcurrentHashMap<String, LoadTest> activeTests = new ConcurrentHashMap<>();
    
    // Thread pool for executing load test operations
    private ExecutorService executorService;
    
    // HTTP client for making requests
    private OkHttpClient httpClient;

    private static final Set<String> RESERVED_HEADERS = new HashSet<>(Arrays.asList(
        "threads", "rampUpPeriod", "loopCount", "targetUrl"
    ));

    // Class to track load test stats and status
    private static class LoadTest {
        private final String id;
        private final int threads;
        private final int rampUpPeriod;
        private final int loopCount;
        private final long startTime;
        private final AtomicInteger completedIterations = new AtomicInteger(0);
        private final AtomicInteger activeThreads = new AtomicInteger(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private volatile boolean isRunning = true;
        private long timerId = -1; // Timer ID for the timeout handler
        private final Object requestBody; // The request body to use for test requests
        private final String targetUrl; // The target URL to test
        private final MultiMap headers; // Store all headers to forward

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

        public synchronized JsonObject getMetrics() {
            int totalCompleted = completedIterations.get();
            int totalExpected = threads * loopCount;
            long elapsedTimeMs = System.currentTimeMillis() - startTime;
            boolean timeoutReached = elapsedTimeMs >= MAX_TEST_DURATION_MS;
            boolean stillRunning = isRunning && totalCompleted < totalExpected && !timeoutReached;
            
            return new JsonObject()
                .put("id", id)
                .put("threads", threads)
                .put("rampUpPeriod", rampUpPeriod)
                .put("loopCount", loopCount)
                .put("startTime", startTime)
                .put("elapsedTimeMs", elapsedTimeMs)
                .put("maxDurationMs", MAX_TEST_DURATION_MS)
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

        public void markCompleted() {
            this.isRunning = false;
        }

        public void setTimerId(long timerId) {
            this.timerId = timerId;
        }

        public long getTimerId() {
            return timerId;
        }
        
        public Object getRequestBody() {
            return requestBody;
        }
        
        public String getTargetUrl() {
            return targetUrl;
        }
        
        public MultiMap getHeaders() {
            return headers;
        }
    }

    @Override
    public void start(Promise<Void> startPromise) {
        // Initialize the thread pool with a fixed size for executing load tests
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // Allow core threads to timeout to prevent resource waste
        threadPool.allowCoreThreadTimeOut(true);
        
        executorService = threadPool;
        
        // Initialize the HTTP client with pooled connections
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(50, KEEP_ALIVE_TIME, TimeUnit.MILLISECONDS))
            .build();
        
        // Add monitoring to track thread pool health
        vertx.setPeriodic(5000, id -> {
            int queueSize = threadPool.getQueue().size();
            int activeCount = threadPool.getActiveCount();
            if (queueSize > 50 || activeCount > MAX_POOL_SIZE - 2) {
                System.out.println("Thread pool utilization high - Queue: " + queueSize + 
                    ", Active threads: " + activeCount);
            }
        });

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // Endpoint to invoke a load test
        router.post("/invoke").handler(this::handleInvoke);
        
        // Endpoint to get metrics for a load test
        router.get("/metrics").handler(this::handleMetrics);
        
        // Create the HTTP server and listen on port 8090
        vertx.createHttpServer()
            .requestHandler(router)
            .listen(8090, result -> {
                if (result.succeeded()) {
                    System.out.println("PerformanceTool started on port 8090");
                    startPromise.complete();
                } else {
                    System.err.println("Failed to start PerformanceTool: " + result.cause().getMessage());
                    startPromise.fail(result.cause());
                }
            });
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        // Close the HTTP client
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
        
        // Properly shut down the executor service
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
        stopPromise.complete();
    }

    private void handleInvoke(RoutingContext ctx) {
        try {
            // Extract configuration headers
            String threadsStr = ctx.request().getHeader("threads");
            String rampUpStr = ctx.request().getHeader("rampUpPeriod");
            String loopCountStr = ctx.request().getHeader("loopCount");
            String targetUrl = ctx.request().getHeader("targetUrl");

            // Get all headers to pass through
            MultiMap headers = ctx.request().headers();
            MultiMap forwardHeaders = MultiMap.caseInsensitiveMultiMap();
            headers.forEach(entry -> {
                if (!RESERVED_HEADERS.contains(entry.getKey())) {
                    forwardHeaders.add(entry.getKey(), entry.getValue());
                }
            });
    
            // Validate required parameters
            if (targetUrl == null || targetUrl.isEmpty()) {
                sendError(ctx, 400, "targetUrl header is required");
                return;
            }
    
            // Get the request body that will be used in the load test
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
    
            // Validate and parse headers
            int threads = parseIntHeader(threadsStr, 1, 1, MAX_THREADS);
            int rampUpPeriod = parseIntHeader(rampUpStr, 0, 0, MAX_RAMP_UP);
            int loopCount = parseIntHeader(loopCountStr, 1, 1, MAX_LOOP_COUNT);
    
            // Generate a unique ID for this test
            String testId = UUID.randomUUID().toString();
            LoadTest loadTest = new LoadTest(testId, threads, rampUpPeriod, loopCount, requestBody, targetUrl, forwardHeaders);
            activeTests.put(testId, loadTest);
    
            // Set a timeout to stop the test after MAX_TEST_DURATION_MS
            long timerId = vertx.setTimer(MAX_TEST_DURATION_MS, id -> {
                stopTest(loadTest);
            });
            loadTest.setTimerId(timerId);
    
            // Start the load test
            executeLoadTest(loadTest);
    
            // Respond immediately that the test has been started
            JsonObject response = new JsonObject()
                .put("message", "Load test started successfully")
                .put("testId", testId)
                .put("threads", threads)
                .put("rampUpPeriod", rampUpPeriod)
                .put("loopCount", loopCount)
                .put("targetUrl", targetUrl)
                .put("maxDurationMs", MAX_TEST_DURATION_MS);
    
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
        } catch (Exception e) {
            sendError(ctx, 500, "Error starting load test: " + e.getMessage());
        }
    }

    private void sendError(RoutingContext ctx, int statusCode, String message) {
        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("error", message)
                .encode());
    }

    private void stopTest(LoadTest loadTest) {
        if (loadTest.isRunning) {
            System.out.println("Stopping test " + loadTest.id + " due to timeout (5 minutes limit reached)");
            loadTest.markCompleted();
        }
    }

private void handleMetrics(RoutingContext ctx) {
        String testId = ctx.request().getParam("testId");
        
        if (testId == null || testId.isEmpty()) {
            // If no test ID provided, return a list of all active tests
            JsonObject response = new JsonObject();
            activeTests.forEach((id, test) -> {
                response.put(id, test.getMetrics());
            });
            
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(response.encode());
            return;
        }

        // Look up the specified test
        LoadTest loadTest = activeTests.get(testId);
        if (loadTest == null) {
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
        JsonObject metrics = loadTest.getMetrics();
        
        // Check if timeout has been reached since last check
        long elapsedTimeMs = System.currentTimeMillis() - loadTest.startTime;
        if (elapsedTimeMs >= MAX_TEST_DURATION_MS && loadTest.isRunning) {
            stopTest(loadTest);
        }
        
        // If test is still running, indicate that
        if (metrics.getBoolean("isRunning")) {
            metrics.put("message", "Load test is still running");
        } else if (metrics.getBoolean("timeoutReached")) {
            metrics.put("message", "Load test stopped due to 5-minute timeout limit");
        } else {
            metrics.put("message", "Load test completed");
        }

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end(metrics.encode());
    }

    private void executeLoadTest(LoadTest loadTest) {
        // Calculate delay between thread starts if ramp-up is enabled
        long delayBetweenThreadsMs = loadTest.rampUpPeriod > 0 && loadTest.threads > 1 ?
            (loadTest.rampUpPeriod * 1000) / (loadTest.threads - 1) : 0;

        // Launch each thread with appropriate delay
        for (int i = 0; i < loadTest.threads; i++) {
            final int threadIndex = i;
            // Delay thread start based on ramp-up period
            vertx.setTimer(i * delayBetweenThreadsMs, timerId -> {
                executeThread(loadTest, threadIndex);
            });
        }
    }

    private void executeThread(LoadTest loadTest, int threadIndex) {
        loadTest.activeThreads.incrementAndGet();
        
        // Use the ExecutorService instead of vertx.executeBlocking
        executorService.submit(() -> {
            try {
                // Simulate the load test iterations
                for (int i = 0; i < loadTest.loopCount; i++) {
                    // Skip if test has been manually stopped or timeout reached
                    if (!loadTest.isRunning) {
                        break;
                    }
                    
                    // Check if max duration reached
                    if (System.currentTimeMillis() - loadTest.startTime >= MAX_TEST_DURATION_MS) {
                        break;
                    }
                    
                    long startTime = System.currentTimeMillis();
                    
                    // Execute the actual workload using the provided request body
                    try {
                        executeRequest(loadTest, threadIndex, i);
                        loadTest.successCount.incrementAndGet();
                    } catch (Exception e) {
                        loadTest.failureCount.incrementAndGet();
                        System.err.println("Request failed: " + e.getMessage());
                    }
                    
                    long elapsed = System.currentTimeMillis() - startTime;
                    loadTest.totalResponseTime.addAndGet(elapsed);
                    loadTest.completedIterations.incrementAndGet();
                }
            } catch (Exception e) {
                System.err.println("Thread " + threadIndex + " failed: " + e.getMessage());
            } finally {
                // Ensure thread count is decremented even if an exception occurs
                int remainingThreads = loadTest.activeThreads.decrementAndGet();
                
                // Check if this was the last thread to complete
                if (remainingThreads == 0) {
                    // Use Vert.x event loop to safely update the shared state
                    vertx.runOnContext(v -> loadTest.markCompleted());
                }
            }
        });
    }

    private void executeRequest(LoadTest loadTest, int threadIndex, int iteration) throws Exception {
        // Get the target URL and request body from the load test
        String targetUrl = loadTest.getTargetUrl();
        Object requestBody = loadTest.getRequestBody();
        
        // Create a Request object for OkHttp
        Request.Builder requestBuilder = new Request.Builder()
            .url(targetUrl)
            .addHeader("X-Thread-Index", String.valueOf(threadIndex))
            .addHeader("X-Iteration", String.valueOf(iteration))
            .addHeader("Content-Type", requestBody instanceof JsonObject ? "application/json" : "text/plain");

        // Add all headers from the original request
        loadTest.getHeaders().forEach(header -> {
            requestBuilder.addHeader(header.getKey(), header.getValue());
        });

        // Add the request body to the request
        if (requestBody instanceof JsonObject) {
            MediaType jsonMediaType = MediaType.get("application/json");
            requestBuilder.post(RequestBody.create(((JsonObject) requestBody).encode(), jsonMediaType));
        } else if (requestBody != null) {
            MediaType textMediaType = MediaType.get("text/plain");
            requestBuilder.post(RequestBody.create(requestBody.toString(), textMediaType));
        } else {
            // Empty body - still need to POST
            MediaType textMediaType = MediaType.get("text/plain");
            requestBuilder.post(RequestBody.create("", textMediaType));
        }

        // Execute the request
        Request request = requestBuilder.build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP error: " + response.code() + " " + response.message());
            }
            // We don't need to increment success count here as it's already done in the caller method
        } catch (IOException e) {
            throw new Exception("Request failed: " + e.getMessage());
        }
    }

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

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new PerformanceTool(), result -> {
            if (result.succeeded()) {
                System.out.println("PerformanceTool deployed successfully");
            } else {
                System.err.println("Failed to deploy PerformanceTool: " + result.cause().getMessage());
            }
        });
    }
} 