package io.vertx.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone load testing application.
 * This version is independent of Vert.x and uses Java's built-in HTTP client and concurrency utilities.
 */
public class PerfApp {
    // Load test configuration
    private static final String TARGET_URL = "http://localhost:8080/api/test"; // Change as needed
    private static final int THREADS = 10;
    private static final int RAMP_UP_PERIOD = 5; // seconds
    private static final int LOOP_COUNT = 100;
    private static final int MAX_TEST_DURATION_MS = 300000; // 5 minutes
    
    // HTTP client configuration
    private static final int CONNECTION_TIMEOUT = 30; // seconds
    private static final int REQUEST_TIMEOUT = 30; // seconds
    private static final int MAX_CONNECTIONS = 100;
    
    // Metrics
    private static final AtomicInteger requestCount = new AtomicInteger(0);
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failureCount = new AtomicInteger(0);
    private static final AtomicLong totalResponseTime = new AtomicLong(0);
    private static final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    private static final AtomicLong maxResponseTime = new AtomicLong(0);
    private static final AtomicInteger activeThreads = new AtomicInteger(0);
    private static final AtomicBoolean testRunning = new AtomicBoolean(true);
    private static final long testStartTime = System.currentTimeMillis();
    
    // Thread pool for load test execution
    private static ThreadPoolExecutor threadPool;
    private static HttpClient httpClient;
    private static ExecutorService httpClientExecutor;
    
    public static void main(String[] args) {
        try {
            System.out.println("Starting load test application...");
            
            // Initialize resources
            initializeResources();
            
            // Read request body from file
            String requestBody = readBodyFromFile();
            System.out.println("Loaded request body from file");
            
            // Start the load test
            String testId = UUID.randomUUID().toString();
            System.out.println("Started load test with ID: " + testId);
            System.out.println("Configuration:");
            System.out.println("  Target URL: " + TARGET_URL);
            System.out.println("  Threads: " + THREADS);
            System.out.println("  Ramp-up period: " + RAMP_UP_PERIOD + " seconds");
            System.out.println("  Loop count: " + LOOP_COUNT);
            
            // Start test execution
            executeLoadTest(requestBody);
            
            // Wait for test to complete while showing progress
            waitForTestCompletion();
            
            // Display final results
            displayTestResults();
            
        } catch (Exception e) {
            System.err.println("Error running load test: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Shutdown resources
            shutdownResources();
        }
    }
    
    /**
     * Initialize thread pool and HTTP client.
     */
    private static void initializeResources() {
        // Create thread pool
        threadPool = new ThreadPoolExecutor(
            THREADS,
            THREADS,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(THREADS * LOOP_COUNT),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // Create HTTP client executor
        httpClientExecutor = Executors.newFixedThreadPool(MAX_CONNECTIONS);
        
        // Create HTTP client
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECTION_TIMEOUT))
            .executor(httpClientExecutor)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }
    
    /**
     * Read request body from the body.json file.
     */
    private static String readBodyFromFile() throws IOException {
        try {
            return new String(Files.readAllBytes(Paths.get("body.json")));
        } catch (IOException e) {
            System.err.println("Warning: Could not read body.json file: " + e.getMessage());
            System.err.println("Continuing with empty request body.");
            return "{}";
        }
    }
    
    /**
     * Execute the load test with the given request body.
     */
    private static void executeLoadTest(String requestBody) {
        long delayBetweenThreadsMs = RAMP_UP_PERIOD > 0 && THREADS > 1 ?
            (RAMP_UP_PERIOD * 1000L) / (THREADS - 1) : 0;
        
        List<CompletableFuture<Void>> threadFutures = new ArrayList<>();
        
        // Launch threads with ramp-up delay
        for (int i = 0; i < THREADS; i++) {
            final int threadIndex = i;
            
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // Apply ramp-up delay
                    if (delayBetweenThreadsMs > 0) {
                        Thread.sleep(threadIndex * delayBetweenThreadsMs);
                    }
                    
                    activeThreads.incrementAndGet();
                    executeThread(threadIndex, requestBody);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    activeThreads.decrementAndGet();
                }
            }, threadPool);
            
            threadFutures.add(future);
        }
        
        // Wait for all threads to complete or timeout
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(threadFutures.toArray(new CompletableFuture[0]))
            .orTimeout(MAX_TEST_DURATION_MS, TimeUnit.MILLISECONDS)
            .exceptionally(throwable -> {
                System.err.println("Test timeout or error: " + throwable.getMessage());
                testRunning.set(false);
                return null;
            });
            
        // We don't need to block here as waitForTestCompletion will handle the waiting
    }
    
    /**
     * Execute a single thread's worth of requests.
     */
    private static void executeThread(int threadIndex, String requestBody) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(TARGET_URL))
            .header("Content-Type", "application/json")
            .header("X-Thread-Index", String.valueOf(threadIndex))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT))
            .build();
        
        for (int i = 0; i < LOOP_COUNT && testRunning.get(); i++) {
            if (System.currentTimeMillis() - testStartTime >= MAX_TEST_DURATION_MS) {
                break;
            }
            
            long requestStartTime = System.currentTimeMillis();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsed = System.currentTimeMillis() - requestStartTime;
                
                // Update metrics
                requestCount.incrementAndGet();
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }
                
                totalResponseTime.addAndGet(elapsed);
                updateMinMaxResponseTime(elapsed);
                
            } catch (Exception e) {
                requestCount.incrementAndGet(); // Count the request even if it failed
                failureCount.incrementAndGet();
                System.err.println("Request failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Update minimum and maximum response times in a thread-safe way.
     */
    private static void updateMinMaxResponseTime(long responseTime) {
        // Update min response time
        minResponseTime.updateAndGet(current -> Math.min(current, responseTime));
        
        // Update max response time
        maxResponseTime.updateAndGet(current -> Math.max(current, responseTime));
    }
    
    /**
     * Wait for the test to complete while showing progress.
     */
    private static void waitForTestCompletion() {
        boolean running = true;
        int progressChar = 0;
        String[] progressChars = {"|", "/", "-", "\\"};
        
        System.out.println("Test in progress. Press Enter at any time to view current metrics.");
        
        // Start a thread to watch for user input
        Thread inputThread = new Thread(() -> {
            try (Scanner scanner = new Scanner(System.in)) {
                while (testRunning.get()) {
                    if (scanner.hasNextLine()) {
                        scanner.nextLine();
                        printMetrics();
                    }
                    // Short sleep to prevent CPU spinning
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        inputThread.setDaemon(true);
        inputThread.start();
        
        // Monitor test progress
        while (running && testRunning.get()) {
            try {
                // Print progress indicator
                System.out.print("\r" + progressChars[progressChar] + " Test running... ");
                progressChar = (progressChar + 1) % progressChars.length;
                
                // Check if test is complete
                if (activeThreads.get() == 0) {
                    running = false;
                    testRunning.set(false);
                    System.out.println("\nTest completed!");
                    break;
                }
                
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * Display the final test results.
     */
    private static void displayTestResults() {
        System.out.println("\n==== FINAL TEST RESULTS ====");
        printMetrics();
    }
    
    /**
     * Print current metrics.
     */
    private static void printMetrics() {
        int totalRequests = requestCount.get();
        int successes = successCount.get();
        int failures = failureCount.get();
        long totalTime = totalResponseTime.get();
        
        System.out.println("Active Threads: " + activeThreads.get());
        System.out.println("Total Requests: " + totalRequests);
        System.out.println("Successful: " + successes);
        System.out.println("Failed: " + failures);
        
        if (totalRequests > 0) {
            double avgResponseTime = totalTime / (double) totalRequests;
            System.out.println("Average Response Time: " + String.format("%.2f", avgResponseTime) + " ms");
            
            // Protect against initialization value when no responses received yet
            if (minResponseTime.get() != Long.MAX_VALUE) {
                System.out.println("Min Response Time: " + minResponseTime.get() + " ms");
            } else {
                System.out.println("Min Response Time: N/A");
            }
            
            System.out.println("Max Response Time: " + maxResponseTime.get() + " ms");
            
            double elapsedSeconds = (System.currentTimeMillis() - testStartTime) / 1000.0;
            if (elapsedSeconds > 0) {
                double throughput = totalRequests / elapsedSeconds;
                System.out.println("Throughput: " + String.format("%.2f", throughput) + " requests/sec");
            }
            
            double errorRate = failures / (double) totalRequests * 100;
            System.out.println("Error Rate: " + String.format("%.2f", errorRate) + "%");
        }
        
        System.out.println("-----------------------------");
    }
    
    /**
     * Shutdown thread pool and HTTP client.
     */
    private static void shutdownResources() {
        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        // Shutdown HTTP client executor
        if (httpClientExecutor != null) {
            httpClientExecutor.shutdown();
            try {
                if (!httpClientExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    httpClientExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                httpClientExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        System.out.println("Load test application shutdown complete");
    }
} 