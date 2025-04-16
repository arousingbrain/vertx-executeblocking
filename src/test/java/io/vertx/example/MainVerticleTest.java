package io.vertx.example;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
public class MainVerticleTest {

  @BeforeEach
  void deployVerticle(Vertx vertx, VertxTestContext testContext) {
    vertx.deployVerticle(new MainVerticle(), testContext.succeeding(id -> testContext.completeNow()));
  }

  @Test
  void testSOAPServiceMock(Vertx vertx, VertxTestContext testContext) throws Throwable {
    // Create a test message
    JsonObject testMessage = new JsonObject().put("test", "data");

    // Send the message to the event bus
    vertx.eventBus().request("test.address", testMessage, reply -> {
      if (reply.succeeded()) {
        JsonObject response = (JsonObject) reply.result().body();
        assertTrue(response.containsKey("response"));
        String soapResponse = response.getString("response");
        assertTrue(soapResponse.contains("TEST123"));
        testContext.completeNow();
      } else {
        testContext.failNow(reply.cause());
      }
    });

    // Wait for the test to complete (with timeout)
    assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
  }
} 