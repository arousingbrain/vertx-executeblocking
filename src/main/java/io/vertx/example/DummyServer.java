package io.vertx.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dummy HTTP server that responds with a 200 status code after a 100ms delay.
 * Used for testing the PerfApp load testing application.
 */
public class DummyServer {
    private static final int PORT = 8123;
    private static final int DELAY_MS = 100;
    private static final int THREADS = 50;
    
    private static final AtomicInteger requestCounter = new AtomicInteger(0);
    private static final AtomicInteger activeRequests = new AtomicInteger(0);
    
    public static void main(String[] args) throws IOException {
        System.out.println("Starting dummy server on port " + PORT);
        
        // Create an HTTP server that listens on port 8123
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new DelayedResponseHandler());
        
        // Use a fixed thread pool to handle requests
        server.setExecutor(Executors.newFixedThreadPool(THREADS));
        server.start();
        
        System.out.println("Dummy server is running. Press Ctrl+C to stop.");
        
        // Start a monitoring thread to display stats
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    int total = requestCounter.get();
                    int active = activeRequests.get();
                    System.out.printf("Stats: %d total requests, %d active requests%n", total, active);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }
    
    /**
     * HTTP handler that introduces a delay and returns a 200 OK response.
     */
    static class DelayedResponseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            activeRequests.incrementAndGet();
            requestCounter.incrementAndGet();
            
            try {
                // Simulate processing delay
                Thread.sleep(DELAY_MS);
                
                // Send response headers
                String response = "OK";
                exchange.sendResponseHeaders(200, response.length());
                
                // Write response body
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // If interrupted, still send a response
                exchange.sendResponseHeaders(500, 0);
                exchange.close();
            } finally {
                activeRequests.decrementAndGet();
            }
        }
    }
} 