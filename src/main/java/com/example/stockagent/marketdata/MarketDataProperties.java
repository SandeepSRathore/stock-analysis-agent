package com.example.stockagent.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param historyBars     daily bars fetched per symbol; 320 covers a 200-day average plus a full
 *                        52-week window with room to spare
 * @param maxNewsArticles cap on articles handed to the model, to bound prompt size
 */
@ConfigurationProperties("stockagent.market-data")
public record MarketDataProperties(
        @DefaultValue("320") int historyBars,
        @DefaultValue("8") int maxNewsArticles,
        @DefaultValue("60s") Duration quoteTtl,
        @DefaultValue("15m") Duration historyTtl,
        @DefaultValue("6h") Duration fundamentalsTtl,
        @DefaultValue("15m") Duration newsTtl) {
}
