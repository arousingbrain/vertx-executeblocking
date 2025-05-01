package io.vertx.example.perf;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.eventbus.Message;
import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Example Verticle that demonstrates how to use the PerformanceTool.
 * Checks for the perf_tool header to determine whether to use the performance tool.
 */
public class ExampleVerticle extends AbstractVerticle {
    
    // Header that determines if this is a performance tool request
    private static final String PERF_TOOL_HEADER = "perf_tool";
    private static final String ADDRESS = "dummy.request";
    
    /**
     * Starts the ExampleVerticle.
     * Sets up event bus consumer for dummy requests.
     * 
     * @param startPromise Promise to signal completion of startup
     */
    @Override
    public void start(Promise<Void> startPromise) {
        try {
            // Register event bus consumer
            vertx.eventBus().consumer(ADDRESS, this::handleDummyRequest);
            
            System.out.println("ExampleVerticle started and listening on address: " + ADDRESS);
            startPromise.complete();
                
            // Register shutdown hook to gracefully shut down PerformanceTool
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down PerformanceTool...");
                PerformanceTool.shutdown();
            }));
        } catch (Exception e) {
            System.err.println("Error starting ExampleVerticle: " + e.getMessage());
            startPromise.fail(e);
        }
    }
    
    /**
     * Handles messages sent to the dummy endpoint.
     * If perf_tool header is present in the message, forwards request to PerformanceTool.
     * Otherwise, returns a dummy response.
     * 
     * @param message The message containing the request data
     */
    private void handleDummyRequest(Message<JsonArray> message) {
        try {
            JsonObject body = message.body().getJsonObject(0);
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            
            // Extract headers from the message if present
            if (body.containsKey("headers")) {
                JsonObject headerObj = body.getJsonObject("headers");
                headerObj.forEach(entry -> {
                    headers.add(entry.getKey(), entry.getValue().toString());
                });
            }
            
            // Check if this is a performance tool request
            String perfToolHeader = headers.get(PERF_TOOL_HEADER);
            
            if (perfToolHeader != null && !perfToolHeader.isEmpty()) {
                // This is a performance tool request - forward to PerformanceTool
                try {
                    // Get request body if present
                    Object requestBody = body.containsKey("body") ? body.getValue("body") : null;
                    
                    // Forward request to PerformanceTool
                    JsonObject result = PerformanceTool.getInstance().handleRequest(headers, requestBody);
                    
                    // Send the response back through the event bus
                    message.reply(new JsonObject()
                        .put("status", "success")
                        .put("contentType", "application/json")
                        .put("body", result));
                } catch (IllegalStateException e) {
                    // Handle concurrent test execution attempt
                    message.reply(new JsonObject()
                        .put("status", "error")
                        .put("statusCode", 409) // HTTP 409 Conflict
                        .put("message", e.getMessage()));
                } catch (Exception e) {
                    // Handle other errors
                    message.reply(new JsonObject()
                        .put("status", "error")
                        .put("statusCode", 500)
                        .put("message", "Error processing performance tool request: " + e.getMessage()));
                }
            } else {
                // Regular request - send dummy response
                message.reply(new JsonObject()
                    .put("status", "success")
                    .put("contentType", "text/plain")
                    .put("body", "This is a dummy endpoint. Add the 'perf_tool' header to use the performance tool."));
            }
        } catch (Exception e) {
            // Send error response for malformed message
            message.reply(new JsonObject()
                .put("status", "error")
                .put("statusCode", 400)
                .put("message", "Invalid message format: " + e.getMessage()));
        }
    }
    
    /**
     * Stops the ExampleVerticle.
     * 
     * @param stopPromise Promise to signal completion of shutdown
     */
    @Override
    public void stop(Promise<Void> stopPromise) {
        try {
            // No need to shut down PerformanceTool here as it's handled by the shutdown hook
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
            } else {
                System.err.println("Failed to deploy ExampleVerticle: " + result.cause().getMessage());
            }
        });
    }
} 