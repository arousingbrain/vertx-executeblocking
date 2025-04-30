package io.vertx.example.perf;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

import io.vertx.example.perf.config.PerformanceToolConfig;
import io.vertx.example.perf.controller.PerformanceToolController;
import io.vertx.example.perf.service.HttpClientService;
import io.vertx.example.perf.service.LoadTestService;
import io.vertx.example.perf.util.ThreadPoolFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A performance testing tool for HTTP services using Vert.x.
 * Provides endpoints for initiating load tests and retrieving metrics.
 * Uses OkHttp client for efficient HTTP connections and thread management.
 */
public class PerformanceTool extends AbstractVerticle {
    
    // Thread pool for executing load test operations
    private ExecutorService executorService;
    
    // Services
    private HttpClientService httpClientService;
    private LoadTestService loadTestService;
    
    // Controller
    private PerformanceToolController controller;

    /**
     * Starts the PerformanceTool verticle.
     * Initializes the thread pool, HTTP client, and sets up HTTP endpoints.
     * 
     * @param startPromise Promise to signal completion of startup
     */
    @Override
    public void start(Promise<Void> startPromise) {
        try {
            // Initialize the thread pool
            ThreadPoolExecutor threadPool = (ThreadPoolExecutor) ThreadPoolFactory.createThreadPool();
            executorService = threadPool;
            
            // Set up monitoring
            ThreadPoolFactory.setupThreadPoolMonitoring(vertx, threadPool);
            
            // Initialize services
            httpClientService = new HttpClientService();
            loadTestService = new LoadTestService(vertx, executorService, httpClientService);
            
            // Initialize controller
            controller = new PerformanceToolController(loadTestService);
            
            // Create router and set up routes
            Router router = Router.router(vertx);
            controller.setupRoutes(vertx, router);
            
            // Create the HTTP server and listen on configured port
            vertx.createHttpServer()
                .requestHandler(router)
                .listen(PerformanceToolConfig.SERVER_PORT, result -> {
                    if (result.succeeded()) {
                        System.out.println("PerformanceTool started on port " + PerformanceToolConfig.SERVER_PORT);
                        startPromise.complete();
                    } else {
                        System.err.println("Failed to start PerformanceTool: " + result.cause().getMessage());
                        startPromise.fail(result.cause());
                    }
                });
        } catch (Exception e) {
            System.err.println("Error starting PerformanceTool: " + e.getMessage());
            startPromise.fail(e);
        }
    }

    /**
     * Stops the PerformanceTool verticle.
     * Gracefully shuts down HTTP client and executor service.
     * 
     * @param stopPromise Promise to signal completion of shutdown
     */
    @Override
    public void stop(Promise<Void> stopPromise) {
        try {
            // Close the HTTP client
            if (httpClientService != null) {
                httpClientService.shutdown();
            }
            
            // Properly shut down the executor service
            ThreadPoolFactory.shutdownThreadPool(executorService);
            
            stopPromise.complete();
        } catch (Exception e) {
            System.err.println("Error stopping PerformanceTool: " + e.getMessage());
            stopPromise.fail(e);
        }
    }

    /**
     * Application entry point.
     * Creates a Vert.x instance and deploys the PerformanceTool verticle.
     * 
     * @param args Command line arguments (unused)
     */
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