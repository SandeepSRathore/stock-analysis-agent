package com.example.stockagent.agent;

import com.example.stockagent.analysis.TechnicalAnalysisService;
import com.example.stockagent.marketdata.Candle;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataService;
import com.example.stockagent.marketdata.NewsArticle;
import com.example.stockagent.marketdata.PriceHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * The tools the model can call. Each one returns either real data or a {@link ToolFailure} that
 * says what went wrong, so the model can explain a gap instead of inventing a number.
 */
@Component
public class StockTools {

    public static final String QUOTE = "getQuote";
    public static final String TECHNICALS = "getTechnicalAnalysis";
    public static final String PRICE_HISTORY = "getRecentPriceHistory";
    public static final String FUNDAMENTALS = "getCompanyFundamentals";
    public static final String NEWS = "getCompanyNews";

    static final int MAX_HISTORY_DAYS = 60;

    private static final Logger log = LoggerFactory.getLogger(StockTools.class);
    private static final String SYMBOL_HELP = "Ticker symbol such as AAPL or MSFT (not a company name)";

    private final MarketDataService marketData;
    private final TechnicalAnalysisService technicalAnalysis;

    public StockTools(MarketDataService marketData, TechnicalAnalysisService technicalAnalysis) {
        this.marketData = marketData;
        this.technicalAnalysis = technicalAnalysis;
    }

    /** What the model sees when a tool cannot return data. */
    public record ToolFailure(String error, String reason, String symbol) {
    }

    public record DailyClose(LocalDate date, double close, long volume) {
    }

    public record RecentPrices(String symbol, String currency, int sessions, Double changePercentOverWindow, List<DailyClose> closes) {
    }

    public record CompanyNews(String symbol, int daysCovered, int articleCount, List<NewsArticle> articles) {
    }

    @Tool(name = QUOTE, description = """
            Latest price for one stock: price, change versus the previous close, day range, volume, \
            52-week high/low, and whether the market is open right now. Use for "what is X trading at". \
            When volumeProvisional is true the volume is incomplete: do not compare it with averageVolume.""")
    public Object getQuote(@ToolParam(description = SYMBOL_HELP) String symbol, ToolContext toolContext) {
        return run(QUOTE, symbol, toolContext, marketData::quote);
    }

    @Tool(name = TECHNICALS, description = """
            Technical analysis of one stock computed from about 15 months of daily prices: trend \
            classification, 20/50/200-day moving averages, RSI(14), MACD(12,26,9), Bollinger bands, ATR, \
            annualised volatility, 1-year max drawdown, 52-week range, 20-day support/resistance, \
            returns over 1 week to 1 year, relative volume, plus plain-English signals and caveats. \
            Use for any question about trend, momentum, volatility or performance.""")
    public Object getTechnicalAnalysis(@ToolParam(description = SYMBOL_HELP) String symbol, ToolContext toolContext) {
        return run(TECHNICALS, symbol, toolContext, technicalAnalysis::analyze);
    }

    @Tool(name = PRICE_HISTORY, description = """
            Daily closing prices and volumes for the most recent trading sessions of one stock, oldest \
            first. Use only when the question needs day-by-day prices (e.g. "what happened last week"); \
            for trend or performance questions use getTechnicalAnalysis instead.""")
    public Object getRecentPriceHistory(
            @ToolParam(description = SYMBOL_HELP) String symbol,
            @ToolParam(description = "Number of trading sessions to return, from 1 to 60") int days,
            ToolContext toolContext) {
        return run(PRICE_HISTORY, symbol, toolContext, s -> recentPrices(marketData.history(s), days));
    }

    @Tool(name = FUNDAMENTALS, description = """
            Company profile and fundamentals for one stock: industry, market cap, P/E, P/B, P/S, EPS, \
            dividend yield, beta, margins, return on equity, revenue and EPS growth, current ratio and \
            debt/equity. A null field means the provider does not report it. Use for valuation, \
            profitability, growth or "what does this company do" questions.""")
    public Object getCompanyFundamentals(@ToolParam(description = SYMBOL_HELP) String symbol, ToolContext toolContext) {
        return run(FUNDAMENTALS, symbol, toolContext, marketData::fundamentals);
    }

    @Tool(name = NEWS, description = """
            Recent news headlines and short summaries for one company, newest first. Use to explain a \
            price move or to check for recent events. An empty list means no news in that window.""")
    public Object getCompanyNews(
            @ToolParam(description = SYMBOL_HELP) String symbol,
            @ToolParam(description = "How many days back to look, from 1 to 30. Use 7 unless the user asks otherwise.") int days,
            ToolContext toolContext) {
        return run(NEWS, symbol, toolContext, s -> {
            int window = Math.clamp(days, 1, 30);
            List<NewsArticle> articles = marketData.news(s, window);
            return new CompanyNews(s, window, articles.size(), articles);
        });
    }

    static RecentPrices recentPrices(PriceHistory history, int days) {
        int sessions = Math.min(Math.clamp(days, 1, MAX_HISTORY_DAYS), history.candles().size());
        List<Candle> window = history.candles().subList(history.candles().size() - sessions, history.candles().size());
        double first = window.getFirst().close();
        Double change = sessions < 2 || first == 0 ? null
                : Math.round((window.getLast().close() - first) / first * 10_000) / 100.0;
        return new RecentPrices(history.symbol(), history.currency(), sessions, change,
                window.stream().map(c -> new DailyClose(c.date(), c.close(), c.volume())).toList());
    }

    private Object run(String tool, String rawSymbol, ToolContext toolContext, Function<String, Object> lookup) {
        ToolCallLog calls = ToolCallLog.from(toolContext);
        long started = System.nanoTime();
        String symbol = rawSymbol == null ? "" : rawSymbol.strip().toUpperCase();
        try {
            symbol = MarketDataService.normalize(rawSymbol);
            Object result = lookup.apply(symbol);
            calls.recordSuccess(tool, symbol, result, elapsedMs(started));
            return result;
        } catch (MarketDataException e) {
            log.info("Tool {}({}) failed: {} - {}", tool, symbol, e.kind(), e.getMessage());
            calls.recordFailure(tool, symbol, e.getMessage(), elapsedMs(started));
            return new ToolFailure(e.getMessage(), e.kind().name(), symbol);
        } catch (RuntimeException e) {
            // A bug or an unexpected response shape: tell the model the data is unavailable, keep the detail in the log.
            log.error("Tool {}({}) failed unexpectedly", tool, symbol, e);
            calls.recordFailure(tool, symbol, "Unexpected error", elapsedMs(started));
            return new ToolFailure("This data is unavailable because of an unexpected internal error.", "INTERNAL_ERROR", symbol);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
