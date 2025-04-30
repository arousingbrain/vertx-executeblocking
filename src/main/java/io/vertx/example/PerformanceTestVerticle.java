package io.vertx.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PerformanceTestVerticle extends AbstractVerticle {

    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int MAX_ITERATIONS = 10000;
    private static final int MAX_THREADS = 5;
    private static final long MAX_DELAY = 60000; // 1 minute
    private static final long DEFAULT_TIMEOUT = 30000; // 30 seconds
    private static final long METRICS_RETENTION_MS = 3600000; // 1 hour

    private final ConcurrentHashMap<String, TestMetrics> metricsMap = new ConcurrentHashMap<>();
    private HttpClient httpClient;

    private static class TestMetrics {
        private final AtomicInteger activeRequests = new AtomicInteger(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final AtomicInteger successfulRequests = new AtomicInteger(0);
        private final AtomicInteger failedRequests = new AtomicInteger(0);
        private final AtomicLong totalStatusCodes = new AtomicLong(0);
        private final long startTime;
        private final int totalIterations;
        private final int threadCount;
        private final String targetUrl;
        private volatile boolean isComplete;

        public TestMetrics(int totalIterations, int threadCount, String targetUrl) {
            this.startTime = System.currentTimeMillis();
            this.totalIterations = totalIterations;
            this.threadCount = threadCount;
            this.targetUrl = targetUrl;
            this.isComplete = false;
        }

        public JsonObject toJson() {
            long currentTime = System.currentTimeMillis();
            long totalTime = currentTime - startTime;
            int totalRequests = successfulRequests.get() + failedRequests.get();
            double avgResponseTime = totalRequests > 0 ? 
                (double) totalResponseTime.get() / totalRequests : 0;
            double avgStatusCode = totalRequests > 0 ?
                (double) totalStatusCodes.get() / totalRequests : 0;

            return new JsonObject()
                .put("targetUrl", targetUrl)
                .put("threadCount", threadCount)
                .put("iterationsPerThread", totalIterations / threadCount)
                .put("totalTime", totalTime)
                .put("totalRequests", totalRequests)
                .put("successfulRequests", successfulRequests.get())
                .put("failedRequests", failedRequests.get())
                .put("activeRequests", activeRequests.get())
                .put("averageResponseTime", avgResponseTime)
                .put("averageStatusCode", avgStatusCode)
                .put("requestsPerSecond", totalTime > 0 ? 
                    (double) totalRequests / (totalTime / 1000.0) : 0)
                .put("isComplete", isComplete)
                .put("progress", totalIterations > 0 ? 
                    (double) totalRequests / totalIterations * 100 : 0);
        }
    }

    @Override
    public void start() {
        // Configure HttpClient with connection pooling
        HttpClientOptions options = new HttpClientOptions()
            .setMaxPoolSize(100)
            .setKeepAlive(true)
            .setIdleTimeout(30)
            .setConnectTimeout(5000) // 5 seconds connect timeout
            .setIdleTimeout(10)  // 10 seconds idle timeout
            .setTryUseCompression(true)
            .setVerifyHost(false) // For testing purposes
            .setTrustAll(true);   // For testing purposes

        httpClient = vertx.createHttpClient(options);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        
        // Endpoint to start performance test
        router.post("/test").handler(this::handleTestRequest);
        
        // Endpoint to get test metrics
        router.get("/metrics/:runId").handler(this::handleGetMetrics);

        // Clean up old metrics periodically
        vertx.setPeriodic(60000, timerId -> cleanupOldMetrics());

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(8080, ar -> {
                if (ar.succeeded()) {
                    System.out.println("Performance test server started on port 8080");
                } else {
                    System.err.println("Failed to start server: " + ar.cause().getMessage());
                }
            });
    }

    private void cleanupOldMetrics() {
        long cutoffTime = System.currentTimeMillis() - METRICS_RETENTION_MS;
        metricsMap.entrySet().removeIf(entry -> 
            entry.getValue().startTime < cutoffTime && entry.getValue().isComplete);
    }

    @Override
    public void stop() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    private void handleTestRequest(RoutingContext ctx) {
        JsonObject testConfig = ctx.getBodyAsJson();
        
        String targetUrl = testConfig.getString("url");
        JsonArray headersArray = testConfig.getJsonArray("headers", new JsonArray());
        int iterations = testConfig.getInteger("iterations", 1);
        int threadCount = testConfig.getInteger("threads", 1);
        long delayMs = testConfig.getLong("delay", 0L);
        
        // Validate inputs
        if (targetUrl == null || targetUrl.isEmpty()) {
            sendError(ctx, 400, "URL is required");
            return;
        }

        if (iterations <= 0 || iterations > MAX_ITERATIONS) {
            sendError(ctx, 400, "Iterations must be between 1 and " + MAX_ITERATIONS);
            return;
        }

        if (threadCount <= 0 || threadCount > MAX_THREADS) {
            sendError(ctx, 400, "Thread count must be between 1 and " + MAX_THREADS);
            return;
        }

        if (delayMs < 0 || delayMs > MAX_DELAY) {
            sendError(ctx, 400, "Delay must be between 0 and " + MAX_DELAY + " milliseconds");
            return;
        }

        // Parse URL to get host, port, and path
        URL url;
        try {
            url = new URL(targetUrl);
        } catch (MalformedURLException e) {
            sendError(ctx, 400, "Invalid URL format: " + e.getMessage());
            return;
        }

        String protocol = url.getProtocol();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            sendError(ctx, 400, "Only HTTP and HTTPS protocols are supported");
            return;
        }

        final String host = url.getHost();
        final int port = url.getPort() == -1 ? 
            (protocol.equals("https") ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT) : 
            url.getPort();
        final String uri = url.getPath().isEmpty() ? "/" : 
            url.getPath() + (url.getQuery() != null ? "?" + url.getQuery() : "");

        // Log the connection details
        System.out.println("Starting test with configuration:");
        System.out.println("  Protocol: " + protocol);
        System.out.println("  Host: " + host);
        System.out.println("  Port: " + port + (url.getPort() == -1 ? " (default for " + protocol + ")" : ""));
        System.out.println("  URI: " + uri);
        System.out.println("  Threads: " + threadCount);
        System.out.println("  Iterations per thread: " + (iterations / threadCount));

        // Generate run ID
        String runId = UUID.randomUUID().toString();
        TestMetrics metrics = new TestMetrics(iterations, threadCount, targetUrl);
        metricsMap.put(runId, metrics);

        // Start the test
        executeTest(runId, metrics, host, port, uri, headersArray, iterations, threadCount, delayMs);

        // Return run ID immediately
        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("runId", runId)
                .encode());
    }

    private void executeTest(String runId, TestMetrics metrics, String host, int port, 
            String uri, JsonArray headersArray, int totalIterations, int threadCount, long delayMs) {
        
        int iterationsPerThread = totalIterations / threadCount;
        
        // Start each thread
        for (int threadIndex = 0; threadIndex < threadCount; threadIndex++) {
            final int threadNum = threadIndex;
            vertx.executeBlocking(promise -> {
                // Execute requests for this thread
                for (int i = 0; i < iterationsPerThread; i++) {
                    final int requestNumber = i;
                    vertx.setTimer(i * delayMs, timerId -> {
                        metrics.activeRequests.incrementAndGet();
                        long requestStartTime = System.currentTimeMillis();

                        HttpClientRequest request = httpClient.request(HttpMethod.GET, port, host, uri, response -> {
                            metrics.totalStatusCodes.addAndGet(response.statusCode());
                            
                            response.bodyHandler(body -> {
                                long responseTime = System.currentTimeMillis() - requestStartTime;
                                metrics.totalResponseTime.addAndGet(responseTime);
                                
                                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                                    metrics.successfulRequests.incrementAndGet();
                                } else {
                                    metrics.failedRequests.incrementAndGet();
                                }
                                
                                metrics.activeRequests.decrementAndGet();

                                if (metrics.successfulRequests.get() + metrics.failedRequests.get() == totalIterations) {
                                    metrics.isComplete = true;
                                }
                            });
                        });

                        request.exceptionHandler(err -> {
                            handleRequestFailure(runId, metrics, requestNumber, err, totalIterations);
                        });

                        // Set headers from the array
                        for (int j = 0; j < headersArray.size(); j++) {
                            String headerPair = headersArray.getString(j);
                            String[] parts = headerPair.split(":", 2);
                            if (parts.length == 2) {
                                request.putHeader(parts[0].trim(), parts[1].trim());
                            }
                        }

                        // Set default headers
                        request.putHeader("User-Agent", "Vert.x-Performance-Test");
                        request.putHeader("Accept", "*/*");

                        // Set request timeout
                        request.setTimeout(DEFAULT_TIMEOUT);

                        request.end();
                    });
                }
                promise.complete();
            }, false, ar -> {
                if (ar.failed()) {
                    System.err.println("Thread " + threadNum + " failed: " + ar.cause().getMessage());
                }
            });
        }
    }

    private void handleRequestFailure(String runId, TestMetrics metrics, int requestNumber, 
            Throwable cause, int totalIterations) {
        metrics.failedRequests.incrementAndGet();
        metrics.activeRequests.decrementAndGet();
        System.err.println("Request " + requestNumber + " failed: " + cause.getMessage());

        if (metrics.successfulRequests.get() + metrics.failedRequests.get() == totalIterations) {
            metrics.isComplete = true;
        }
    }

    private void handleGetMetrics(RoutingContext ctx) {
        String runId = ctx.pathParam("runId");
        if (runId == null || runId.isEmpty()) {
            sendError(ctx, 400, "Run ID is required");
            return;
        }

        TestMetrics metrics = metricsMap.get(runId);
        if (metrics == null) {
            sendError(ctx, 404, "No metrics found for run ID: " + runId);
            return;
        }

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end(metrics.toJson().encode());
    }

    private void sendError(RoutingContext ctx, int statusCode, String message) {
        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(new JsonObject()
                .put("error", message)
                .encode());
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new PerformanceTestVerticle());
    }
} 