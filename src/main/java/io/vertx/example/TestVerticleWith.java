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
import java.util.concurrent.TimeoutException;

public class TestVerticleWith extends AbstractVerticle {

    private IAccountInquiryService accountInquiryService;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
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

    private final ExecutorService soapExecutorService = Executors.newFixedThreadPool(5);
    private void handle(Message<JsonObject> msg) {
        soapExecutorService.submit(() -> {
            Promise<GetSOAPResponseType> soapPromise = Promise.promise();
            Handler<Promise<GetSOAPResponseType>> handler = performServiceCall(prepareRequest(msg.body()));
            handler.handle(soapPromise);

            try {
                GetSOAPResponseType response = 
                    soapPromise.future().toCompletionStage().toCompletableFuture()
                        .get(60L, java.util.concurrent.TimeUnit.SECONDS);
                AsyncResult<GetSOAPResponseType> asyncResult = Future.succeededFuture(response);
                vertx.runOnContext(v -> handleResponse(msg, asyncResult));
            } catch (TimeoutException te) {
                AsyncResult<GetSOAPResponseType> asyncResult = 
                    Future.failedFuture(new RuntimeException("SOAP operation timed out", te));
                vertx.runOnContext(v -> handleResponse(msg, asyncResult).handle(asyncResult));
            } catch (Exception e) {
                AsyncResult<GetSOAPResponseType> asyncResult = 
                    Future.failedFuture(new RuntimeException("SOAP call exception", e));
                vertx.runOnContext(v -> handleResponse(msg, asyncResult).handle(asyncResult));
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