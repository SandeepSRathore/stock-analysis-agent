package com.example.stockagent.marketdata;

import java.util.Comparator;
import java.util.List;

/** Daily bars for one symbol, always oldest first. */
public record PriceHistory(String symbol, String currency, String exchange, List<Candle> candles) {

    public PriceHistory {
        candles = candles.stream().sorted(Comparator.comparing(Candle::date)).toList();
    }

    public Candle latest() {
        return candles.getLast();
    }
}
