package com.example.stockagent.analysis;

import com.example.stockagent.marketdata.MarketDataService;
import com.example.stockagent.marketdata.PriceHistory;
import com.example.stockagent.marketdata.Quote;
import org.springframework.stereotype.Service;

/** Fetches a symbol's daily bars and runs them through the {@link TechnicalAnalyzer}. */
@Service
public class TechnicalAnalysisService {

    private final MarketDataService marketData;

    public TechnicalAnalysisService(MarketDataService marketData) {
        this.marketData = marketData;
    }

    public TechnicalSnapshot analyze(String symbol) {
        PriceHistory history = marketData.history(symbol);
        // The daily series includes today's bar while the market is open. The quote tells us whether
        // that bar is still moving, so volume comparisons can use the last finished session instead.
        Quote quote = marketData.quote(symbol);
        boolean sessionInProgress = quote.marketOpen() && history.latest().date().equals(quote.tradingDay());
        return TechnicalAnalyzer.analyze(history, sessionInProgress);
    }
}
