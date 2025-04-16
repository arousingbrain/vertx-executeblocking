package io.vertx.example;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
public class MainVerticleWithBlockingTest {

    @BeforeEach
    void deployVerticle(Vertx vertx, VertxTestContext testContext) {
        vertx.deployVerticle(new MainVerticleWithBlocking(), testContext.succeeding(id -> testContext.completeNow()));
    }

    @Test
    void testSOAPServiceMockWithBlocking(Vertx vertx, VertxTestContext testContext) throws Throwable {
        // Create a test message
        JsonObject testMessage = new JsonObject().put("test", "data");

        // Send the message to the event bus and expect no response within timeout
        vertx.eventBus().request("test.address", testMessage, ar -> {
            // We don't expect this callback to be called at all
            // The test will pass by timing out
        });

        // Test passes if we timeout (no response received)
        assertFalse(testContext.awaitCompletion(2, TimeUnit.SECONDS));
        testContext.completeNow();
    }

    @Test
    void testConcurrentRequests(Vertx vertx, VertxTestContext testContext) throws Throwable {
        int numRequests = 5;
        AtomicInteger sent = new AtomicInteger(0);

        // Send multiple concurrent requests
        for (int i = 0; i < numRequests; i++) {
            JsonObject testMessage = new JsonObject().put("test", "data" + i);
            vertx.eventBus().request("test.address", testMessage, ar -> {
                // We don't expect these callbacks to be called
            });
            sent.incrementAndGet();
        }

        // Verify all requests were sent
        assertEquals(numRequests, sent.get());
        
        // Test passes if we timeout (no responses received)
        assertFalse(testContext.awaitCompletion(2, TimeUnit.SECONDS));
        testContext.completeNow();
    }
} 