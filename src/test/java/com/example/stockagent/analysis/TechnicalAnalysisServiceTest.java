package com.example.stockagent.analysis;

import com.example.stockagent.marketdata.FakeProviders.FakePriceProvider;
import com.example.stockagent.marketdata.CompanyDataProvider;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.example.stockagent.marketdata.MarketDataProperties;
import com.example.stockagent.marketdata.MarketDataService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TechnicalAnalysisServiceTest {

    private final FakePriceProvider prices = new FakePriceProvider();
    private final TechnicalAnalysisService service = new TechnicalAnalysisService(new MarketDataService(
            prices, (CompanyDataProvider) null,
            new MarketDataProperties(320, 8, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(6), Duration.ofMinutes(15)),
            Clock.systemUTC()));

    @Test
    void closedMarketMeansTheLatestBarIsFinal() {
        prices.marketOpen = false;

        TechnicalSnapshot snapshot = service.analyze("aapl");

        assertThat(snapshot.symbol()).isEqualTo("AAPL");
        assertThat(snapshot.sessionInProgress()).isFalse();
        assertThat(snapshot.asOf()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(snapshot.barsAnalyzed()).isEqualTo(260);
    }

    @Test
    void openMarketWithTodaysBarInTheSeriesIsInProgress() {
        prices.marketOpen = true;

        assertThat(service.analyze("AAPL").sessionInProgress()).isTrue();
    }

    @Test
    void openMarketButSeriesEndingOnAnEarlierDayIsNotInProgress() {
        // e.g. just after the open, before the provider has published today's bar
        FakePriceProvider stale = new FakePriceProvider() {
            @Override
            public com.example.stockagent.marketdata.Quote quote(String symbol) {
                return com.example.stockagent.marketdata.FakeProviders.quote(symbol, 359, LocalDate.of(2026, 11, 10), true);
            }
        };
        TechnicalAnalysisService staleService = new TechnicalAnalysisService(new MarketDataService(
                stale, (CompanyDataProvider) null,
                new MarketDataProperties(320, 8, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(6), Duration.ofMinutes(15)),
                Clock.systemUTC()));

        assertThat(staleService.analyze("AAPL").sessionInProgress()).isFalse();
    }

    @Test
    void providerFailuresPropagate() {
        prices.failNext = new MarketDataException(Kind.SYMBOL_NOT_FOUND, "unknown");

        MarketDataException e = catchThrowableOfType(MarketDataException.class, () -> service.analyze("NOPE"));

        assertThat(e.kind()).isEqualTo(Kind.SYMBOL_NOT_FOUND);
    }
}
