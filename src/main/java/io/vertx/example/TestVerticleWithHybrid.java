package io.vertx.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Future;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TestVerticleWithHybrid extends AbstractVerticle {

    private IAccountInquiryService accountInquiryService;
    private CircuitBreaker circuitBreaker;
    private ExecutorService soapExecutorService;
    
    // Thread pool configuration
    // Core pool size matches CPU cores for baseline efficiency
    private static final int CORE_POOL_SIZE = 4;     // Matches k8s CPU cores
    // Max pool size allows for some growth during high load periods
    private static final int MAX_POOL_SIZE = 8;      // 2x core size for peak loads
    // Keep threads alive for a short time to handle bursty traffic
    private static final long KEEP_ALIVE_TIME = 1250; // 1.25 seconds to prevent thread blocked warnings
    
    // SLA requirement: SOAP service calls must complete within 1 second
    private static final int TIMEOUT_MS = 950;       // 950ms timeout to ensure total response under 1.5s
    
    // Adjusted circuit breaker settings for high load
    private static final int CIRCUIT_BREAKER_MAX_FAILURES = 300;  // Higher threshold for breaking
    private static final int CIRCUIT_BREAKER_RESET_MS = 5000;     // Faster recovery

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        // Initialize circuit breaker with improved options for high volume
        circuitBreaker = CircuitBreaker.create("account-inquiry-circuit-breaker", vertx,
            new CircuitBreakerOptions()
                .setMaxFailures(CIRCUIT_BREAKER_MAX_FAILURES)
                .setTimeout(TIMEOUT_MS)              // Circuit breaker will trigger if operation exceeds 950ms
                .setFallbackOnFailure(true)
                .setResetTimeout(CIRCUIT_BREAKER_RESET_MS)
        );

        // Create a dynamic thread pool with bounded queue
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.MILLISECONDS,                   // Using milliseconds for more precise control
            new LinkedBlockingQueue<>(100),          // Bounded queue to prevent memory issues
            new ThreadPoolExecutor.CallerRunsPolicy() // Provides backpressure when queue is full
        );
        
        // Allow core threads to timeout to prevent blocked thread warnings
        threadPool.allowCoreThreadTimeOut(true);     // Ensures no threads stay alive longer than necessary
        
        // Add a hook to regularly monitor the thread pool's queue size and active count
        vertx.setPeriodic(1000, id -> {
            if (threadPool.getQueue().size() > 50 || threadPool.getActiveCount() > MAX_POOL_SIZE - 1) {
                // Log high utilization warning - high queue or near max thread capacity
                System.out.println("Warning: ThreadPool high utilization - Queue: " + 
                    threadPool.getQueue().size() + ", Active threads: " + threadPool.getActiveCount());
            }
        });
        
        soapExecutorService = threadPool;

        try {
            accountInquiryService = getAccountInquiryService();
            if (accountInquiryService != null) {
                vertx.eventBus().consumer("account.inquiry", this::handle);
                startPromise.complete();
            } else {
                startPromise.fail("Account inquiry service not found");
            }
        } catch (Exception e) {
            startPromise.fail("FAILED TO GET ACCOUNT INQUIRY SERVICE: " + e.getMessage());
        }
    }

    private void handle(Message<JsonObject> msg) {
        // Start overall response timer to ensure total response time is under 1.5s
        final long startTime = System.currentTimeMillis();
        
        // Wrap the service call with circuit breaker
        circuitBreaker.<GetSOAPResponseType>execute(promise -> {
            GetSOAPEntity request = prepareRequest(msg.body());
            
            // Create a new Promise for the SOAP call
            Promise<GetSOAPResponseType> soapPromise = Promise.promise();
            
            // Use AtomicBoolean to track if the operation has completed
            AtomicBoolean completed = new AtomicBoolean(false);
            
            // Set up the timeout using Vert.x non-blocking timer - use a shorter timeout to enforce discipline
            // This timer acts as a safety net for the worker thread
            long timerId = vertx.setTimer(TIMEOUT_MS - 50, timeoutId -> {  // 50ms buffer for cleanup
                if (completed.compareAndSet(false, true)) {
                    soapPromise.tryFail(new TimeoutException("SOAP operation timed out after " + (TIMEOUT_MS-50) + " ms"));
                }
            });
            
            // Create a failsafe timer that will absolutely ensure 1.5s max response time
            long failsafeTimerId = vertx.setTimer(1450, timeoutId -> {  // 1.45s ultimate failsafe
                if (completed.compareAndSet(false, true)) {
                    soapPromise.tryFail(new TimeoutException("SOAP operation hard timeout after 1.45s"));
                    System.err.println("CRITICAL: Hard timeout triggered - operation exceeded 1.45s");
                }
            });

            // Use thread pool for SOAP calls with enhanced timeout handling
            soapExecutorService.submit(() -> {
                try {
                    if (completed.get()) {
                        // Already timed out, don't perform the operation
                        return;
                    }
                    
                    // Split the SOAP operation into two phases to avoid long-running operations
                    // Phase 1: Prepare the SOAP call (this should be quick)
                    Handler<Promise<GetSOAPResponseType>> handler = null;
                    try {
                        handler = performServiceCall(request);
                    } catch (Exception e) {
                        if (completed.compareAndSet(false, true)) {
                            vertx.cancelTimer(timerId);
                            vertx.cancelTimer(failsafeTimerId);
                            soapPromise.fail(e);
                        }
                        return;
                    }
                    
                    // Check if we've already timed out between phases
                    if (completed.get()) {
                        return;
                    }
                    
                    // Phase 2: Execute the actual SOAP call
                    try {
                        // Execute the handler on the worker thread
                        handler.handle(soapPromise);
                    } catch (Exception e) {
                        if (completed.compareAndSet(false, true)) {
                            vertx.cancelTimer(timerId);
                            vertx.cancelTimer(failsafeTimerId);
                            soapPromise.fail(e);
                        }
                    }
                } catch (Exception e) {
                    if (completed.compareAndSet(false, true)) {
                        vertx.cancelTimer(timerId);
                        vertx.cancelTimer(failsafeTimerId);
                        soapPromise.fail(e);
                    }
                }
            });
            
            // Set up completion handling
            soapPromise.future().onComplete(ar -> {
                // Cancel all timers regardless of success/failure
                vertx.cancelTimer(timerId);
                vertx.cancelTimer(failsafeTimerId);
                
                // Mark as completed
                completed.set(true);
                
                // Calculate elapsed time for monitoring
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > 900) {
                    // Log slow operations for monitoring
                    System.out.println("Slow SOAP operation detected: " + elapsed + "ms");
                }
                
                // Complete the circuit breaker promise
                if (ar.succeeded()) {
                    promise.complete(ar.result());
                } else {
                    promise.fail(ar.cause());
                }
            });
        })
        .onComplete(asyncResult -> {
            // Final response time check
            long totalTime = System.currentTimeMillis() - startTime;
            
            if (asyncResult.succeeded()) {
                handleResponse(msg, asyncResult.result()).handle(asyncResult);
            } else {
                String errorMessage = asyncResult.cause() instanceof TimeoutException ?
                    "Service call timed out" :
                    "Service temporarily unavailable";
                    
                msg.reply(new JsonObject()
                    .put("error", errorMessage)
                    .put("details", asyncResult.cause().getMessage()));
            }
            
            // Log any response that's close to our 1.5s threshold
            if (totalTime > 1300) {
                System.out.println("WARNING: Total response time approaching threshold: " + totalTime + "ms");
            }
        });
    }

    private GetSOAPEntity prepareRequest(JsonObject body) {
        GetSOAPEntity getSOAPEntity = new GetSOAPEntity();
        getSOAPEntity.setRequest(body.getString("request"));
        return getSOAPEntity;
    }

    private Handler<AsyncResult<GetSOAPResponseType>> handleResponse(Message<JsonObject> message, GetSOAPResponseType response) {
        return asyncResult -> {
            if (asyncResult.succeeded()) {
                ResponseType responseType = asyncResult.result().getResponse();
                StatusType status = responseType.getStatus();
                if (status.getCode() == 200) {
                    message.reply(responseType);
                } else {
                    message.reply(new JsonObject().put("error", responseType.getStatus().getMessage()));
                }
            } else {
                message.reply(new JsonObject().put("error", asyncResult.cause().getMessage()));
            }
        };
    }

    private Handler<Promise<GetSOAPResponseType>> performServiceCall(GetSOAPEntity getSOAPEntity) throws Exception {
        BindingProvider bindingProvider = (BindingProvider) accountInquiryService;
        bindingProvider.getRequestContext().put(Header.HEADER_LIST, new HeaderList());
        return accountInquiryService.getAccountInquiry(getSOAPEntity);
    }

    private IAccountInquiryService getAccountInquiryService() {
        AccountInquiryService accountInquiryService = new AccountInquiryService();
        // Get the port from the account inquiry service, which is the service endpoint
        IAccountInquiryService port = accountInquiryService.getPort();

        // NOT IMPLEMENTED - Get signature properties
        // NOT IMPLEMENTED - put signature properties into the SOAP message

        org.apache.cxf.endpoint.Client client = org.apache.cxf.endpoint.ClientProxy.getClient(port);
        
        // Increased timeouts for higher load scenarios
        HTTPConduit conduit = (HTTPConduit) client.getConduit();
        HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();
        // DO NOT CHANGE THESE VALUES
        httpClientPolicy.setConnectionTimeout(250);   // 250ms to establish connection
        httpClientPolicy.setReceiveTimeout(1000);     // 1000ms to receive response (total with connection = 1s)
        conduit.setClient(httpClientPolicy);

        final WSS4JOutInterceptor wssOut = new WSS4JOutInterceptor(properties);
        client.getOutInterceptors().add(wssOut);

        BindingProvider bindingProvider = (BindingProvider) port;
        bindingProvider.getRequestContext()
            .put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, "https://api.sandbox.bambora.com/api/v2/account-inquiry");
        // And so forth
        return port;
    }

    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {
        // Properly shutdown the executor service
        if (soapExecutorService != null) {
            soapExecutorService.shutdown();
        }
        stopPromise.complete();
    }
} 