package com.example.stockagent.marketdata;

/** Source of quotes and daily price history. Implementations throw {@link MarketDataException}. */
public interface PriceDataProvider {

    String name();

    Quote quote(String symbol);

    /** The most recent {@code bars} daily candles, oldest first. */
    PriceHistory dailyHistory(String symbol, int bars);
}
