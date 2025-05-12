package io.vertx.example.Research;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class VertxThreading extends AbstractVerticle {
    private static final String SOAP_WORKER_ADDRESS = "soap.worker";
    private static final int WORKER_POOL_SIZE = 4;
    private Executor executor;

    @Override
    public void start(Promise<Void> startPromise) {
        // Create a dedicated thread pool for SOAP operations
        executor = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
        
        // Deploy the worker verticle with worker configuration
        DeploymentOptions options = new DeploymentOptions()
            .setWorker(true)  // This marks it as a worker verticle
            .setWorkerPoolSize(WORKER_POOL_SIZE)
            .setMaxWorkerExecuteTime(1000L * 1000000); // 1 second in nanoseconds
        
        vertx.deployVerticle(new SoapWorkerVerticle(executor), options, ar -> {
            if (ar.succeeded()) {
                // Register event bus consumer
                vertx.eventBus().consumer("account.inquiry", this::handle);
                startPromise.complete();
            } else {
                startPromise.fail(ar.cause());
            }
        });
    }

    private void handle(Message<JsonObject> msg) {
        JsonObject request = msg.body();
        
        // Send request to worker verticle without blocking the event loop
        vertx.eventBus().request(SOAP_WORKER_ADDRESS, request, reply -> {
            if (reply.succeeded()) {
                msg.reply(reply.result().body());
            } else {
                msg.reply(new JsonObject()
                    .put("error", "Service call failed: " + reply.cause().getMessage()));
            }
        });
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (executor instanceof java.util.concurrent.ExecutorService) {
            ((java.util.concurrent.ExecutorService) executor).shutdown();
        }
        stopPromise.complete();
    }

    // Inner worker verticle class - this runs on a worker thread
    private static class SoapWorkerVerticle extends AbstractVerticle {
        private final Executor executor;
        private static final String SOAP_ENDPOINT = "https://api.sandbox.bambora.com/api/v2/account-inquiry";

        public SoapWorkerVerticle(Executor executor) {
            this.executor = executor;
        }

        @Override
        public void start(Promise<Void> startPromise) {
            // Register worker consumer
            vertx.eventBus().consumer(SOAP_WORKER_ADDRESS, this::handle);
            startPromise.complete();
        }

        private void handle(Message<JsonObject> msg) {
            final JsonObject request = msg.body();
            final Context context = vertx.getOrCreateContext();
            
            // Use CompletableFuture with explicit Supplier for JDK 8/11 compatibility
            CompletableFuture.supplyAsync(new Supplier<JsonObject>() {
                @Override
                public JsonObject get() {
                    try {
                        // Simulate SOAP call - these are potentially blocking operations
                        String soapRequest = prepareSoapRequest(request);
                        String soapResponse = executeSoapCall(soapRequest);
                        return parseSoapResponse(soapResponse);
                    } catch (Exception e) {
                        throw new RuntimeException("SOAP call failed: " + e.getMessage(), e);
                    }
                }
            }, executor).whenComplete((result, error) -> {
                // Ensure we're back on the Vert.x event loop
                context.runOnContext(v -> {
                    if (error != null) {
                        msg.fail(500, error.getMessage());
                    } else {
                        msg.reply(result);
                    }
                });
            });
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

        private String executeSoapCall(String soapRequest) {
            // In a real implementation, this would make the actual SOAP call
            // For this example, we'll just return a mock response
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                   "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                   "<soapenv:Body>" +
                   "<ns:getAccountInquiryResponse xmlns:ns=\"http://api.bambora.com/v2\">" +
                   "<response>Mock SOAP Response</response>" +
                   "</ns:getAccountInquiryResponse>" +
                   "</soapenv:Body>" +
                   "</soapenv:Envelope>";
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
    }
} 