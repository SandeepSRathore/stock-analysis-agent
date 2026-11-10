package com.example.stockagent.analysis;

import com.example.stockagent.analysis.Indicators.Bollinger;
import com.example.stockagent.analysis.Indicators.MacdSeries;
import com.example.stockagent.marketdata.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Expected values were produced by a separate Python implementation (statistics module), and the
 * two RSI readings also match the worked example published for Wilder's RSI on this price series.
 */
class IndicatorsTest {

    private static final double[] CLOSES = {
            44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28,
            46.28, 46.00, 46.03, 46.41, 46.22, 45.64, 46.21, 46.25, 45.71, 46.45, 45.78, 45.35, 44.03, 44.18,
            44.22, 44.57, 43.42, 42.66, 43.13};

    private static final double TOLERANCE = 1e-9;

    @Test
    void simpleMovingAverageUsesOnlyTheLastPeriodValues() {
        assertThat(Indicators.sma(CLOSES, 5)).isCloseTo(43.6, within(TOLERANCE));
        assertThat(Indicators.sma(CLOSES, 20)).isCloseTo(45.241, within(TOLERANCE));
    }

    @Test
    void smaSeriesIsNanUntilEnoughValuesThenMatchesTheWindowMean() {
        double[] series = Indicators.smaSeries(new double[] {1, 2, 3, 4, 5}, 3);

        assertThat(series[0]).isNaN();
        assertThat(series[1]).isNaN();
        assertThat(series[2]).isCloseTo(2.0, within(TOLERANCE));
        assertThat(series[3]).isCloseTo(3.0, within(TOLERANCE));
        assertThat(series[4]).isCloseTo(4.0, within(TOLERANCE));
    }

    @Test
    void exponentialMovingAverageIsSeededWithTheSimpleAverage() {
        assertThat(Indicators.ema(CLOSES, 10)).isCloseTo(44.11929901522181, within(TOLERANCE));
        // With exactly `period` values the EMA is just their mean.
        assertThat(Indicators.ema(new double[] {2, 4, 6}, 3)).isCloseTo(4.0, within(TOLERANCE));
    }

    @Test
    void rsiMatchesWildersWorkedExample() {
        double[] firstFifteen = java.util.Arrays.copyOf(CLOSES, 15);

        assertThat(Indicators.rsi(firstFifteen, 14)).isCloseTo(70.46413502109705, within(TOLERANCE));
        assertThat(Indicators.rsi(CLOSES, 14)).isCloseTo(37.788771982057824, within(TOLERANCE));
    }

    @Test
    void rsiIsHundredWhenPriceOnlyRisesAndFiftyWhenFlat() {
        assertThat(Indicators.rsi(new double[] {1, 2, 3, 4, 5, 6}, 5)).isEqualTo(100.0);
        assertThat(Indicators.rsi(new double[] {3, 3, 3, 3, 3, 3}, 5)).isEqualTo(50.0);
    }

    @Test
    void macdLineSignalAndHistogramMatchReference() {
        MacdSeries macd = Indicators.macdSeries(CLOSES, 5, 10, 4);
        int last = CLOSES.length - 1;

        assertThat(macd.line()[last]).isCloseTo(-0.6082205441401882, within(TOLERANCE));
        assertThat(macd.signal()[last]).isCloseTo(-0.54101124231374, within(TOLERANCE));
        assertThat(macd.histogram()[last]).isCloseTo(-0.06720930182644824, within(TOLERANCE));
        assertThat(macd.histogram()[last - 1]).isCloseTo(-0.14132049310812167, within(TOLERANCE));
        // Line starts at index slow-1 = 9; signal needs 4 line values, so it starts at index 12.
        assertThat(macd.line()[8]).isNaN();
        assertThat(macd.line()[9]).isNotNaN();
        assertThat(macd.signal()[11]).isNaN();
        assertThat(macd.signal()[12]).isNotNaN();
    }

    @Test
    void bollingerBandsUsePopulationStandardDeviation() {
        Bollinger bands = Indicators.bollinger(CLOSES, 20, 2.0);

        assertThat(bands.middle()).isCloseTo(45.241, within(TOLERANCE));
        assertThat(bands.upper()).isCloseTo(47.62015026847822, within(TOLERANCE));
        assertThat(bands.lower()).isCloseTo(42.86184973152178, within(TOLERANCE));
        assertThat(bands.percentB()).isCloseTo(0.05635421016297137, within(TOLERANCE));
    }

    @Test
    void averageTrueRangeAccountsForGapsAndUsesWilderSmoothing() {
        // (high, low, close); true ranges are 2, 2, 2, 4, 1.5 -> ATR(3) = 2.2777...
        double[][] bars = {{10, 8, 9}, {11, 9, 10}, {12, 10, 11}, {11, 9, 9.5}, {13, 9, 12}, {12.5, 11, 11.5}};
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < bars.length; i++) {
            candles.add(new Candle(LocalDate.of(2026, 1, 1).plusDays(i), bars[i][2], bars[i][0], bars[i][1], bars[i][2], 1000));
        }

        assertThat(Indicators.atr(candles, 3)).isCloseTo(2.2777777777777777, within(TOLERANCE));
    }

    @Test
    void annualizedVolatilityMatchesReference() {
        assertThat(Indicators.annualizedVolatilityPercent(CLOSES, 20)).isCloseTo(20.763784835234198, within(1e-8));
    }

    @Test
    void maxDrawdownIsTheWorstPeakToTroughFall() {
        assertThat(Indicators.maxDrawdownPercent(CLOSES, CLOSES.length)).isCloseTo(-8.15931108719054, within(TOLERANCE));
        assertThat(Indicators.maxDrawdownPercent(new double[] {1, 2, 3}, 10)).isEqualTo(0.0);
        // Peak 100 -> trough 50 is -50%, even though the series ends higher.
        assertThat(Indicators.maxDrawdownPercent(new double[] {80, 100, 50, 90}, 10)).isCloseTo(-50.0, within(TOLERANCE));
    }

    @Test
    void maxDrawdownOnlyLooksAtTheLookbackWindow() {
        // The 100 -> 50 crash is outside the 2-bar window; inside it the price only rose.
        assertThat(Indicators.maxDrawdownPercent(new double[] {100, 50, 60, 70}, 2)).isEqualTo(0.0);
    }

    @Test
    void percentChangeComparesAgainstTheCloseThatManyBarsBack() {
        assertThat(Indicators.percentChange(CLOSES, 5)).isCloseTo(-2.376641014033498, within(TOLERANCE));
    }

    @Test
    void everyIndicatorReturnsNullWhenThereIsNotEnoughHistory() {
        double[] three = {1, 2, 3};

        assertThat(Indicators.sma(three, 4)).isNull();
        assertThat(Indicators.ema(three, 4)).isNull();
        assertThat(Indicators.rsi(three, 3)).isNull();
        assertThat(Indicators.bollinger(three, 4, 2)).isNull();
        assertThat(Indicators.annualizedVolatilityPercent(three, 3)).isNull();
        assertThat(Indicators.percentChange(three, 3)).isNull();
        assertThat(Indicators.maxDrawdownPercent(new double[] {1}, 5)).isNull();
        assertThat(Indicators.atr(List.of(), 3)).isNull();
        assertThat(Indicators.lastOrNull(Indicators.macdSeries(three, 2, 3, 2).histogram())).isNull();
    }
}
