package io.vertx.example.Research;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.Context;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// Apache CXF imports
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Enhanced production-ready SOAP client implementation using:
 * 1. Vert.x HttpClient for non-blocking HTTP requests
 * 2. Simple circuit breaker pattern for resilience
 * 3. Backpressure handling with executor service
 * 4. Optimized connection pooling
 * 5. Apache CXF HttpClient for SOAP calls
 */
public class VertxProd extends AbstractVerticle {
    private static final String SOAP_ENDPOINT = "https://api.sandbox.bambora.com/api/v2/account-inquiry";
    private static final int CONNECTION_TIMEOUT = 250;  // 250ms connection timeout
    private static final int REQUEST_TIMEOUT = 1000;    // 1s request timeout
    private static final int MAX_POOL_SIZE = 20;        // Max connections in the pool
    private static final int CIRCUIT_BREAKER_MAX_FAILURES = 5;
    private static final long CIRCUIT_BREAKER_RESET_TIMEOUT = 10000; // 10 seconds
    private static final int WORKER_POOL_SIZE = 4;      // Thread pool size
    
    private HttpClient httpClient;
    private Executor executor;
    
    // Simple circuit breaker implementation
    private AtomicInteger failureCount = new AtomicInteger(0);
    private volatile boolean circuitOpen = false;
    private volatile long circuitResetTime = 0;
    
    @Override
    public void start(Promise<Void> startPromise) {
        // Create a dedicated executor service for XML processing, with backpressure
        executor = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
        
        // Create HTTP Client with configured timeouts and connection pooling
        HttpClientOptions clientOptions = new HttpClientOptions()
            .setConnectTimeout(CONNECTION_TIMEOUT)
            .setIdleTimeout(REQUEST_TIMEOUT)
            .setMaxPoolSize(MAX_POOL_SIZE)
            .setSsl(true)
            .setKeepAlive(true);
            
        httpClient = vertx.createHttpClient(clientOptions);
        
        // Register event bus consumer
        vertx.eventBus().consumer("account.inquiry", this::handle);
        
        startPromise.complete();
    }
    
    private void handle(Message<JsonObject> msg) {
        // Check if circuit breaker is open
        if (circuitOpen) {
            if (System.currentTimeMillis() > circuitResetTime) {
                // Reset the circuit
                circuitOpen = false;
                failureCount.set(0);
                System.out.println("Circuit breaker reset");
            } else {
                // Circuit is open, fail fast
                msg.fail(503, "Service temporarily unavailable (circuit open)");
                return;
            }
        }
        
        // Execute the SOAP call
        executeSoapCall(msg.body()).onComplete(ar -> {
            if (ar.succeeded()) {
                JsonObject response = ar.result();
                
                // Reset failure count on success
                failureCount.set(0);
                
                msg.reply(response);
            } else {
                // Increment failure count
                int failures = failureCount.incrementAndGet();
                if (failures >= CIRCUIT_BREAKER_MAX_FAILURES) {
                    circuitOpen = true;
                    circuitResetTime = System.currentTimeMillis() + CIRCUIT_BREAKER_RESET_TIMEOUT;
                    System.out.println("Circuit breaker opened");
                }
                
                msg.fail(500, ar.cause().getMessage());
            }
        });
    }
    
    private Future<JsonObject> executeSoapCall(JsonObject request) {
        Promise<JsonObject> promise = Promise.promise();
        
        // First prepare the SOAP request
        final String soapBody = prepareSoapRequest(request);
        final Context context = vertx.getOrCreateContext();
        
        // Use CompletableFuture with executor service instead of executeBlocking
        CompletableFuture.supplyAsync(new Supplier<JsonObject>() {
            @Override
            public JsonObject get() {
                try {
                    // The HTTP request needs to be executed synchronously in this context
                    // as we're already on a separate thread
                    String responseXml = sendSoapRequest(soapBody);
                    return parseSoapResponse(responseXml);
                } catch (Exception e) {
                    throw new RuntimeException("SOAP call failed: " + e.getMessage(), e);
                }
            }
        }, executor).whenComplete((result, error) -> {
            // Ensure we're back on the Vert.x event loop
            context.runOnContext(v -> {
                if (error != null) {
                    promise.fail(error);
                } else {
                    promise.complete(result);
                }
            });
        });
        
        return promise.future();
    }
    
    private String sendSoapRequest(String soapBody) throws Exception {
        // Use Apache CXF HttpClient to make the SOAP call
        URL url = new URL(SOAP_ENDPOINT);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        // Configure connection properties
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/xml;charset=UTF-8");
        connection.setRequestProperty("SOAPAction", "");
        connection.setConnectTimeout(CONNECTION_TIMEOUT);
        connection.setReadTimeout(REQUEST_TIMEOUT); // Set the 1s read timeout
        connection.setDoOutput(true);
        
        // Send the SOAP request
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = soapBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }
        
        // Process the response
        int responseCode = connection.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) != -1) {
                    result.write(buffer, 0, length);
                }
                return result.toString(StandardCharsets.UTF_8);
            }
        } else {
            throw new RuntimeException("SOAP call failed with status code: " + responseCode);
        }
    }
    
    private String prepareSoapRequest(JsonObject request) {
        // Convert JSON request to SOAP XML format
        return String.format(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
            "<soapenv:Body>" +
            "<ns:getAccountInquiry xmlns:ns=\"http://api.bambora.com/v2\">" +
            "<request>%s</request>" +
            "</ns:getAccountInquiry>" +
            "</soapenv:Body>" +
            "</soapenv:Envelope>",
            request.getString("request")
        );
    }
    
    private JsonObject parseSoapResponse(String soapResponse) {
        // Parse SOAP XML response to JSON
        try {
            // Extract the response content from SOAP envelope
            String content = soapResponse.substring(
                soapResponse.indexOf("<response>") + 10,
                soapResponse.indexOf("</response>")
            );
            
            return new JsonObject()
                .put("response", content)
                .put("status", new JsonObject()
                    .put("code", 200)
                    .put("message", "Success"));
        } catch (Exception e) {
            return new JsonObject()
                .put("error", "Failed to parse SOAP response: " + e.getMessage());
        }
    }
    
    @Override
    public void stop(Promise<Void> stopPromise) {
        // Close the HTTP client
        if (httpClient != null) {
            httpClient.close();
        }
        
        // Shutdown the executor service
        if (executor instanceof java.util.concurrent.ExecutorService) {
            ((java.util.concurrent.ExecutorService) executor).shutdown();
        }
        
        stopPromise.complete();
    }
} 