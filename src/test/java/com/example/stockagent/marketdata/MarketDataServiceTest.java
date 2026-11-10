package com.example.stockagent.marketdata;

import com.example.stockagent.marketdata.FakeProviders.FakeCompanyProvider;
import com.example.stockagent.marketdata.FakeProviders.FakePriceProvider;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class MarketDataServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
    private static final MarketDataProperties PROPERTIES = new MarketDataProperties(
            320, 3, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(6), Duration.ofMinutes(15));

    private final FakePriceProvider prices = new FakePriceProvider();
    private final FakeCompanyProvider company = new FakeCompanyProvider();
    private final MarketDataService service = new MarketDataService(prices, company, PROPERTIES, CLOCK);

    @Test
    void symbolsAreTrimmedAndUpperCasedBeforeReachingTheProvider() {
        assertThat(service.quote("  aapl ").symbol()).isEqualTo("AAPL");
        assertThat(prices.quoteCalls).containsExactly("AAPL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AAPL", "brk.b", "BF-B", "TCS:NSE", "7203", "A"})
    void acceptsRealWorldTickerShapes(String symbol) {
        assertThat(MarketDataService.normalize(symbol)).isEqualTo(symbol.toUpperCase());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "Apple Inc", "AAPL;DROP", "../etc", ".AAPL", "AAPL?x=1", "ABCDEFGHIJKLMNOPQRSTU", "AAPL\nMSFT"})
    void rejectsAnythingThatIsNotATickerWithoutCallingTheProvider(String symbol) {
        MarketDataException e = catchThrowableOfType(MarketDataException.class, () -> service.quote(symbol));

        assertThat(e).isNotNull();
        assertThat(e.kind()).isEqualTo(Kind.INVALID_SYMBOL);
        assertThat(e.getMessage()).contains("not a valid ticker symbol");
        assertThat(prices.quoteCalls).isEmpty();
    }

    @Test
    void repeatedLookupsAreServedFromCache() {
        service.quote("AAPL");
        service.quote("aapl");
        service.history("AAPL");
        service.history("AAPL");
        service.fundamentals("AAPL");
        service.fundamentals("AAPL");
        service.news("AAPL", 7);
        service.news("AAPL", 7);

        assertThat(prices.quoteCalls).hasSize(1);
        assertThat(prices.historyCalls).hasSize(1);
        assertThat(company.fundamentalsCalls).hasSize(1);
        assertThat(company.newsCalls).hasSize(1);
    }

    @Test
    void differentSymbolsAndNewsWindowsAreCachedSeparately() {
        service.quote("AAPL");
        service.quote("MSFT");
        service.news("AAPL", 7);
        service.news("AAPL", 14);

        assertThat(prices.quoteCalls).containsExactly("AAPL", "MSFT");
        assertThat(company.newsCalls).hasSize(2);
    }

    @Test
    void failuresAreNotCachedSoTheNextCallRetries() {
        prices.failNext = new MarketDataException(Kind.RATE_LIMITED, "slow down");

        MarketDataException e = catchThrowableOfType(MarketDataException.class, () -> service.quote("AAPL"));
        assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);

        assertThat(service.quote("AAPL").price()).isEqualTo(359);
        assertThat(prices.quoteCalls).hasSize(2);
    }

    @Test
    void historyRequestsTheConfiguredNumberOfBars() {
        service.history("AAPL");

        assertThat(prices.historyBarsRequested).isEqualTo(320);
    }

    @Test
    void newsCoversTheRequestedWindowEndingToday() {
        service.news("AAPL", 7);

        assertThat(company.newsCalls).containsExactly("AAPL 2026-11-02..2026-11-09");
    }

    @Test
    void newsWindowIsClampedToBetweenOneAndThirtyDays() {
        service.news("AAPL", 0);
        service.news("MSFT", 365);

        assertThat(company.newsCalls).containsExactly("AAPL 2026-11-08..2026-11-09", "MSFT 2026-10-10..2026-11-09");
    }

    @Test
    void newsIsCappedAndLongSummariesAreShortened() {
        String longSummary = "x".repeat(400);
        company.articles = IntStream.range(0, 10)
                .mapToObj(i -> new NewsArticle("Headline " + i, "Source", longSummary, "https://example.com/" + i,
                        Instant.parse("2026-11-09T10:00:00Z").minusSeconds(i)))
                .toList();

        List<NewsArticle> news = service.news("AAPL", 7);

        assertThat(news).hasSize(3);
        assertThat(news.getFirst().headline()).isEqualTo("Headline 0");
        assertThat(news.getFirst().summary()).hasSize(281).endsWith("…");
    }

    @Test
    void shortAndMissingSummariesAreLeftAlone() {
        company.articles = List.of(
                new NewsArticle("A", "S", null, "u", Instant.parse("2026-11-09T10:00:00Z")),
                new NewsArticle("B", "S", "short", "u", Instant.parse("2026-11-09T09:00:00Z")));

        assertThat(service.news("AAPL", 7)).extracting(NewsArticle::summary).containsExactly(null, "short");
    }

    @Test
    void withoutACompanyProviderFundamentalsAndNewsSayHowToEnableThem() {
        MarketDataService priceOnly = new MarketDataService(prices, (CompanyDataProvider) null, PROPERTIES, CLOCK);

        MarketDataException fundamentals = catchThrowableOfType(MarketDataException.class, () -> priceOnly.fundamentals("AAPL"));
        MarketDataException news = catchThrowableOfType(MarketDataException.class, () -> priceOnly.news("AAPL", 7));

        assertThat(fundamentals.kind()).isEqualTo(Kind.NOT_CONFIGURED);
        assertThat(fundamentals.getMessage()).contains("Company fundamentals are not available", "FINNHUB_API_KEY");
        assertThat(news.kind()).isEqualTo(Kind.NOT_CONFIGURED);
        assertThat(news.getMessage()).startsWith("Company news is not available");
        assertThat(priceOnly.companyProviderName()).isEmpty();
        assertThat(priceOnly.quote("AAPL").price()).isEqualTo(359);
    }

    @Test
    void invalidSymbolIsReportedBeforeTheMissingProvider() {
        MarketDataService priceOnly = new MarketDataService(prices, (CompanyDataProvider) null, PROPERTIES, CLOCK);

        MarketDataException e = catchThrowableOfType(MarketDataException.class, () -> priceOnly.fundamentals("not a ticker"));

        assertThat(e.kind()).isEqualTo(Kind.INVALID_SYMBOL);
    }

    @Test
    void exposesProviderNames() {
        assertThat(service.priceProviderName()).isEqualTo("Fake prices");
        assertThat(service.companyProviderName()).contains("Fake company data");
    }
}
