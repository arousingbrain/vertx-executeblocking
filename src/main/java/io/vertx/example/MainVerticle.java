package io.vertx.example;

import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.eventbus.Message;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;

/**
 * MainVerticle class that demonstrates the use of Vert.x Circuit Breaker pattern
 * for handling SOAP service calls with resilience and fault tolerance.
 * 
 * This class implements a Vert.x verticle that:
 * 1. Sets up a circuit breaker to protect against SOAP service failures
 * 2. Listens for messages on the event bus
 * 3. Processes SOAP requests with built-in failure handling
 * 4. Provides response timing metrics
 * 
 * The circuit breaker pattern helps prevent cascading failures by:
 * - Opening the circuit after a certain number of failures
 * - Providing a fallback mechanism when the circuit is open
 * - Automatically attempting to close the circuit after a timeout
 */
public class MainVerticle extends AbstractVerticle {
    /** Logger instance for this class */
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    
    /** Event bus address for receiving SOAP requests */
    private static final String busAddress = "test.address";
    
    // Circuit breaker configuration parameters
    /** Maximum number of failures before opening the circuit */
    private static final int MAX_FAILURES = 5;
    /** Time in milliseconds to wait before attempting to close the circuit */
    private static final int RESET_TIMEOUT_MS = 10000;
    /** Timeout in milliseconds for individual SOAP calls */
    private static final int CIRCUIT_BREAKER_TIMEOUT_MS = 3000;
    
    /** Circuit breaker instance for managing SOAP call resilience */
    private CircuitBreaker circuitBreaker;

    /**
     * Initializes the verticle by:
     * 1. Setting up the circuit breaker with configured parameters
     * 2. Registering an event bus consumer for SOAP requests
     * 
     * @param startPromise Promise to be completed when initialization is done
     * @throws Exception if initialization fails
     */
    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        logger.info("Starting MainVerticle");
        
        // Configure circuit breaker with resilience parameters
        CircuitBreakerOptions options = new CircuitBreakerOptions()
            .setMaxFailures(MAX_FAILURES)          // Open circuit after 5 failures
            .setTimeout(CIRCUIT_BREAKER_TIMEOUT_MS) // 3 second timeout for SOAP calls
            .setResetTimeout(RESET_TIMEOUT_MS)     // Wait 10 seconds before attempting to close
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

    /**
     * Handles incoming SOAP requests by:
     * 1. Measuring request duration
     * 2. Executing the SOAP call through the circuit breaker
     * 3. Processing the response or handling failures
     * 
     * @param msg The incoming event bus message containing the SOAP request
     */
    private void handle(Message<JsonObject> msg) {
        long startTime = System.nanoTime();
        
        // Execute the SOAP call through the circuit breaker for resilience
        circuitBreaker.execute(promise -> {
            try {
                // Simulate SOAP call with network delay
                Thread.sleep(100); // Simulate network latency
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

/**
 * Simple response type class representing a SOAP response.
 * In a real implementation, this would contain the actual SOAP response structure.
 */
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