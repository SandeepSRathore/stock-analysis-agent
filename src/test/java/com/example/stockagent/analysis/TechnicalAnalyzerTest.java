package com.example.stockagent.analysis;

import com.example.stockagent.analysis.TechnicalSnapshot.RsiZone;
import com.example.stockagent.analysis.TechnicalSnapshot.Trend;
import com.example.stockagent.marketdata.Candle;
import com.example.stockagent.marketdata.PriceHistory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TechnicalAnalyzerTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    @Test
    void steadyRiseIsAStrongUptrendNearItsHigh() {
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(260, i -> 100 + i), false);

        assertThat(snapshot.trend()).isEqualTo(Trend.STRONG_UPTREND);
        assertThat(snapshot.lastClose()).isEqualTo(359.0);
        assertThat(snapshot.barsAnalyzed()).isEqualTo(260);
        assertThat(snapshot.asOf()).isEqualTo(START.plusDays(259));
        // SMA50 of the last 50 values 310..359 is 334.5; SMA200 of 160..359 is 259.5.
        assertThat(snapshot.movingAverages().sma50()).isEqualTo(334.5);
        assertThat(snapshot.movingAverages().sma200()).isEqualTo(259.5);
        assertThat(snapshot.movingAverages().priceVsSma50Percent()).isEqualTo(7.32);
        assertThat(snapshot.momentum().rsi14()).isEqualTo(100.0);
        assertThat(snapshot.momentum().rsiZone()).isEqualTo(RsiZone.OVERBOUGHT);
        assertThat(snapshot.momentum().macdHistogram()).isNotNull();
        // 252 sessions back the close was 107, so the 1-year return is (359-107)/107.
        assertThat(snapshot.returns().oneYearPercent()).isEqualTo(235.51);
        assertThat(snapshot.returns().oneWeekPercent()).isEqualTo(1.41);
        assertThat(snapshot.volatility().maxDrawdown1yPercent()).isEqualTo(0.0);
        assertThat(snapshot.caveats()).isEmpty();
        assertThat(snapshot.signals())
                .anyMatch(s -> s.contains("above its 50-day average, which is above the 200-day"))
                .anyMatch(s -> s.contains("within 3% of its 52-week high"))
                .anyMatch(s -> s.contains("overbought"));
    }

    @Test
    void steadyFallIsAStrongDowntrendNearItsLow() {
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(260, i -> 400 - i), false);

        assertThat(snapshot.trend()).isEqualTo(Trend.STRONG_DOWNTREND);
        assertThat(snapshot.momentum().rsiZone()).isEqualTo(RsiZone.OVERSOLD);
        assertThat(snapshot.returns().oneYearPercent()).isNegative();
        assertThat(snapshot.volatility().maxDrawdown1yPercent()).isNegative();
        assertThat(snapshot.signals())
                .anyMatch(s -> s.contains("below its 50-day average, which is below the 200-day"))
                .anyMatch(s -> s.contains("within 3% of its 52-week low"));
    }

    @Test
    void fiftyTwoWeekRangeUsesHighsAndLowsOfTheLastYearOnly() {
        // 300 bars: an early spike to 1000 falls outside the 252-bar window and must be ignored.
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(300, i -> i == 10 ? 1000 : 100), false);

        assertThat(snapshot.range().high52Week()).isEqualTo(101.0); // candle high is close * 1.01
        assertThat(snapshot.range().low52Week()).isEqualTo(99.0);
    }

    @Test
    void shortHistoryLeavesLongIndicatorsNullAndSaysWhy() {
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(30, i -> 50 + (i % 3)), false);

        assertThat(snapshot.trend()).isEqualTo(Trend.INSUFFICIENT_DATA);
        assertThat(snapshot.movingAverages().sma20()).isNotNull();
        assertThat(snapshot.movingAverages().sma50()).isNull();
        assertThat(snapshot.movingAverages().sma200()).isNull();
        assertThat(snapshot.movingAverages().priceVsSma200Percent()).isNull();
        assertThat(snapshot.momentum().rsi14()).isNotNull();
        assertThat(snapshot.momentum().macdLine()).isNotNull();
        assertThat(snapshot.momentum().macdSignal()).isNull();
        assertThat(snapshot.returns().threeMonthPercent()).isNull();
        assertThat(snapshot.returns().oneYearPercent()).isNull();
        assertThat(snapshot.caveats())
                .anyMatch(s -> s.contains("Only 30 sessions") && s.contains("200-day average and 50-day average are not available"))
                .anyMatch(s -> s.contains("52-week high/low"));
    }

    @Test
    void withOnlyFiftyDayAverageTrendFollowsPriceAgainstIt() {
        assertThat(TechnicalAnalyzer.analyze(history(100, i -> 100 + i), false).trend()).isEqualTo(Trend.UPTREND);
        assertThat(TechnicalAnalyzer.analyze(history(100, i -> 300 - i), false).trend()).isEqualTo(Trend.DOWNTREND);
    }

    @Test
    void trendClassificationCoversEveryArrangementOfPriceAndAverages() {
        assertThat(TechnicalAnalyzer.classifyTrend(120, 110.0, 100.0)).isEqualTo(Trend.STRONG_UPTREND);
        assertThat(TechnicalAnalyzer.classifyTrend(120, 100.0, 110.0)).isEqualTo(Trend.UPTREND);
        assertThat(TechnicalAnalyzer.classifyTrend(105, 110.0, 100.0)).isEqualTo(Trend.SIDEWAYS);
        assertThat(TechnicalAnalyzer.classifyTrend(105, 100.0, 110.0)).isEqualTo(Trend.SIDEWAYS);
        assertThat(TechnicalAnalyzer.classifyTrend(90, 110.0, 100.0)).isEqualTo(Trend.DOWNTREND);
        assertThat(TechnicalAnalyzer.classifyTrend(90, 100.0, 110.0)).isEqualTo(Trend.STRONG_DOWNTREND);
        assertThat(TechnicalAnalyzer.classifyTrend(90, null, null)).isEqualTo(Trend.INSUFFICIENT_DATA);
    }

    @Test
    void reportsGoldenCrossWithHowLongAgoItHappened() {
        // Flat at 100 (both averages equal), then rising from bar 240: the 50-day pulls above the
        // 200-day on that first rising bar, 9 sessions before the last bar (index 249).
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(250, i -> i < 240 ? 100 : 100 + (i - 239)), false);

        assertThat(snapshot.signals()).contains("Golden cross: the 50-day average crossed above the 200-day 9 sessions ago.");
    }

    @Test
    void reportsDeathCross() {
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(250, i -> i < 248 ? 100 : 100 - (i - 247)), false);

        assertThat(snapshot.signals()).contains("Death cross: the 50-day average crossed below the 200-day 1 session ago.");
    }

    @Test
    void doesNotReportACrossOlderThanTheLookbackWindow() {
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(300, i -> i < 240 ? 100 : 100 + (i - 239)), false);

        assertThat(snapshot.signals()).noneMatch(s -> s.contains("cross: the 50-day"));
    }

    @Test
    void reportsBreakoutAboveThePriorTwentySessionHigh() {
        // Flat at 100 (prior highs 101), then a jump to 110 on the last bar.
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(60, i -> i == 59 ? 110 : 100), false);

        assertThat(snapshot.range().resistance20Day()).isEqualTo(101.0);
        assertThat(snapshot.range().support20Day()).isEqualTo(99.0);
        assertThat(snapshot.signals())
                .contains("Price is above the high of the previous 20 sessions (breakout).")
                .contains("Price is above the upper Bollinger band (20, 2).");
    }

    @Test
    void volumeSpikeIsMeasuredAgainstThePriorTwentySessions() {
        List<Candle> candles = new ArrayList<>(history(40, i -> 100).candles());
        Candle last = candles.removeLast();
        candles.add(new Candle(last.date(), last.open(), last.high(), last.low(), last.close(), 3_000_000));

        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(new PriceHistory("TEST", "USD", "NASDAQ", candles), false);

        assertThat(snapshot.volume().lastCompletedSessionVolume()).isEqualTo(3_000_000);
        assertThat(snapshot.volume().average20Day()).isEqualTo(1_000_000);
        assertThat(snapshot.volume().relativeVolume()).isEqualTo(3.0);
        assertThat(snapshot.signals()).contains("Last completed session traded 3.0x its 20-day average volume.");
    }

    @Test
    void inProgressSessionIsFlaggedAndExcludedFromVolume() {
        List<Candle> candles = new ArrayList<>(history(40, i -> 100).candles());
        Candle last = candles.removeLast();
        // A partial session: tiny volume so far, price up.
        candles.add(new Candle(last.date(), 100, 106, 100, 105, 50_000));

        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(new PriceHistory("TEST", "USD", "NASDAQ", candles), true);

        assertThat(snapshot.sessionInProgress()).isTrue();
        assertThat(snapshot.lastClose()).isEqualTo(105.0);
        assertThat(snapshot.volume().lastCompletedSessionVolume()).isEqualTo(1_000_000);
        assertThat(snapshot.volume().relativeVolume()).isEqualTo(1.0);
        assertThat(snapshot.caveats()).anyMatch(s -> s.contains("still trading"));
    }

    @Test
    void implausiblyLowLatestVolumeIsTreatedAsNotYetPublished() {
        // What a provider returns just after the close: the latest bar has a partial volume count.
        List<Candle> candles = new ArrayList<>(history(40, i -> 100).candles());
        Candle last = candles.removeLast();
        candles.add(new Candle(last.date(), 100, 101, 99, 100, 30_000));

        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(new PriceHistory("TEST", "USD", "NASDAQ", candles), false);

        assertThat(snapshot.volume().lastCompletedSessionVolume()).isEqualTo(1_000_000);
        assertThat(snapshot.volume().relativeVolume()).isEqualTo(1.0);
        assertThat(snapshot.caveats()).anyMatch(s -> s.contains("reported volume (30,000) is far below normal"));
        assertThat(snapshot.asOf()).isEqualTo(last.date()); // the price bar itself is still used
    }

    @Test
    void aQuietButPlausibleSessionIsReportedAsIs() {
        List<Candle> candles = new ArrayList<>(history(40, i -> 100).candles());
        Candle last = candles.removeLast();
        candles.add(new Candle(last.date(), 100, 101, 99, 100, 400_000)); // 40% of average: e.g. a holiday half-day

        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(new PriceHistory("TEST", "USD", "NASDAQ", candles), false);

        assertThat(snapshot.volume().lastCompletedSessionVolume()).isEqualTo(400_000);
        assertThat(snapshot.volume().relativeVolume()).isEqualTo(0.4);
        assertThat(snapshot.caveats()).noneMatch(s -> s.contains("reported volume"));
    }

    @Test
    void subDollarPricesKeepFourDecimals() {
        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(history(30, i -> 0.12345 + i * 0.0001), false);

        assertThat(snapshot.lastClose()).isEqualTo(0.1264); // 0.12635 rounded half-up
        assertThat(snapshot.movingAverages().sma20()).isEqualTo(0.1254);
    }

    @Test
    void candlesAreAnalysedOldestFirstEvenWhenSuppliedNewestFirst() {
        List<Candle> reversed = new ArrayList<>(history(260, i -> 100 + i).candles()).reversed();

        TechnicalSnapshot snapshot = TechnicalAnalyzer.analyze(new PriceHistory("TEST", "USD", "NASDAQ", reversed), false);

        assertThat(snapshot.trend()).isEqualTo(Trend.STRONG_UPTREND);
        assertThat(snapshot.lastClose()).isEqualTo(359.0);
    }

    @Test
    void emptyHistoryIsRejected() {
        assertThatThrownBy(() -> TechnicalAnalyzer.analyze(new PriceHistory("TEST", "USD", "NASDAQ", List.of()), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TEST");
    }

    private static PriceHistory history(int bars, IntToDoubleFunction closeAt) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            double close = closeAt.applyAsDouble(i);
            candles.add(new Candle(START.plusDays(i), close, close * 1.01, close * 0.99, close, 1_000_000));
        }
        return new PriceHistory("TEST", "USD", "NASDAQ", candles);
    }
}
