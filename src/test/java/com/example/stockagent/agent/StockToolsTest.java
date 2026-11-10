package com.example.stockagent.agent;

import com.example.stockagent.agent.StockTools.CompanyNews;
import com.example.stockagent.agent.StockTools.RecentPrices;
import com.example.stockagent.agent.StockTools.ToolFailure;
import com.example.stockagent.agent.ToolCallLog.ToolCall;
import com.example.stockagent.analysis.TechnicalAnalysisService;
import com.example.stockagent.analysis.TechnicalSnapshot;
import com.example.stockagent.marketdata.CompanyDataProvider;
import com.example.stockagent.marketdata.CompanyFundamentals;
import com.example.stockagent.marketdata.FakeProviders;
import com.example.stockagent.marketdata.FakeProviders.FakeCompanyProvider;
import com.example.stockagent.marketdata.FakeProviders.FakePriceProvider;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.example.stockagent.marketdata.MarketDataProperties;
import com.example.stockagent.marketdata.MarketDataService;
import com.example.stockagent.marketdata.Quote;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StockToolsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
    private static final MarketDataProperties PROPERTIES = new MarketDataProperties(
            320, 8, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(6), Duration.ofMinutes(15));

    private final FakePriceProvider prices = new FakePriceProvider();
    private final FakeCompanyProvider company = new FakeCompanyProvider();
    private final ToolCallLog log = new ToolCallLog();
    private final ToolContext context = new ToolContext(log.asToolContext());
    private final StockTools tools = toolsWith(company);

    private StockTools toolsWith(CompanyDataProvider companyProvider) {
        MarketDataService marketData = new MarketDataService(prices, companyProvider, PROPERTIES, CLOCK);
        return new StockTools(marketData, new TechnicalAnalysisService(marketData));
    }

    @Test
    void eachToolReturnsItsDataAndRecordsTheCall() {
        assertThat(tools.getQuote(" aapl ", context)).isInstanceOf(Quote.class);
        assertThat(tools.getTechnicalAnalysis("AAPL", context)).isInstanceOf(TechnicalSnapshot.class);
        assertThat(tools.getRecentPriceHistory("AAPL", 5, context)).isInstanceOf(RecentPrices.class);
        assertThat(tools.getCompanyFundamentals("AAPL", context)).isInstanceOf(CompanyFundamentals.class);
        assertThat(tools.getCompanyNews("AAPL", 7, context)).isInstanceOf(CompanyNews.class);

        assertThat(log.calls()).extracting(ToolCall::tool).containsExactly(
                "getQuote", "getTechnicalAnalysis", "getRecentPriceHistory", "getCompanyFundamentals", "getCompanyNews");
        assertThat(log.calls()).allMatch(ToolCall::succeeded).allMatch(call -> call.symbol().equals("AAPL"));
        assertThat(log.result("getQuote", "AAPL", Quote.class)).map(Quote::price).contains(359.0);
        assertThat(log.result("getTechnicalAnalysis", "AAPL", TechnicalSnapshot.class)).isPresent();
        assertThat(log.result("getCompanyNews", "AAPL", CompanyNews.class)).map(CompanyNews::articleCount).contains(2);
    }

    @Test
    void marketDataFailureBecomesAnExplanationForTheModelNotAnException() {
        prices.failNext = new MarketDataException(Kind.RATE_LIMITED, "Rate limit reached. Try again in a minute.");

        Object result = tools.getQuote("AAPL", context);

        assertThat(result).isEqualTo(new ToolFailure("Rate limit reached. Try again in a minute.", "RATE_LIMITED", "AAPL"));
        assertThat(log.calls()).singleElement().satisfies(call -> {
            assertThat(call.succeeded()).isFalse();
            assertThat(call.error()).isEqualTo("Rate limit reached. Try again in a minute.");
        });
        assertThat(log.result("getQuote", "AAPL", Quote.class)).isEmpty();
    }

    @Test
    void invalidSymbolIsExplainedWithoutCallingAProvider() {
        Object result = tools.getQuote("Apple Inc", context);

        assertThat(result).isInstanceOfSatisfying(ToolFailure.class, failure -> {
            assertThat(failure.reason()).isEqualTo("INVALID_SYMBOL");
            assertThat(failure.error()).contains("not a valid ticker symbol");
            assertThat(failure.symbol()).isEqualTo("APPLE INC");
        });
        assertThat(prices.quoteCalls).isEmpty();
    }

    @Test
    void nullSymbolFromTheModelIsHandled() {
        assertThat(tools.getQuote(null, context)).isInstanceOfSatisfying(ToolFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo("INVALID_SYMBOL"));
    }

    @Test
    void fundamentalsAndNewsExplainWhenNoProviderIsConfigured() {
        StockTools priceOnly = toolsWith(null);

        assertThat(priceOnly.getCompanyFundamentals("AAPL", context)).isInstanceOfSatisfying(ToolFailure.class, failure -> {
            assertThat(failure.reason()).isEqualTo("NOT_CONFIGURED");
            assertThat(failure.error()).contains("FINNHUB_API_KEY");
        });
        assertThat(priceOnly.getCompanyNews("AAPL", 7, context)).isInstanceOf(ToolFailure.class);
    }

    @Test
    void unexpectedErrorIsHiddenFromTheModelButReported() {
        prices.failNext = new IllegalStateException("secret internal detail");

        Object result = tools.getQuote("AAPL", context);

        assertThat(result).isInstanceOfSatisfying(ToolFailure.class, failure -> {
            assertThat(failure.reason()).isEqualTo("INTERNAL_ERROR");
            assertThat(failure.error()).doesNotContain("secret");
        });
        assertThat(log.calls()).singleElement().matches(call -> !call.succeeded());
    }

    @Test
    void recentPriceHistoryReturnsTheLastNSessionsOldestFirst() {
        RecentPrices recent = (RecentPrices) tools.getRecentPriceHistory("AAPL", 3, context);

        // The fake history rises by 1 a day and ends at 359 on 2026-11-09.
        assertThat(recent.sessions()).isEqualTo(3);
        assertThat(recent.closes()).extracting(StockTools.DailyClose::close).containsExactly(357.0, 358.0, 359.0);
        assertThat(recent.closes().getLast().date()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(recent.changePercentOverWindow()).isEqualTo(0.56); // (359 - 357) / 357
    }

    @Test
    void recentPriceHistoryIsClampedToSixtySessionsAndAtLeastOne() {
        assertThat(((RecentPrices) tools.getRecentPriceHistory("AAPL", 5000, context)).closes()).hasSize(60);

        RecentPrices one = (RecentPrices) tools.getRecentPriceHistory("AAPL", -4, context);
        assertThat(one.closes()).hasSize(1);
        assertThat(one.changePercentOverWindow()).isNull();
    }

    @Test
    void recentPriceHistoryNeverAsksForMoreSessionsThanExist() {
        RecentPrices recent = StockTools.recentPrices(FakeProviders.history("NEW", 4, LocalDate.of(2026, 11, 9), i -> 10 + i), 30);

        assertThat(recent.sessions()).isEqualTo(4);
        assertThat(recent.closes()).hasSize(4);
    }

    @Test
    void newsWindowIsClamped() {
        CompanyNews news = (CompanyNews) tools.getCompanyNews("AAPL", 400, context);

        assertThat(news.daysCovered()).isEqualTo(30);
        assertThat(company.newsCalls).containsExactly("AAPL 2026-10-10..2026-11-09");
    }

    @Test
    void toolsWorkWithoutARequestLog() {
        assertThat(tools.getQuote("AAPL", new ToolContext(Map.of()))).isInstanceOf(Quote.class);
        assertThat(tools.getQuote("AAPL", null)).isInstanceOf(Quote.class);
    }

    // --- what the model actually sees: tool definitions and JSON results, through Spring AI

    @Test
    void springAiExposesFiveToolsWithSymbolParametersAndNoToolContext() {
        ToolCallback[] callbacks = ToolCallbacks.from(tools);

        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name()).containsExactlyInAnyOrder(
                "getQuote", "getTechnicalAnalysis", "getRecentPriceHistory", "getCompanyFundamentals", "getCompanyNews");
        assertThat(callbacks).allSatisfy(callback -> {
            assertThat(callback.getToolDefinition().description()).isNotBlank();
            assertThat(callback.getToolDefinition().inputSchema()).contains("\"symbol\"").doesNotContain("toolContext");
        });
        assertThat(callback("getCompanyNews").getToolDefinition().inputSchema()).contains("\"days\"");
        assertThat(callback("getRecentPriceHistory").getToolDefinition().inputSchema()).contains("\"days\"");
    }

    @Test
    void toolResultIsSerialisedAsReadableJsonWithIsoDates() {
        String json = callback("getQuote").call("{\"symbol\":\"aapl\"}", context);

        assertThat(json)
                .contains("\"symbol\":\"AAPL\"")
                .contains("\"price\":359.0")
                .contains("\"tradingDay\":\"2026-11-09\"")
                .contains("\"asOf\":\"2026-11-09T21:00:00Z\"");
        assertThat(log.calls()).singleElement().matches(ToolCall::succeeded);
    }

    @Test
    void toolFailureIsSerialisedAsAnErrorObject() {
        String json = callback("getCompanyNews").call("{\"symbol\":\"not a ticker\",\"days\":7}", context);

        assertThat(json).contains("\"error\":").contains("\"reason\":\"INVALID_SYMBOL\"");
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(ToolCallbacks.from(tools))
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
