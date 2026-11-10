package com.example.stockagent.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Supplier;

public final class Retry {

    private static final Logger log = LoggerFactory.getLogger(Retry.class);

    private Retry() {
    }

    /**
     * Runs an idempotent GET and repeats it once if the connection fails before a response arrives.
     *
     * <p>HTTP clients keep connections open for reuse, and a server may close an idle one at the
     * same moment the client picks it up again, which surfaces as "Connection reset". A second
     * attempt gets a fresh connection. Responses of any status are never retried here.
     */
    public static <T> T onceOnConnectionFailure(Supplier<T> idempotentGet) {
        try {
            return idempotentGet.get();
        } catch (ResourceAccessException e) {
            log.info("Connection failed ({}); retrying once", e.getMostSpecificCause().getMessage());
            return idempotentGet.get();
        }
    }
}
