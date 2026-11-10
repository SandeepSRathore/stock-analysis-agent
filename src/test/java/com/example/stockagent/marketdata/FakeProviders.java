package com.example.stockagent.marketdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/** Hand-written in-memory providers that count calls, shared by tests across packages. */
public final class FakeProviders {

    private FakeProviders() {
    }

    public static Quote quote(String symbol, double price, LocalDate tradingDay, boolean marketOpen) {
        return new Quote(symbol, symbol + " Inc.", "NASDAQ", "USD", price, price - 1, 1, 1.0, price - 0.5, price + 1,
                price - 2, 1_000_000, marketOpen, 2_000_000L, price + 10, price - 50, tradingDay, marketOpen,
                Instant.parse("2026-11-09T21:00:00Z"));
    }

    public static PriceHistory history(String symbol, int bars, LocalDate lastDay, IntToDoubleFunction closeAt) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            double close = closeAt.applyAsDouble(i);
            candles.add(new Candle(lastDay.minusDays(bars - 1 - i), close, close * 1.01, close * 0.99, close, 1_000_000));
        }
        return new PriceHistory(symbol, "USD", "NASDAQ", candles);
    }

    public static CompanyFundamentals fundamentals(String symbol) {
        return new CompanyFundamentals(symbol, symbol + " Inc.", "Technology", "NASDAQ", "US", "USD",
                LocalDate.of(1980, 12, 12), "https://example.com", 5_000_000.0, 15_000.0,
                30.0, 40.0, 8.0, 6.5, 0.5, 1.2, 150.0, 25.0, 45.0, 30.0, 6.0, 10.0, 0.9, 1.5);
    }

    public static class FakePriceProvider implements PriceDataProvider {
        public final List<String> quoteCalls = new ArrayList<>();
        public final List<String> historyCalls = new ArrayList<>();
        public LocalDate lastDay = LocalDate.of(2026, 11, 9);
        public boolean marketOpen = false;
        public int historyBarsRequested;
        /** Thrown (once) by the next call, then cleared. */
        public RuntimeException failNext;

        @Override
        public String name() {
            return "Fake prices";
        }

        @Override
        public Quote quote(String symbol) {
            quoteCalls.add(symbol);
            throwIfPrimed();
            return FakeProviders.quote(symbol, 359, lastDay, marketOpen);
        }

        @Override
        public PriceHistory dailyHistory(String symbol, int bars) {
            historyCalls.add(symbol);
            historyBarsRequested = bars;
            throwIfPrimed();
            return FakeProviders.history(symbol, 260, lastDay, i -> 100 + i);
        }

        private void throwIfPrimed() {
            if (failNext != null) {
                RuntimeException e = failNext;
                failNext = null;
                throw e;
            }
        }
    }

    public static class FakeCompanyProvider implements CompanyDataProvider {
        public final List<String> fundamentalsCalls = new ArrayList<>();
        public final List<String> newsCalls = new ArrayList<>();
        public List<NewsArticle> articles = List.of(
                new NewsArticle("Headline one", "Reuters", "Summary one", "https://example.com/1", Instant.parse("2026-11-09T10:00:00Z")),
                new NewsArticle("Headline two", "Bloomberg", "Summary two", "https://example.com/2", Instant.parse("2026-11-08T10:00:00Z")));

        @Override
        public String name() {
            return "Fake company data";
        }

        @Override
        public CompanyFundamentals fundamentals(String symbol) {
            fundamentalsCalls.add(symbol);
            return FakeProviders.fundamentals(symbol);
        }

        @Override
        public List<NewsArticle> news(String symbol, LocalDate from, LocalDate to) {
            newsCalls.add(symbol + " " + from + ".." + to);
            return articles;
        }
    }
}
