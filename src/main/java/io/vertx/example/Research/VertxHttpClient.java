package io.vertx.example.Research;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Context;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.ClientProxy;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor;
import org.apache.cxf.binding.soap.SoapBindingConstants;

import javax.xml.ws.BindingProvider;
import java.util.HashMap;
import java.util.Map;

public class VertxHttpClient extends AbstractVerticle {
    private static final String SOAP_ENDPOINT = "https://api.sandbox.bambora.com/api/v2/account-inquiry";
    private static final int CONNECTION_TIMEOUT = 250;  // 250ms connection timeout
    private static final int REQUEST_TIMEOUT = 1000;    // 1s request timeout
    private Client soapClient;
    private IAccountInquiryService port;

    @Override
    public void start(Promise<Void> startPromise) {
        try {
            // Initialize SOAP client with CXF
            AccountInquiryService service = new AccountInquiryService();
            port = service.getPort();
            
            // Get the CXF client
            soapClient = ClientProxy.getClient(port);
            
            // Configure HTTP conduit with timeout policies
            HTTPConduit conduit = (HTTPConduit) soapClient.getConduit();
            HTTPClientPolicy httpClientPolicy = new HTTPClientPolicy();
            
            // Set connection timeout
            httpClientPolicy.setConnectionTimeout(CONNECTION_TIMEOUT);
            
            // Set read timeout
            httpClientPolicy.setReceiveTimeout(REQUEST_TIMEOUT);
            
            // Additional performance settings
            httpClientPolicy.setAllowChunking(false);  // Disable chunking for better performance
            httpClientPolicy.setAutoRedirect(true);    // Allow automatic redirects
            
            // Set the policy
            conduit.setClient(httpClientPolicy);
            
            // Configure binding provider
            BindingProvider bindingProvider = (BindingProvider) port;
            bindingProvider.getRequestContext()
                .put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, SOAP_ENDPOINT);
            
            // Set SOAP action
            bindingProvider.getRequestContext()
                .put(SoapBindingConstants.SOAP_ACTION, "");
            
            // Register event bus consumer
            vertx.eventBus().consumer("account.inquiry", this::handle);
            startPromise.complete();
        } catch (Exception e) {
            startPromise.fail("Failed to initialize SOAP client: " + e.getMessage());
        }
    }

    private void handle(Message<JsonObject> msg) {
        Promise<GetSOAPResponseType> promise = Promise.promise();
        executeSoapCall(msg, promise);
        
        promise.future().onComplete(ar -> {
            if (ar.succeeded()) {
                msg.reply(new JsonObject().put("response", ar.result()));
            } else {
                msg.fail(500, ar.cause().getMessage());
            }
        });
    }
    
    private void executeSoapCall(Message<JsonObject> msg, Promise<GetSOAPResponseType> promise) {
        JsonObject request = msg.body();
        
        // Prepare SOAP request
        final GetSOAPEntity soapRequest = prepareRequest(request);
        
        // Direct SOAP call approach - will run on event loop
        // Note: In a real implementation, you'd want to use a worker verticle 
        // or other non-blocking approach for this potentially blocking call
        try {
            // Make the SOAP call (this would normally be potentially blocking)
            GetSOAPResponseType response = port.getAccountInquiry(soapRequest);
            promise.complete(response);
        } catch (Exception e) {
            promise.fail(new RuntimeException("SOAP call failed: " + e.getMessage(), e));
        }
    }

    private GetSOAPEntity prepareRequest(JsonObject request) {
        GetSOAPEntity soapRequest = new GetSOAPEntity();
        soapRequest.setRequest(request.getString("request"));
        return soapRequest;
    }

    private JsonObject convertResponseToJson(GetSOAPResponseType response) {
        ResponseType responseType = response.getResponse();
        StatusType status = responseType.getStatus();
        
        return new JsonObject()
            .put("response", responseType.getContent())
            .put("status", new JsonObject()
                .put("code", status.getCode())
                .put("message", status.getMessage()));
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (soapClient != null) {
            soapClient.destroy();
        }
        
        stopPromise.complete();
    }
} 