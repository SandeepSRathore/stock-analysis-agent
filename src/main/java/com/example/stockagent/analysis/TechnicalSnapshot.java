package com.example.stockagent.analysis;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the agent knows about a stock's price action, computed in Java from daily bars.
 * A null field means there was not enough history to compute it; {@link #caveats} says why.
 *
 * @param asOf              date of the latest bar included
 * @param sessionInProgress true when the latest bar is today's still-trading session, so
 *                          price-based readings can move before the close
 * @param signals           plain-English observations derived from the numbers
 * @param caveats           limits on how far the numbers can be trusted
 */
public record TechnicalSnapshot(
        String symbol,
        String currency,
        LocalDate asOf,
        boolean sessionInProgress,
        int barsAnalyzed,
        double lastClose,
        Trend trend,
        MovingAverages movingAverages,
        Momentum momentum,
        Volatility volatility,
        PriceRange range,
        Returns returns,
        VolumeStats volume,
        List<String> signals,
        List<String> caveats) {

    public enum Trend {
        /** Price above the 50-day average, which is above the 200-day average. */
        STRONG_UPTREND,
        /** Price above both averages, but the 50-day has not yet crossed above the 200-day. */
        UPTREND,
        /** Price sits between the two averages. */
        SIDEWAYS,
        /** Price below both averages, but the 50-day is still above the 200-day. */
        DOWNTREND,
        /** Price below the 50-day average, which is below the 200-day average. */
        STRONG_DOWNTREND,
        INSUFFICIENT_DATA
    }

    public enum RsiZone {
        OVERBOUGHT, NEUTRAL, OVERSOLD
    }

    public record MovingAverages(
            Double sma20,
            Double sma50,
            Double sma200,
            Double priceVsSma50Percent,
            Double priceVsSma200Percent) {
    }

    public record Momentum(
            Double rsi14,
            RsiZone rsiZone,
            Double macdLine,
            Double macdSignal,
            Double macdHistogram) {
    }

    public record Volatility(
            Double atr14,
            Double atrPercentOfPrice,
            Double annualizedVolatility30dPercent,
            Double bollingerUpper,
            Double bollingerMiddle,
            Double bollingerLower,
            Double bollingerPercentB,
            Double maxDrawdown1yPercent) {
    }

    /**
     * @param support20Day    lowest low of the 20 sessions before the latest bar
     * @param resistance20Day highest high of the 20 sessions before the latest bar
     */
    public record PriceRange(
            Double high52Week,
            Double low52Week,
            Double percentBelow52WeekHigh,
            Double percentAbove52WeekLow,
            Double support20Day,
            Double resistance20Day) {
    }

    public record Returns(
            Double oneWeekPercent,
            Double oneMonthPercent,
            Double threeMonthPercent,
            Double sixMonthPercent,
            Double oneYearPercent) {
    }

    /** Volume figures always describe completed sessions, never a session still in progress. */
    public record VolumeStats(Long lastCompletedSessionVolume, Long average20Day, Double relativeVolume) {
    }
}
