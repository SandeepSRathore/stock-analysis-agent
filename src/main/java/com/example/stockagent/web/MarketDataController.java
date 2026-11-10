package com.example.stockagent.web;

import com.example.stockagent.analysis.TechnicalAnalysisService;
import com.example.stockagent.analysis.TechnicalSnapshot;
import com.example.stockagent.marketdata.CompanyFundamentals;
import com.example.stockagent.marketdata.MarketDataService;
import com.example.stockagent.marketdata.NewsArticle;
import com.example.stockagent.marketdata.Quote;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The same data the agent's tools see, without the language model. Useful for checking a figure
 * in a report, and for debugging a data provider on its own.
 */
@RestController
@RequestMapping("/api/stocks/{symbol}")
public class MarketDataController {

    private final MarketDataService marketData;
    private final TechnicalAnalysisService technicalAnalysis;

    public MarketDataController(MarketDataService marketData, TechnicalAnalysisService technicalAnalysis) {
        this.marketData = marketData;
        this.technicalAnalysis = technicalAnalysis;
    }

    @GetMapping("/quote")
    public Quote quote(@PathVariable String symbol) {
        return marketData.quote(symbol);
    }

    @GetMapping("/technicals")
    public TechnicalSnapshot technicals(@PathVariable String symbol) {
        return technicalAnalysis.analyze(symbol);
    }

    @GetMapping("/fundamentals")
    public CompanyFundamentals fundamentals(@PathVariable String symbol) {
        return marketData.fundamentals(symbol);
    }

    @GetMapping("/news")
    public List<NewsArticle> news(@PathVariable String symbol, @RequestParam(defaultValue = "7") int days) {
        return marketData.news(symbol, days);
    }
}
