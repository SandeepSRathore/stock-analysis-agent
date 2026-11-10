package com.example.stockagent.marketdata.finnhub;

import com.example.stockagent.Fixtures;
import com.example.stockagent.marketdata.CompanyFundamentals;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.example.stockagent.marketdata.NewsArticle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Fixtures follow the response schemas in Finnhub's published API spec (profile2, metric,
 * company-news). They are hand-written, not captured from a live call.
 */
class FinnhubCompanyProviderTest {

    private static final String BASE = "https://finnhub.test/api/v1";

    private MockRestServiceServer server;
    private FinnhubCompanyProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new FinnhubCompanyProvider(builder, new FinnhubProperties(BASE, "fh-key"));
    }

    @Test
    void fundamentalsCombineProfileAndMetrics() {
        server.expect(requestTo(BASE + "/stock/profile2?symbol=AAPL"))
                .andExpect(header("X-Finnhub-Token", "fh-key"))
                .andRespond(withSuccess(Fixtures.read("finnhub-profile2.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/stock/metric?symbol=AAPL&metric=all"))
                .andExpect(header("X-Finnhub-Token", "fh-key"))
                .andRespond(withSuccess(Fixtures.read("finnhub-metric.json"), MediaType.APPLICATION_JSON));

        CompanyFundamentals f = provider.fundamentals("AAPL");

        assertThat(f).isEqualTo(new CompanyFundamentals(
                "AAPL", "Apple Inc", "Technology", "NASDAQ NMS - GLOBAL MARKET", "US", "USD",
                LocalDate.of(1980, 12, 12), "https://www.apple.com/",
                5180000.5, 14776.35,
                34.2, 51.3, 9.1, 10.25, 0.42, 1.29,
                151.9, 26.9, 46.2, 31.8, 6.4, 12.1, 0.89, 1.47));
        server.verify();
    }

    @Test
    void exchangeSuffixIsDroppedBecauseFinnhubUsesBareTickers() {
        server.expect(requestTo(BASE + "/stock/profile2?symbol=AAPL"))
                .andRespond(withSuccess(Fixtures.read("finnhub-profile2.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/stock/metric?symbol=AAPL&metric=all"))
                .andRespond(withSuccess("{\"metric\":{}}", MediaType.APPLICATION_JSON));

        assertThat(provider.fundamentals("AAPL:NASDAQ").symbol()).isEqualTo("AAPL:NASDAQ");
        server.verify();
    }

    @Test
    void metricsFallBackToAlternativeNamesAndStayNullWhenAbsent() {
        server.expect(requestTo(BASE + "/stock/profile2?symbol=XYZ"))
                .andRespond(withSuccess("{\"name\":\"Xyz Corp\",\"ticker\":\"XYZ\",\"ipo\":\"not-a-date\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/stock/metric?symbol=XYZ&metric=all")).andRespond(withSuccess("""
                {"metric":{"peTTM":null,"peBasicExclExtraTTM":18.5,"pbAnnual":3.2,"beta":"n/a","roeRfy":14.0}}
                """, MediaType.APPLICATION_JSON));

        CompanyFundamentals f = provider.fundamentals("XYZ");

        assertThat(f.name()).isEqualTo("Xyz Corp");
        assertThat(f.peRatioTtm()).isEqualTo(18.5);
        assertThat(f.priceToBook()).isEqualTo(3.2);
        assertThat(f.returnOnEquityPercent()).isEqualTo(14.0);
        assertThat(f.beta()).isNull();
        assertThat(f.epsTtm()).isNull();
        assertThat(f.netMarginPercent()).isNull();
        assertThat(f.marketCapMillions()).isNull();
        assertThat(f.ipoDate()).isNull();
    }

    @Test
    void missingMetricsObjectLeavesAllMetricsNull() {
        server.expect(requestTo(BASE + "/stock/profile2?symbol=XYZ"))
                .andRespond(withSuccess("{\"name\":\"Xyz Corp\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/stock/metric?symbol=XYZ&metric=all"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CompanyFundamentals f = provider.fundamentals("XYZ");

        assertThat(f.peRatioTtm()).isNull();
        assertThat(f.debtToEquity()).isNull();
    }

    @Test
    void emptyProfileMeansUnknownSymbolAndSkipsTheMetricsCall() {
        server.expect(requestTo(BASE + "/stock/profile2?symbol=NOPE")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MarketDataException e = failure(() -> provider.fundamentals("NOPE"));

        assertThat(e.kind()).isEqualTo(Kind.SYMBOL_NOT_FOUND);
        server.verify(); // no second request was made
    }

    @Test
    void newsIsNewestFirstWithBlankHeadlinesDropped() {
        server.expect(requestTo(BASE + "/company-news?symbol=AAPL&from=2026-11-02&to=2026-11-09"))
                .andExpect(header("X-Finnhub-Token", "fh-key"))
                .andRespond(withSuccess(Fixtures.read("finnhub-company-news.json"), MediaType.APPLICATION_JSON));

        List<NewsArticle> news = provider.news("AAPL", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9));

        assertThat(news).containsExactly(
                new NewsArticle("Apple shares hit record ahead of earnings", "MarketWatch",
                        "Shares closed at an all-time high.", "https://example.com/news/102", Instant.ofEpochSecond(1794240000)),
                new NewsArticle("Apple supplier lifts outlook on strong iPhone demand", "Reuters",
                        "A key supplier raised its full-year forecast, citing stronger than expected orders.",
                        "https://example.com/news/101", Instant.ofEpochSecond(1794150000)));
        server.verify();
    }

    @Test
    void noNewsIsAnEmptyList() {
        server.expect(requestTo(BASE + "/company-news?symbol=AAPL&from=2026-11-02&to=2026-11-09"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(provider.news("AAPL", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9))).isEmpty();
    }

    @Test
    void invalidKeyIsAccessDenied() {
        server.expect(requestTo(BASE + "/stock/profile2?symbol=AAPL"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"Invalid API key\"}"));

        MarketDataException e = failure(() -> provider.fundamentals("AAPL"));

        assertThat(e.kind()).isEqualTo(Kind.ACCESS_DENIED);
        assertThat(e.getMessage()).contains("FINNHUB_API_KEY").doesNotContain("fh-key");
    }

    @Test
    void symbolOutsideTheFreePlanIsAccessDenied() {
        server.expect(requestTo(BASE + "/stock/profile2?symbol=RELIANCE.NS"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"You don't have access to this resource.\"}"));

        MarketDataException e = failure(() -> provider.fundamentals("RELIANCE.NS"));

        assertThat(e.kind()).isEqualTo(Kind.ACCESS_DENIED);
        assertThat(e.getMessage()).contains("free plan does not cover RELIANCE.NS");
    }

    @Test
    void rateLimitIsReported() {
        server.expect(requestTo(BASE + "/company-news?symbol=AAPL&from=2026-11-02&to=2026-11-09"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"API limit reached. Please try again later.\"}"));

        MarketDataException e = failure(() -> provider.news("AAPL", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9)));

        assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
    }

    @Test
    void serverErrorIsAnUpstreamError() {
        server.expect(requestTo(BASE + "/stock/profile2?symbol=AAPL")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        MarketDataException e = failure(() -> provider.fundamentals("AAPL"));

        assertThat(e.kind()).isEqualTo(Kind.UPSTREAM_ERROR);
        assertThat(e.getMessage()).contains("HTTP 500");
    }

    @Test
    void connectionFailureIsRetriedOnce() {
        server.expect(requestTo(BASE + "/company-news?symbol=AAPL&from=2026-11-02&to=2026-11-09"))
                .andRespond(withException(new IOException("Connection reset")));
        server.expect(requestTo(BASE + "/company-news?symbol=AAPL&from=2026-11-02&to=2026-11-09"))
                .andRespond(withSuccess(Fixtures.read("finnhub-company-news.json"), MediaType.APPLICATION_JSON));

        assertThat(provider.news("AAPL", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9))).hasSize(2);
        server.verify();
    }

    @Test
    void connectionFailureTwiceInARowIsAnUpstreamError() {
        server.expect(ExpectedCount.twice(), requestTo(BASE + "/stock/profile2?symbol=AAPL"))
                .andRespond(withException(new IOException("timed out")));

        MarketDataException e = failure(() -> provider.fundamentals("AAPL"));

        assertThat(e.kind()).isEqualTo(Kind.UPSTREAM_ERROR);
        assertThat(e.getMessage()).contains("timed out");
        server.verify();
    }

    private static MarketDataException failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        MarketDataException e = catchThrowableOfType(MarketDataException.class, call);
        assertThat(e).as("expected a MarketDataException").isNotNull();
        return e;
    }
}
