package com.example.stockagent;

import com.example.stockagent.agent.StockAnalysisAgent;
import com.example.stockagent.agent.StockTools;
import com.example.stockagent.marketdata.CompanyDataProvider;
import com.example.stockagent.marketdata.MarketDataService;
import com.example.stockagent.marketdata.PriceDataProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application with dummy keys. Nothing here calls OpenAI or a data provider:
 * clients are only constructed, not used.
 */
class StockAnalysisAgentApplicationTests {

    @Nested
    @SpringBootTest(properties = {
            "spring.ai.openai.api-key=test-key",
            "stockagent.twelvedata.api-key=demo",
            "stockagent.finnhub.api-key="})
    class WithoutAFinnhubKey {

        @Autowired
        StockAnalysisAgent agent;

        @Autowired
        StockTools tools;

        @Autowired
        MarketDataService marketData;

        @Autowired
        ObjectProvider<CompanyDataProvider> companyProvider;

        @Test
        void startsOnPriceDataAlone() {
            assertThat(agent).isNotNull();
            assertThat(tools).isNotNull();
            assertThat(companyProvider.getIfAvailable()).isNull();
            assertThat(marketData.companyProviderName()).isEmpty();
            assertThat(marketData.priceProviderName()).isEqualTo("Twelve Data (demo key)");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.ai.openai.api-key=test-key",
            "stockagent.twelvedata.api-key=real-key",
            "stockagent.finnhub.api-key=fh-key"})
    class WithAFinnhubKey {

        @Autowired
        MarketDataService marketData;

        @Autowired
        PriceDataProvider priceProvider;

        @Test
        void enablesFundamentalsAndNews() {
            assertThat(marketData.companyProviderName()).contains("Finnhub");
            assertThat(priceProvider.name()).isEqualTo("Twelve Data");
        }
    }
}
