package io.vertx.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
    private static final String busAddress = "test.address";

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("Starting MainVerticle");
        vertx.eventBus().consumer(busAddress, this::handle);
        startPromise.complete();
    }

    private void handle(Message<JsonObject> msg) {
        logger.info("Received message in non-blocking verticle: {}", msg.body());
        // Create a mock SOAP response
        String soapResponse = "TEST123";
        
        msg.reply(new JsonObject().put("response", soapResponse));
    }
}

// A sample response type class
class GetSOAPResponseType {
  private final String content;

  public GetSOAPResponseType(String content) {
    this.content = content;
  }

  @Override
  public String toString() {
    return content;
  }
}