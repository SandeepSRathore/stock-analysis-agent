package com.example.stockagent.marketdata.twelvedata;

import com.example.stockagent.Fixtures;
import com.example.stockagent.marketdata.Candle;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.example.stockagent.marketdata.PriceHistory;
import com.example.stockagent.marketdata.Quote;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The quote, time-series and demo-key-error fixtures are real responses captured from the API. */
class TwelveDataPriceProviderTest {

    private static final String BASE = "https://twelvedata.test";

    private MockRestServiceServer server;
    private TwelveDataPriceProvider provider;

    @BeforeEach
    void setUp() {
        provider = providerWithKey("my-key");
    }

    private TwelveDataPriceProvider providerWithKey(String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TwelveDataPriceProvider(builder, new TwelveDataProperties(BASE, apiKey));
    }

    @Test
    void quoteMapsEveryFieldOfARealResponse() {
        server.expect(requestTo(BASE + "/quote?symbol=AAPL"))
                .andExpect(header("Authorization", "apikey my-key"))
                .andRespond(withSuccess(Fixtures.read("twelvedata-quote.json"), MediaType.APPLICATION_JSON));

        Quote quote = provider.quote("AAPL");

        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.name()).isEqualTo("Apple Inc.");
        assertThat(quote.exchange()).isEqualTo("NASDAQ");
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.price()).isEqualTo(350.53);
        assertThat(quote.previousClose()).isEqualTo(342.37);
        assertThat(quote.change()).isEqualTo(8.16);
        assertThat(quote.changePercent()).isEqualTo(2.38339);
        assertThat(quote.open()).isEqualTo(345.005);
        assertThat(quote.dayHigh()).isEqualTo(352.28);
        assertThat(quote.dayLow()).isEqualTo(344.78);
        assertThat(quote.volume()).isEqualTo(1_503_100);
        // Captured minutes after the close: 1.5M against a 52.5M average is a partial tally, not a quiet day.
        assertThat(quote.volumeProvisional()).isTrue();
        assertThat(quote.averageVolume()).isEqualTo(52_553_610);
        assertThat(quote.fiftyTwoWeekHigh()).isEqualTo(355.38);
        assertThat(quote.fiftyTwoWeekLow()).isEqualTo(243.42);
        assertThat(quote.tradingDay()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(quote.marketOpen()).isFalse();
        assertThat(quote.asOf()).isEqualTo(Instant.ofEpochSecond(1794257940));
        server.verify();
    }

    @Test
    void quoteDerivesChangeWhenTheApiOmitsIt() {
        server.expect(requestTo(BASE + "/quote?symbol=XYZ")).andRespond(withSuccess("""
                {"symbol":"XYZ","datetime":"2026-11-09","close":"110","previous_close":"100","is_market_open":true}
                """, MediaType.APPLICATION_JSON));

        Quote quote = provider.quote("XYZ");

        assertThat(quote.change()).isEqualTo(10.0);
        assertThat(quote.changePercent()).isEqualTo(10.0);
        assertThat(quote.marketOpen()).isTrue();
        assertThat(quote.volumeProvisional()).isTrue();
        assertThat(quote.fiftyTwoWeekHigh()).isNull();
        assertThat(quote.averageVolume()).isNull();
    }

    @Test
    void volumeIsFinalOnceTheMarketIsClosedAndTheFigureIsInANormalRange() {
        server.expect(requestTo(BASE + "/quote?symbol=XYZ")).andRespond(withSuccess("""
                {"symbol":"XYZ","datetime":"2026-11-06","close":"110","previous_close":"100","is_market_open":false,
                 "volume":"30000000","average_volume":"50000000"}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.quote("XYZ").volumeProvisional()).isFalse();
    }

    @Test
    void volumeIsNotFlaggedWhenThereIsNoAverageToCompareAgainst() {
        server.expect(requestTo(BASE + "/quote?symbol=XYZ")).andRespond(withSuccess("""
                {"symbol":"XYZ","datetime":"2026-11-06","close":"110","previous_close":"100","is_market_open":false,"volume":"5"}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.quote("XYZ").volumeProvisional()).isFalse();
    }

    @Test
    void quoteWithoutAPriceIsAnUpstreamError() {
        server.expect(requestTo(BASE + "/quote?symbol=XYZ")).andRespond(withSuccess("""
                {"symbol":"XYZ","datetime":"2026-11-09"}
                """, MediaType.APPLICATION_JSON));

        assertThat(failure(() -> provider.quote("XYZ")).kind()).isEqualTo(Kind.UPSTREAM_ERROR);
    }

    @Test
    void historyRequestsDailyBarsAndMapsARealResponse() {
        server.expect(requestTo(BASE + "/time_series?symbol=AAPL&interval=1day&outputsize=320&order=asc"))
                .andExpect(header("Authorization", "apikey my-key"))
                .andRespond(withSuccess(Fixtures.read("twelvedata-time-series.json"), MediaType.APPLICATION_JSON));

        PriceHistory history = provider.dailyHistory("AAPL", 320);

        assertThat(history.symbol()).isEqualTo("AAPL");
        assertThat(history.currency()).isEqualTo("USD");
        assertThat(history.exchange()).isEqualTo("NASDAQ");
        assertThat(history.candles()).hasSize(5);
        assertThat(history.candles().getFirst())
                .isEqualTo(new Candle(LocalDate.of(2026, 11, 3), 325.57999, 334.5, 325.32999, 331.89999, 63_160_000));
        assertThat(history.latest().date()).isEqualTo(LocalDate.of(2026, 11, 9));
        server.verify();
    }

    @Test
    void historyIsSortedOldestFirstWhateverOrderTheApiUses() {
        server.expect(requestTo(BASE + "/time_series?symbol=XYZ&interval=1day&outputsize=2&order=asc")).andRespond(withSuccess("""
                {"meta":{"symbol":"XYZ","currency":"USD","exchange":"NYSE"},"status":"ok","values":[
                  {"datetime":"2026-11-06","open":"2","high":"2","low":"2","close":"2","volume":"20"},
                  {"datetime":"2026-11-05","open":"1","high":"1","low":"1","close":"1","volume":"10"}]}
                """, MediaType.APPLICATION_JSON));

        PriceHistory history = provider.dailyHistory("XYZ", 2);

        assertThat(history.candles()).extracting(Candle::close).containsExactly(1.0, 2.0);
    }

    @Test
    void historySkipsBarsWithoutPricesAndDefaultsMissingVolume() {
        server.expect(requestTo(BASE + "/time_series?symbol=XYZ&interval=1day&outputsize=3&order=asc")).andRespond(withSuccess("""
                {"meta":{"symbol":"XYZ"},"status":"ok","values":[
                  {"datetime":"2026-11-05","open":"1","high":"1","low":"1","close":null,"volume":"10"},
                  {"datetime":"2026-11-06","high":"2.5","low":"1.5","close":"2"}]}
                """, MediaType.APPLICATION_JSON));

        PriceHistory history = provider.dailyHistory("XYZ", 3);

        assertThat(history.candles()).containsExactly(new Candle(LocalDate.of(2026, 11, 6), 2, 2.5, 1.5, 2, 0));
        assertThat(history.currency()).isNull();
    }

    @Test
    void historyWithNoBarsMeansTheSymbolIsUnknown() {
        server.expect(requestTo(BASE + "/time_series?symbol=XYZ&interval=1day&outputsize=5&order=asc"))
                .andRespond(withSuccess("{\"meta\":{\"symbol\":\"XYZ\"},\"status\":\"ok\",\"values\":[]}", MediaType.APPLICATION_JSON));

        assertThat(failure(() -> provider.dailyHistory("XYZ", 5)).kind()).isEqualTo(Kind.SYMBOL_NOT_FOUND);
    }

    @Test
    void demoKeyRejectionExplainsHowToGetARealKey() {
        provider = providerWithKey("demo");
        server.expect(requestTo(BASE + "/quote?symbol=NVDA"))
                .andExpect(header("Authorization", "apikey demo"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Fixtures.read("twelvedata-error-demo-key.json")));

        MarketDataException e = failure(() -> provider.quote("NVDA"));

        assertThat(e.kind()).isEqualTo(Kind.ACCESS_DENIED);
        assertThat(e.getMessage()).contains("demo key only covers a few symbols", "NVDA", "TWELVEDATA_API_KEY");
        assertThat(provider.name()).isEqualTo("Twelve Data (demo key)");
    }

    @Test
    void rejectionOfARealKeyReportsTheApiMessage() {
        // The colon in an exchange-qualified symbol is percent-encoded on the wire.
        server.expect(requestTo(BASE + "/quote?symbol=TCS%3ANSE"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":403,"message":"**symbol** TCS:NSE is available starting with the Grow plan","status":"error"}
                        """));

        MarketDataException e = failure(() -> provider.quote("TCS:NSE"));

        assertThat(e.kind()).isEqualTo(Kind.ACCESS_DENIED);
        assertThat(e.getMessage()).contains("plan does not cover this symbol", "symbol TCS:NSE is available starting with the Grow plan");
        assertThat(e.getMessage()).doesNotContain("**").doesNotContain("my-key");
        assertThat(provider.name()).isEqualTo("Twelve Data");
    }

    @Test
    void errorBodyWithHttp200IsStillAFailure() {
        server.expect(requestTo(BASE + "/quote?symbol=NOPE")).andRespond(withSuccess("""
                {"code":404,"message":"**symbol** not found: NOPE. Please specify it correctly according to API Documentation.","status":"error"}
                """, MediaType.APPLICATION_JSON));

        MarketDataException e = failure(() -> provider.quote("NOPE"));

        assertThat(e.kind()).isEqualTo(Kind.SYMBOL_NOT_FOUND);
        assertThat(e.getMessage()).isEqualTo("Twelve Data does not recognise the symbol NOPE.");
    }

    @Test
    void badRequestAboutTheSymbolIsTreatedAsUnknownSymbol() {
        server.expect(requestTo(BASE + "/quote?symbol=NOPE"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":400,"message":"**symbol** or **figi** parameter is missing or invalid.","status":"error"}
                        """));

        assertThat(failure(() -> provider.quote("NOPE")).kind()).isEqualTo(Kind.SYMBOL_NOT_FOUND);
    }

    @Test
    void rateLimitInJsonBodyIsReported() {
        server.expect(requestTo(BASE + "/quote?symbol=AAPL"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("""
                        {"code":429,"message":"You have run out of API credits for the current minute.","status":"error"}
                        """));

        MarketDataException e = failure(() -> provider.quote("AAPL"));

        assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(e.getMessage()).contains("rate limit", "Try again in a minute");
    }

    @Test
    void rateLimitWithANonJsonBodyFallsBackToTheHttpStatus() {
        server.expect(requestTo(BASE + "/quote?symbol=AAPL"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.TEXT_PLAIN).body("slow down"));

        assertThat(failure(() -> provider.quote("AAPL")).kind()).isEqualTo(Kind.RATE_LIMITED);
    }

    @Test
    void errorStatusWithJsonBodyButNoCodeFallsBackToTheHttpStatus() {
        server.expect(requestTo(BASE + "/quote?symbol=AAPL"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"slow down\"}"));

        assertThat(failure(() -> provider.quote("AAPL")).kind()).isEqualTo(Kind.RATE_LIMITED);
    }

    @Test
    void serverErrorIsAnUpstreamError() {
        server.expect(requestTo(BASE + "/quote?symbol=AAPL"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>Bad gateway</html>"));

        MarketDataException e = failure(() -> provider.quote("AAPL"));

        assertThat(e.kind()).isEqualTo(Kind.UPSTREAM_ERROR);
        assertThat(e.getMessage()).contains("502", "AAPL");
    }

    @Test
    void unparseableSuccessBodyIsAnUpstreamError() {
        server.expect(requestTo(BASE + "/quote?symbol=AAPL"))
                .andRespond(withSuccess("<html>maintenance</html>", MediaType.TEXT_HTML));

        MarketDataException e = failure(() -> provider.quote("AAPL"));

        assertThat(e.kind()).isEqualTo(Kind.UPSTREAM_ERROR);
        assertThat(e.getMessage()).startsWith("Could not get data for AAPL from Twelve Data");
    }

    @Test
    void connectionFailureIsRetriedOnceOnAFreshConnection() {
        // A pooled connection the server has already closed fails with "Connection reset" on reuse.
        server.expect(requestTo(BASE + "/quote?symbol=AAPL")).andRespond(withException(new IOException("Connection reset")));
        server.expect(requestTo(BASE + "/quote?symbol=AAPL"))
                .andRespond(withSuccess(Fixtures.read("twelvedata-quote.json"), MediaType.APPLICATION_JSON));

        assertThat(provider.quote("AAPL").price()).isEqualTo(350.53);
        server.verify();
    }

    @Test
    void connectionFailureTwiceInARowIsAnUpstreamError() {
        server.expect(ExpectedCount.twice(), requestTo(BASE + "/quote?symbol=AAPL"))
                .andRespond(withException(new IOException("Connection reset")));

        MarketDataException e = failure(() -> provider.quote("AAPL"));

        assertThat(e.kind()).isEqualTo(Kind.UPSTREAM_ERROR);
        assertThat(e.getMessage()).contains("Connection reset");
        server.verify(); // exactly two attempts, not more
    }

    @Test
    void errorResponsesAreNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/quote?symbol=AAPL"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.TEXT_PLAIN).body("slow down"));

        assertThat(failure(() -> provider.quote("AAPL")).kind()).isEqualTo(Kind.RATE_LIMITED);
        server.verify();
    }

    @Test
    void unreadableDateIsAnUpstreamError() {
        server.expect(requestTo(BASE + "/quote?symbol=AAPL")).andRespond(withSuccess("""
                {"symbol":"AAPL","datetime":"yesterday","close":"1","previous_close":"1"}
                """, MediaType.APPLICATION_JSON));

        MarketDataException e = failure(() -> provider.quote("AAPL"));

        assertThat(e.kind()).isEqualTo(Kind.UPSTREAM_ERROR);
        assertThat(e.getMessage()).contains("unreadable date");
    }

    private static MarketDataException failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        MarketDataException e = catchThrowableOfType(MarketDataException.class, call);
        assertThat(e).as("expected a MarketDataException").isNotNull();
        return e;
    }
}
