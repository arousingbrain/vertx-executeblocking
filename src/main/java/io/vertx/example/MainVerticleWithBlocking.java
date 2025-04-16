package io.vertx.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

public class MainVerticleWithBlocking extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticleWithBlocking.class);
    private static final String BUS_ADDRESS = "test.address";
    private static final long TIMEOUT_MS = 3000;

    @Override
    public void start(Promise<Void> startPromise) {
        // Set up event bus consumer
        vertx.eventBus().consumer(BUS_ADDRESS, message -> {
            logger.info("Received message, starting blocking operation");
            
            // Execute blocking operation that simulates a hanging SOAP service call
            vertx.executeBlocking(future -> {
                try {
                    logger.info("Simulating MainVerticleWithBlocking service call that never responds...");
                    AtomicReference<String> soapResponse = new AtomicReference<>("TEST321");
                    
                    // Create a future that will be completed by the timer
                    CompletableFuture<String> timerFuture = new CompletableFuture<>();
                    
                    // Set up a timer to complete the future after timeout
                    vertx.setTimer(TIMEOUT_MS, id -> {
                        soapResponse.set("TEST123");
                        timerFuture.complete(soapResponse.get());
                    });
                    
                    // Wait for the timer to complete
                    String result = timerFuture.get();
                    future.complete(result);
                    
                } catch (Exception e) {
                    logger.error("Thread interrupted", e);
                    future.fail(e);
                }
            }, false, res -> {
                if (res.succeeded()) {
                    message.reply(new JsonObject().put("response", res.result()));
                } else {
                    logger.error("SOAP service call failed", res.cause());
                }
            });
        });

        startPromise.complete();
    }
} 