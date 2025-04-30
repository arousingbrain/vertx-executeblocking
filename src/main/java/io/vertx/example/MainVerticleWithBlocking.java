package io.vertx.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.core.eventbus.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

interface IAccountService {
    String getAccountNumber();
}

public class MainVerticleWithBlocking extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticleWithBlocking.class);
    private static final String BUS_ADDRESS = "test.address";
    private static final long TIMEOUT_MS = 2000;

    @Override
    public void start(Promise<Void> startPromise) {
        // Set up event bus consumer
        vertx.eventBus().consumer(BUS_ADDRESS, message -> {
            logger.info("Received message, starting operation");
            
            try {
                logger.info("Getting account service...");
                IAccountService accountService = getAccountService();
                if (accountService != null) {
                    // Set up consumer for handle method
                    vertx.eventBus().consumer(BUS_ADDRESS, this::handle);
                } else {
                    message.fail(500, "Account service not found");
                }
            } catch (Exception e) {
                message.fail(500, e.getMessage());
            }
        });

        startPromise.complete();
    }

    private IAccountService getAccountService() {
        return new IAccountService() {
            @Override
            public String getAccountNumber() {
                return "TEST123";
            }
        };
    }

    private void handle(Message<JsonObject> msg) {
        vertx.executeBlocking(performServiceCall(msg), false, handleResponse(msg, asyncResult.result()));

    }


    private Handler<Promise<GetSOAPResponseType>> performServiceCall(GetSOAPEntity getSOAPEntity,
        Message<JsonObject> message) throws Exception {

        return promise -> {
            try {
                BindingProvider bindingProvider = (BindingProvider) accountInquiryService;
                bindingProvider.getRequestContext().put(Header.HEADER_LIST, new HeaderList());
                promise.complete(accountInquiryService.getAccountInquiry(getSOAPEntity));
            } catch (Exception e) {
                promise.fail(e);
            }
        };
    }
} 