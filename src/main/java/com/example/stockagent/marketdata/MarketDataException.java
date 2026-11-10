package com.example.stockagent.marketdata;

/** A market-data lookup failed for a reason the caller (or the model) can act on. */
public class MarketDataException extends RuntimeException {

    public enum Kind {
        /** The ticker is malformed; nothing was sent upstream. */
        INVALID_SYMBOL,
        /** The provider does not know this ticker. */
        SYMBOL_NOT_FOUND,
        /** The provider's request quota is exhausted; retry later. */
        RATE_LIMITED,
        /** The API key is missing, invalid, or its plan does not cover this symbol or endpoint. */
        ACCESS_DENIED,
        /** No provider is configured for this kind of data. */
        NOT_CONFIGURED,
        /** The provider failed or returned something unusable. */
        UPSTREAM_ERROR
    }

    private final Kind kind;

    public MarketDataException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public MarketDataException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
