package io.vertx.example;

import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

public class VertxApp {
    private static final Logger logger = LoggerFactory.getLogger(VertxApp.class);
    private static final int MESSAGE_INTERVAL_MS = 1000; // Send message every second
    private static final long MAX_WORKER_EXECUTE_TIME_NS = TimeUnit.SECONDS.toNanos(2); // 2 seconds (in nanoseconds) max worker time
    private static final int BLOCKED_THREAD_CHECK_INTERVAL_MS = 2000; // Check every second
    
    // Different ports for each Vertx instance
    private static final int MAIN_VERTICLE_PORT = 8091;
    private static final int BLOCKING_VERTICLE_PORT = 8092;

    public static void main(String[] args) {
        // Configure Vertx with blocked thread checking
        VertxOptions options = new VertxOptions()
            .setBlockedThreadCheckInterval(BLOCKED_THREAD_CHECK_INTERVAL_MS)
            .setMaxWorkerExecuteTime(MAX_WORKER_EXECUTE_TIME_NS)
            .setWorkerPoolSize(2); // Limited worker pool to increase chance of blocking

        // Create first Vertx instance for MainVerticle
        Vertx mainVertx = Vertx.vertx(options);
        
        // Create second Vertx instance for MainVerticleWithBlocking
        Vertx blockingVertx = Vertx.vertx(options);

        // Deploy MainVerticle on first instance
        mainVertx.deployVerticle(new MainVerticle(), new DeploymentOptions().setConfig(new JsonObject().put("port", MAIN_VERTICLE_PORT)), res -> {
            if (res.succeeded()) {
                logger.info("MainVerticle deployed successfully on port {}", MAIN_VERTICLE_PORT);
                
                // Start sending messages periodically to MainVerticle
                mainVertx.setPeriodic(MESSAGE_INTERVAL_MS, id -> {
                    logger.info("Sending message to MainVerticle");
                    mainVertx.eventBus().send("test.address", "test message");
                });
            } else {
                logger.error("Failed to deploy MainVerticle", res.cause());
            }
        });

        // Deploy MainVerticleWithBlocking on second instance
        blockingVertx.deployVerticle(new MainVerticleWithBlocking(), new DeploymentOptions().setConfig(new JsonObject().put("port", BLOCKING_VERTICLE_PORT)), res -> {
            if (res.succeeded()) {
                logger.info("MainVerticleWithBlocking deployed successfully on port {}", BLOCKING_VERTICLE_PORT);
                
                // Start sending messages periodically to MainVerticleWithBlocking
                blockingVertx.setPeriodic(MESSAGE_INTERVAL_MS, id -> {
                    logger.info("Sending message to MainVerticleWithBlocking");
                    blockingVertx.eventBus().send("test.address", "test message");
                });
            } else {
                logger.error("Failed to deploy MainVerticleWithBlocking", res.cause());
            }
        });

        // Add shutdown hook to properly close both Vertx instances
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");
            Promise<Void> mainClosePromise = Promise.promise();
            Promise<Void> blockingClosePromise = Promise.promise();
            
            mainVertx.close(mainClosePromise);
            blockingVertx.close(blockingClosePromise);
            
            CompositeFuture.all(mainClosePromise.future(), blockingClosePromise.future())
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        logger.info("Both Vertx instances closed successfully");
                    } else {
                        logger.error("Error closing Vertx instances", ar.cause());
                    }
                });
        }));
    }
} 