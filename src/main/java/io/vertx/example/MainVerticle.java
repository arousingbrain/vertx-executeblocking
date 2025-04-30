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
    /** Logger instance for this class */
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    
    /** Event bus address for receiving SOAP requests */
    private static final String busAddress = "test.address";
    
    // Circuit breaker configuration parameters
    /** Maximum number of failures before opening the circuit */
    private static final int MAX_FAILURES = 2;
    /** Time in milliseconds to wait before attempting to close the circuit */
    private static final int RESET_TIMEOUT_MS = 3000;
    /** Timeout in milliseconds for individual SOAP calls */
    private static final int CIRCUIT_BREAKER_TIMEOUT_MS = 1200;
    /** Simulated network latency in milliseconds */
    private static final int SIMULATED_LATENCY_MS = 4000;
    
    /** Circuit breaker instance for managing SOAP call resilience */
    private CircuitBreaker circuitBreaker;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        logger.info("Starting MainVerticle");
        
        // Configure circuit breaker with resilience parameters
        CircuitBreakerOptions options = new CircuitBreakerOptions()
            .setMaxFailures(MAX_FAILURES)          // Open circuit after N number of failures
            .setTimeout(CIRCUIT_BREAKER_TIMEOUT_MS) // timeout in seconds for SOAP calls
            .setResetTimeout(RESET_TIMEOUT_MS)     // Wait N seconds before attempting to close
            .setFallbackOnFailure(true);           // Enable fallback mechanism
            
        // Create and configure the circuit breaker with event handlers
        circuitBreaker = CircuitBreaker.create("soap-circuit-breaker", vertx, options)
            .openHandler(v -> logger.warn("Circuit breaker opened - SOAP service is failing"))
            .closeHandler(v -> logger.info("Circuit breaker closed - SOAP service is healthy"))
            .halfOpenHandler(v -> logger.info("Circuit breaker half-open - testing SOAP service"));
        
        // Register event bus consumer for SOAP requests
        vertx.eventBus().consumer(busAddress, this::handle);
        startPromise.complete();
    }

    private void handle(Message<JsonObject> msg) {
        long startTime = System.nanoTime();
        
        // Execute the SOAP call through the circuit breaker for resilience
        circuitBreaker.execute(promise -> {
            // Use a timer to simulate network latency without blocking
            vertx.setTimer(SIMULATED_LATENCY_MS, id -> {
                // Simulate SOAP response
                GetSOAPResponseType result = new GetSOAPResponseType("TEST123");
                promise.complete(result);
            });
        }).onComplete(asyncResult -> {
            long endTime = System.nanoTime();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            if (asyncResult.succeeded()) {
                logger.info("SOAP call completed in {} ms", durationMs);
                msg.reply(new JsonObject().put("response", asyncResult.result().toString()));
            } else {
                String errorMessage = "SOAP service timeout - Response took longer than " + CIRCUIT_BREAKER_TIMEOUT_MS + "ms";
                logger.error("{} (actual time: {} ms)", errorMessage, durationMs);
                msg.fail(504, errorMessage); // 504 Gateway Timeout
            }
        });
    }
}

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