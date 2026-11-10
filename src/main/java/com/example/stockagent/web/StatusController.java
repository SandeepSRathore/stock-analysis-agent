package com.example.stockagent.web;

import com.example.stockagent.marketdata.MarketDataService;
import com.example.stockagent.marketdata.twelvedata.TwelveDataProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports which model and data sources this instance is running with. Never exposes keys. */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    /**
     * @param companyDataProvider null when fundamentals and news are not configured
     * @param priceDataLimitedToDemoSymbols true while the Twelve Data demo key is in use
     */
    public record Status(String model, String priceDataProvider, boolean priceDataLimitedToDemoSymbols, String companyDataProvider) {
    }

    private final MarketDataService marketData;
    private final TwelveDataProperties twelveData;
    private final String model;

    public StatusController(MarketDataService marketData,
                            TwelveDataProperties twelveData,
                            @Value("${spring.ai.openai.chat.model:unknown}") String model) {
        this.marketData = marketData;
        this.twelveData = twelveData;
        this.model = model;
    }

    @GetMapping
    public Status status() {
        return new Status(model, marketData.priceProviderName(), twelveData.usingDemoKey(),
                marketData.companyProviderName().orElse(null));
    }
}
