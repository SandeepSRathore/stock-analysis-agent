package com.example.stockagent.marketdata.finnhub;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param apiKey blank disables fundamentals and news; set FINNHUB_API_KEY to enable them */
@ConfigurationProperties("stockagent.finnhub")
public record FinnhubProperties(
        @DefaultValue("https://finnhub.io/api/v1") String baseUrl,
        @DefaultValue("") String apiKey) {
}
