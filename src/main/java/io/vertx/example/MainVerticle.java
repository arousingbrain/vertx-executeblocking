package io.vertx.example;

import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.eventbus.Message;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    private static final String busAddress = "test.address";
    private static final int SOAP_TIMEOUT_SECONDS = 2;
    private static final int SOAP_SIMULATED_DELAY_MS = 2100;
    
    // Circuit breaker settings
    private static final int MAX_FAILURES = 5;
    private static final int RESET_TIMEOUT_MS = 10000;
    private static final int CIRCUIT_BREAKER_TIMEOUT_MS = 3000;
    
    private CircuitBreaker circuitBreaker;
    private final ScheduledExecutorService soapExecutor = Executors.newScheduledThreadPool(5);

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
            // Create a promise for the SOAP call
            Promise<GetSOAPResponseType> soapPromise = Promise.promise();
            AtomicBoolean promiseCompleted = new AtomicBoolean(false);
            
            // Submit the SOAP call to the executor
            Thread soapThread = new Thread(() -> {
                try {
                    // Invoke the SOAP call
                    Handler<Promise<GetSOAPResponseType>> soapCallHandler = performSOAPCall(msg);
                    soapCallHandler.handle(soapPromise);
                    
                    // Wait for the result
                    GetSOAPResponseType result = soapPromise.future()
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get();
                    
                    if (promiseCompleted.compareAndSet(false, true)) {
                        promise.complete(result);
                    }
                } catch (Exception e) {
                    if (promiseCompleted.compareAndSet(false, true)) {
                        promise.fail(e);
                    }
                }
            });
            
            // Start the SOAP thread
            soapThread.start();
            
            // Schedule a timeout task
            soapExecutor.schedule(() -> {
                if (!soapPromise.future().isComplete()) {
                    logger.warn("Dropping SOAP call - operation taking too long");
                    soapThread.interrupt();
                    if (promiseCompleted.compareAndSet(false, true)) {
                        promise.fail(new TimeoutException("SOAP operation dropped - taking too long"));
                    }
                }
            }, SOAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
        }).onComplete(asyncResult -> {
            long endTime = System.nanoTime();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            if (asyncResult.succeeded()) {
                logger.info("SOAP call completed in {} ms", durationMs);
                msg.reply(new JsonObject().put("response", asyncResult.result().toString()));
            } else {
                if (asyncResult.cause() instanceof TimeoutException) {
                    logger.error("SOAP call timed out after {} ms (timeout was {} seconds)", durationMs, SOAP_TIMEOUT_SECONDS);
                } else {
                    logger.error("SOAP call failed after {} ms with error", durationMs, asyncResult.cause());
                }
                msg.fail(500, asyncResult.cause().getMessage());
            }
        });
    }

    private Handler<Promise<GetSOAPResponseType>> performSOAPCall(Message<JsonObject> msg) {
        return promise -> {
            long startTime = System.nanoTime();
            try {
                // Simulate blocking SOAP call
                long remainingMs = SOAP_SIMULATED_DELAY_MS;
                while (remainingMs > 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("SOAP call was interrupted due to timeout");
                    }
                    long sleepMs = Math.min(remainingMs, 100);
                    Thread.sleep(sleepMs);
                    remainingMs -= sleepMs;
                }
                
                promise.complete(new GetSOAPResponseType("TEST123"));
                
                long endTime = System.nanoTime();
                long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                logger.info("SOAP operation completed in {} ms", durationMs);
            } catch (InterruptedException e) {
                long endTime = System.nanoTime();
                long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                logger.warn("SOAP call was interrupted after {} ms", durationMs);
                promise.fail(e);
            } catch (Exception e) {
                long endTime = System.nanoTime();
                long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
                logger.error("Error in performSOAPCall after {} ms", durationMs, e);
                promise.fail(e);
            }
        };
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