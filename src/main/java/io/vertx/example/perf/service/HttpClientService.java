package io.vertx.example.perf.service;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.example.perf.config.PerformanceToolConfig;
import io.vertx.example.perf.model.LoadTest;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for HTTP client operations.
 * Uses OkHttp for efficient connection handling.
 */
public class HttpClientService {
    private final OkHttpClient httpClient;
    
    /**
     * Creates a new HTTP client service.
     * Initializes the OkHttp client with configured parameters.
     */
    public HttpClientService() {
        // Initialize the HTTP client with pooled connections
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(PerformanceToolConfig.HTTP_CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(PerformanceToolConfig.HTTP_READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(PerformanceToolConfig.HTTP_WRITE_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(
                PerformanceToolConfig.HTTP_CONNECTION_POOL_SIZE, 
                PerformanceToolConfig.KEEP_ALIVE_TIME, 
                TimeUnit.MILLISECONDS))
            .build();
    }
    
    /**
     * Executes an HTTP request for a load test.
     * 
     * @param loadTest The load test containing URL and headers
     * @param threadIndex Thread index for tracking
     * @param iteration Iteration number for tracking
     * @throws Exception If the request fails
     */
    public void executeRequest(LoadTest loadTest, int threadIndex, int iteration) throws Exception {
        // Get test configuration values
        String targetUrl = loadTest.getTargetUrl();
        Object requestBody = loadTest.getRequestBody();
        
        // Build the HTTP request with the target URL
        Request.Builder requestBuilder = new Request.Builder()
            .url(targetUrl)
            .addHeader("X-Thread-Index", String.valueOf(threadIndex))
            .addHeader("X-Iteration", String.valueOf(iteration))
            .addHeader("Content-Type", requestBody instanceof JsonObject ? "application/json" : "text/plain");

        // Add all forwarded headers from the original request
        loadTest.getHeaders().forEach(header -> {
            requestBuilder.addHeader(header.getKey(), header.getValue());
        });

        // Add the request body to the POST request with appropriate content type
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

        // Execute the request synchronously (we're already in a worker thread)
        Request request = requestBuilder.build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP error: " + response.code() + " " + response.message());
            }
        } catch (IOException e) {
            throw new Exception("Request failed: " + e.getMessage());
        }
    }
    
    /**
     * Shuts down the HTTP client.
     */
    public void shutdown() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }
} 