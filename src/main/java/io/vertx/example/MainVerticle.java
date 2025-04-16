package io.vertx.example;

import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.eventbus.Message;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    private static final String busAddress = "test.address";
    
    // Circuit breaker settings
    private static final int MAX_FAILURES = 5;
    private static final int RESET_TIMEOUT_MS = 10000;
    private static final int CIRCUIT_BREAKER_TIMEOUT_MS = 3000;
    
    private CircuitBreaker circuitBreaker;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        logger.info("Starting MainVerticle");
        
        // Configure circuit breaker
        CircuitBreakerOptions options = new CircuitBreakerOptions()
            .setMaxFailures(MAX_FAILURES)
            .setTimeout(CIRCUIT_BREAKER_TIMEOUT_MS)
            .setResetTimeout(RESET_TIMEOUT_MS)
            .setFallbackOnFailure(true);
            
        circuitBreaker = CircuitBreaker.create("soap-circuit-breaker", vertx, options)
            .openHandler(v -> logger.warn("Circuit breaker opened"))
            .closeHandler(v -> logger.info("Circuit breaker closed"))
            .halfOpenHandler(v -> logger.info("Circuit breaker half-open"));
        
        vertx.eventBus().consumer(busAddress, this::handle);
        startPromise.complete();
    }

    private void handle(Message<JsonObject> msg) {
        long startTime = System.nanoTime();
        
        // Execute the SOAP call through the circuit breaker
        circuitBreaker.execute(promise -> {
            try {
                // Simulate SOAP call
                Thread.sleep(100); // Simulate network delay
                GetSOAPResponseType result = new GetSOAPResponseType("TEST123");
                promise.complete(result);
            } catch (Exception e) {
                promise.fail(e);
            }
        }).onComplete(asyncResult -> {
            long endTime = System.nanoTime();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            if (asyncResult.succeeded()) {
                logger.info("SOAP call completed in {} ms", durationMs);
                msg.reply(new JsonObject().put("response", asyncResult.result().toString()));
            } else {
                logger.error("SOAP call failed after {} ms with error", durationMs, asyncResult.cause());
                msg.fail(500, asyncResult.cause().getMessage());
            }
        });
    }
}

// A sample response type class
class GetSOAPResponseType {
    private final String content;

    public GetSOAPResponseType(String content) {
        this.content = content;
    }

    @Override
    public String toString() {
        return content;
    }
}