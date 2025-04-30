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
import java.util.concurrent.TimeoutException;

public class TestVerticleWithCircuitBreaker extends AbstractVerticle {

    private IAccountInquiryService accountInquiryService;
    private CircuitBreaker circuitBreaker;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        // Initialize circuit breaker with appropriate options
        circuitBreaker = CircuitBreaker.create("account-inquiry-circuit-breaker", vertx,
            new CircuitBreakerOptions()
                // Maximum number of failures before opening the circuit
                // When this number is reached, the circuit opens and all requests fail fast
                // This prevents cascading failures and allows the system to recover
                .setMaxFailures(5)
                
                // Maximum time in milliseconds to wait for a response
                // If the operation takes longer than this, it's considered a failure
                // This prevents hanging requests from blocking the system
                .setTimeout(60000)
                
                // Whether to call the fallback handler when the circuit is open
                // When true, failed requests will get a fallback response instead of an error
                // This provides graceful degradation of service
                .setFallbackOnFailure(true)
                
                // Time in milliseconds to wait before attempting to reset the circuit
                // After this time, the circuit will transition to half-open state
                // This allows the system to test if the underlying service has recovered
                .setResetTimeout(30000)
        );

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
        // Wrap the service call with circuit breaker
        circuitBreaker.<GetSOAPResponseType>execute(promise -> {
            GetSOAPEntity request = prepareRequest(msg.body());
            
            // Use executeBlocking but ensure we have a timeout for the worker thread
            vertx.executeBlocking(future -> {
                try {
                    // Create a new Promise for the SOAP call
                    Promise<GetSOAPResponseType> soapPromise = Promise.promise();
                    
                    // Get the handler and set it up
                    Handler<Promise<GetSOAPResponseType>> handler = performServiceCall(request);
                    
                    // Set up the completion handler first - this ensures we handle the response
                    soapPromise.future().onComplete(soapResult -> {
                        if (soapResult.succeeded()) {
                            future.complete(soapResult.result());
                        } else {
                            future.fail(soapResult.cause());
                        }
                    });
                    
                    // Now set up the timeout AFTER the completion handler
                    // This is thread-safe because Vert.x event loop is single-threaded
                    long timerId = vertx.setTimer(60000, timeoutId -> {
                        if (!soapPromise.future().isComplete()) {
                            // Force fail the promise if it's not complete after timeout
                            soapPromise.tryFail(new TimeoutException("SOAP operation timed out after 60 seconds"));
                            
                            // Also directly fail the future to ensure the worker thread is released
                            if (!future.future().isComplete()) {
                                future.fail(new TimeoutException("Worker thread released after timeout"));
                            }
                        }
                    });
                    
                    // Add a handler to cancel the timer when the promise completes
                    // We use a separate handler to avoid race conditions
                    soapPromise.future().onComplete(ignored -> vertx.cancelTimer(timerId));
                    
                    // Now execute the handler - this is where the actual SOAP call happens
                    handler.handle(soapPromise);
                    
                } catch (Exception e) {
                    future.fail(e);
                }
            }, 
            // Use ordered=false for better performance (we don't need execution order guarantees)
            false, 
            // This is the final promise to the circuit breaker
            promise);
        })
        .onComplete(asyncResult -> {
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
        
        // Set CXF timeouts
        HTTPConduit conduit = (HTTPConduit) client.getConduit();
        HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();
        httpClientPolicy.setConnectionTimeout(30000); // Maximum time to establish initial connection with server
        httpClientPolicy.setReceiveTimeout(60000);    // Maximum time to wait for response after connection is established
        conduit.setClient(httpClientPolicy);

        final WSS4JOutInterceptor wssOut = new WSS4JOutInterceptor(properties);
        client.getOutInterceptors().add(wssOut);

        BindingProvider bindingProvider = (BindingProvider) port;
        bindingProvider.getRequestContext()
            .put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, "https://api.sandbox.bambora.com/api/v2/account-inquiry");
        // And so forth
        return port;
    }
} 