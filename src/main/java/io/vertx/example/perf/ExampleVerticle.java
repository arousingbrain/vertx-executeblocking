package io.vertx.example.perf;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Example Verticle that demonstrates how to use the PerformanceTool singleton.
 * This Verticle shows how to initialize the tool and handle its lifecycle.
 */
public class ExampleVerticle extends AbstractVerticle {
    
    private PerformanceTool performanceTool;
    
    /**
     * Starts the ExampleVerticle.
     * Initializes the PerformanceTool singleton and sets up a simple HTTP endpoint.
     * 
     * @param startPromise Promise to signal completion of startup
     */
    @Override
    public void start(Promise<Void> startPromise) {
        try {
            // Get PerformanceTool singleton instance
            performanceTool = PerformanceTool.getInstance(vertx);
            
            // Create router and set up routes
            Router router = Router.router(vertx);
            router.route().handler(BodyHandler.create());
            
            // Simple endpoint to demonstrate the Verticle is running
            router.route(HttpMethod.GET, "/hello").handler(ctx -> {
                ctx.response()
                   .putHeader("Content-Type", "text/plain")
                   .end("Hello from ExampleVerticle! PerformanceTool is initialized.");
            });
            
            // Create the HTTP server and listen on configured port
            vertx.createHttpServer()
                .requestHandler(router)
                .listen(8080, result -> {
                    if (result.succeeded()) {
                        System.out.println("ExampleVerticle started on port 8080");
                        startPromise.complete();
                    } else {
                        System.err.println("Failed to start ExampleVerticle: " + result.cause().getMessage());
                        startPromise.fail(result.cause());
                    }
                });
        } catch (Exception e) {
            System.err.println("Error starting ExampleVerticle: " + e.getMessage());
            startPromise.fail(e);
        }
    }
    
    /**
     * Stops the ExampleVerticle.
     * Note: We don't shutdown the PerformanceTool here since other Verticles might be using it.
     * 
     * @param stopPromise Promise to signal completion of shutdown
     */
    @Override
    public void stop(Promise<Void> stopPromise) {
        try {
            // We don't shut down the PerformanceTool here since other Verticles might be using it
            // The JVM shutdown hook or the main Verticle should handle that
            stopPromise.complete();
        } catch (Exception e) {
            System.err.println("Error stopping ExampleVerticle: " + e.getMessage());
            stopPromise.fail(e);
        }
    }
    
    /**
     * Application entry point.
     * Creates a Vert.x instance and deploys the ExampleVerticle.
     * 
     * @param args Command line arguments (unused)
     */
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new ExampleVerticle(), result -> {
            if (result.succeeded()) {
                System.out.println("ExampleVerticle deployed successfully");
                
                // Register shutdown hook to gracefully shut down PerformanceTool
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("Shutting down PerformanceTool...");
                    PerformanceTool.getInstance(vertx).shutdown();
                }));
            } else {
                System.err.println("Failed to deploy ExampleVerticle: " + result.cause().getMessage());
            }
        });
    }
} 