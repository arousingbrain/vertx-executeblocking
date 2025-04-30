package io.vertx.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

public class TestVerticle extends AbstractVerticle {

    private IAccountInquiryService accountInquiryService;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        vertx.executeBlocking(future -> {
            accountInquiryService = getAccountInquiryService();
            if (accountInquiryService != null) {
                future.complete();
            } else {
                future.fail("Account inquiry service not found");
            }
        }, future2 -> {
            if (future2.succeeded()) {
                vertx.eventBus().consumer("account.inquiry", this::handle);
            } else {
                startPromise.fail("FAILED TO GET ACCOUNT INQUIRY SERVICE");
            }
        });
    }

    private void handle(Message<JsonObject> msg) {
        vertx.executeBlocking(performServiceCall(prepareRequest(msg.body()), msg), false, handleResponse(msg));
       
    }

    private GetSOAPEntity prepareRequest(JsonObject body) {
        GetSOAPEntity getSOAPEntity = new GetSOAPEntity();
        getSOAPEntity.setRequest(body.getString("request"));
        return getSOAPEntity;
    }

    private Handler<Promise<GetSOAPResponseType>> handleResponse(Message<JsonObject> message) {
        return promise -> {
            if (promise.succeeded()) {
                GetSOAPResponseType response = promise.result();
                ResponseType responseType = response.getResponse();
                StatusType status = responseType.getStatus();
                if (status.getCode() == 200) {
                    message.reply(responseType);
                } else {
                    message.reply(new JsonObject().put("error", responseType.getStatus().getMessage()));
                }
            }
        };
    }

    private Handler<Promise<GetSOAPResponseType>> performServiceCall(GetSOAPEntity getSOAPEntity, Message<JsonObject> message) {
        return promise -> {
            try {
                BindingProvider bindingProvider = (BindingProvider) accountInquiryService;
                try {
                    bindingProvider.getRequestContext()
                        .put(Header.HEADER_LIST, new HeaderList());
                    promise.complete(accountInquiryService.getAccountInquiry(getSOAPEntity));
                } catch (Exception e) {
                    promise.fail(e);
                }

                GetSOAPResponseType response = accountInquiryService.getAccountInquiry(getSOAPEntity);
                promise.complete(response);
            } catch (Exception e) {
                promise.fail(e);
            }
        };
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
        httpClientPolicy.setConnectionTimeout(30000); // 30 seconds
        httpClientPolicy.setReceiveTimeout(60000); // 60 seconds
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
