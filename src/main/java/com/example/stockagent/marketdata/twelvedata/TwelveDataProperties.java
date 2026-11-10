package com.example.stockagent.marketdata.twelvedata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param apiKey defaults to Twelve Data's public {@code demo} key, which only answers for a few
 *               symbols such as AAPL; set TWELVEDATA_API_KEY to use any symbol
 */
@ConfigurationProperties("stockagent.twelvedata")
public record TwelveDataProperties(
        @DefaultValue("https://api.twelvedata.com") String baseUrl,
        @DefaultValue(DEMO_KEY) String apiKey) {

    public static final String DEMO_KEY = "demo";

    public boolean usingDemoKey() {
        return DEMO_KEY.equals(apiKey);
    }
}
