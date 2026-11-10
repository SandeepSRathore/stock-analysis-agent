package com.example.stockagent.analysis;

import com.example.stockagent.analysis.Indicators.Bollinger;
import com.example.stockagent.analysis.Indicators.MacdSeries;
import com.example.stockagent.analysis.TechnicalSnapshot.Momentum;
import com.example.stockagent.analysis.TechnicalSnapshot.MovingAverages;
import com.example.stockagent.analysis.TechnicalSnapshot.PriceRange;
import com.example.stockagent.analysis.TechnicalSnapshot.Returns;
import com.example.stockagent.analysis.TechnicalSnapshot.RsiZone;
import com.example.stockagent.analysis.TechnicalSnapshot.Trend;
import com.example.stockagent.analysis.TechnicalSnapshot.Volatility;
import com.example.stockagent.analysis.TechnicalSnapshot.VolumeStats;
import com.example.stockagent.marketdata.Candle;
import com.example.stockagent.marketdata.PriceHistory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns daily bars into a {@link TechnicalSnapshot}. Pure and deterministic: the language model
 * never does this arithmetic, it only reads the result.
 */
public final class TechnicalAnalyzer {

    static final int YEAR = Indicators.TRADING_DAYS_PER_YEAR;
    private static final int CROSS_LOOKBACK = 30;
    private static final int MACD_CROSS_LOOKBACK = 5;
    private static final int RANGE_LOOKBACK = 20;
    /**
     * A finished session of a listed stock does not trade a fifth of its usual volume. A figure
     * that low on the latest bar means the provider has only a partial tally so far.
     */
    static final double PROVISIONAL_VOLUME_RATIO = 0.2;

    private TechnicalAnalyzer() {
    }

    /**
     * @param sessionInProgress whether the last candle is a session that is still trading
     */
    public static TechnicalSnapshot analyze(PriceHistory history, boolean sessionInProgress) {
        List<Candle> candles = history.candles();
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("No price history for " + history.symbol());
        }
        double[] closes = candles.stream().mapToDouble(Candle::close).toArray();
        int n = closes.length;
        double last = closes[n - 1];
        List<String> signals = new ArrayList<>();
        List<String> caveats = new ArrayList<>();

        if (sessionInProgress) {
            caveats.add("The latest bar is today's session, which is still trading: price-based readings "
                    + "can change before the close. Volume figures use the last completed session.");
        }

        // --- moving averages and trend
        double[] sma50Series = Indicators.smaSeries(closes, 50);
        double[] sma200Series = Indicators.smaSeries(closes, 200);
        Double sma20 = Indicators.sma(closes, 20);
        Double sma50 = Indicators.lastOrNull(sma50Series);
        Double sma200 = Indicators.lastOrNull(sma200Series);
        Trend trend = classifyTrend(last, sma50, sma200);
        if (sma200 == null) {
            caveats.add("Only " + n + " sessions of history: the 200-day average"
                    + (sma50 == null ? " and 50-day average are" : " is") + " not available.");
        }
        describeTrend(trend, signals);
        describeCross(sma50Series, sma200Series, signals);

        // --- momentum
        Double rsi = Indicators.rsi(closes, 14);
        RsiZone rsiZone = rsi == null ? null : rsi >= 70 ? RsiZone.OVERBOUGHT : rsi <= 30 ? RsiZone.OVERSOLD : RsiZone.NEUTRAL;
        if (rsiZone == RsiZone.OVERBOUGHT) {
            signals.add("RSI(14) is %.1f, in overbought territory (70 or above).".formatted(rsi));
        } else if (rsiZone == RsiZone.OVERSOLD) {
            signals.add("RSI(14) is %.1f, in oversold territory (30 or below).".formatted(rsi));
        }
        MacdSeries macd = Indicators.macdSeries(closes, 12, 26, 9);
        Double macdHistogram = Indicators.lastOrNull(macd.histogram());
        describeMacd(macd.histogram(), signals);

        // --- volatility
        Double atr = Indicators.atr(candles, 14);
        Bollinger bands = Indicators.bollinger(closes, 20, 2.0);
        if (bands != null && last > bands.upper()) {
            signals.add("Price is above the upper Bollinger band (20, 2).");
        } else if (bands != null && last < bands.lower()) {
            signals.add("Price is below the lower Bollinger band (20, 2).");
        }

        // --- 52-week range and recent support / resistance
        int rangeStart = Math.max(0, n - YEAR);
        double high52 = candles.subList(rangeStart, n).stream().mapToDouble(Candle::high).max().orElseThrow();
        double low52 = candles.subList(rangeStart, n).stream().mapToDouble(Candle::low).min().orElseThrow();
        if (n < YEAR) {
            caveats.add("Only " + n + " sessions of history: the 52-week high/low, 1-year return and 1-year "
                    + "drawdown cover that shorter window or are not available.");
        }
        double percentBelowHigh = (last - high52) / high52 * 100;
        double percentAboveLow = (last - low52) / low52 * 100;
        if (percentBelowHigh >= -3) {
            signals.add("Price is within 3% of its 52-week high.");
        } else if (percentAboveLow <= 3) {
            signals.add("Price is within 3% of its 52-week low.");
        }
        Double support = null;
        Double resistance = null;
        if (n > RANGE_LOOKBACK) {
            List<Candle> prior = candles.subList(n - 1 - RANGE_LOOKBACK, n - 1);
            support = prior.stream().mapToDouble(Candle::low).min().orElseThrow();
            resistance = prior.stream().mapToDouble(Candle::high).max().orElseThrow();
            if (last > resistance) {
                signals.add("Price is above the high of the previous 20 sessions (breakout).");
            } else if (last < support) {
                signals.add("Price is below the low of the previous 20 sessions (breakdown).");
            }
        }

        // --- volume, on completed sessions only
        VolumeStats volume = volumeStats(candles, sessionInProgress, signals, caveats);

        return new TechnicalSnapshot(
                history.symbol(),
                history.currency(),
                candles.getLast().date(),
                sessionInProgress,
                n,
                price(last),
                trend,
                new MovingAverages(price(sma20), price(sma50), price(sma200),
                        round(percentVs(last, sma50), 2), round(percentVs(last, sma200), 2)),
                new Momentum(round(rsi, 1), rsiZone,
                        round(Indicators.lastOrNull(macd.line()), 3),
                        round(Indicators.lastOrNull(macd.signal()), 3),
                        round(macdHistogram, 3)),
                new Volatility(price(atr),
                        round(atr == null ? null : atr / last * 100, 2),
                        round(Indicators.annualizedVolatilityPercent(closes, 30), 1),
                        price(bands == null ? null : bands.upper()),
                        price(bands == null ? null : bands.middle()),
                        price(bands == null ? null : bands.lower()),
                        round(bands == null ? null : bands.percentB(), 2),
                        round(Indicators.maxDrawdownPercent(closes, YEAR), 1)),
                new PriceRange(price(high52), price(low52), round(percentBelowHigh, 2), round(percentAboveLow, 2),
                        price(support), price(resistance)),
                new Returns(round(Indicators.percentChange(closes, 5), 2),
                        round(Indicators.percentChange(closes, 21), 2),
                        round(Indicators.percentChange(closes, 63), 2),
                        round(Indicators.percentChange(closes, 126), 2),
                        round(Indicators.percentChange(closes, YEAR), 2)),
                volume,
                List.copyOf(signals),
                List.copyOf(caveats));
    }

    static Trend classifyTrend(double last, Double sma50, Double sma200) {
        if (sma50 == null) {
            return Trend.INSUFFICIENT_DATA;
        }
        if (sma200 == null) {
            return last >= sma50 ? Trend.UPTREND : Trend.DOWNTREND;
        }
        boolean above50 = last > sma50;
        boolean above200 = last > sma200;
        boolean fastAboveSlow = sma50 > sma200;
        if (above50 && above200) {
            return fastAboveSlow ? Trend.STRONG_UPTREND : Trend.UPTREND;
        }
        if (!above50 && !above200) {
            return fastAboveSlow ? Trend.DOWNTREND : Trend.STRONG_DOWNTREND;
        }
        return Trend.SIDEWAYS;
    }

    private static void describeTrend(Trend trend, List<String> signals) {
        switch (trend) {
            case STRONG_UPTREND -> signals.add("Price is above its 50-day average, which is above the 200-day average.");
            case UPTREND -> signals.add("Price is above its moving averages, but the 50-day has not confirmed above the 200-day.");
            case SIDEWAYS -> signals.add("Price sits between its 50-day and 200-day averages: no clear trend.");
            case DOWNTREND -> signals.add("Price is below its moving averages, though the 50-day is still above the 200-day.");
            case STRONG_DOWNTREND -> signals.add("Price is below its 50-day average, which is below the 200-day average.");
            case INSUFFICIENT_DATA -> { }
        }
    }

    /** Reports the most recent 50/200-day crossover if it happened within the lookback window. */
    private static void describeCross(double[] sma50, double[] sma200, List<String> signals) {
        int n = sma50.length;
        for (int i = n - 1; i >= Math.max(1, n - CROSS_LOOKBACK); i--) {
            double today = sma50[i] - sma200[i];
            double yesterday = sma50[i - 1] - sma200[i - 1];
            if (Double.isNaN(today) || Double.isNaN(yesterday)) {
                return;
            }
            if (today > 0 && yesterday <= 0) {
                signals.add("Golden cross: the 50-day average crossed above the 200-day " + sessionsAgo(n - 1 - i) + ".");
                return;
            }
            if (today < 0 && yesterday >= 0) {
                signals.add("Death cross: the 50-day average crossed below the 200-day " + sessionsAgo(n - 1 - i) + ".");
                return;
            }
        }
    }

    private static void describeMacd(double[] histogram, List<String> signals) {
        int n = histogram.length;
        if (n == 0 || Double.isNaN(histogram[n - 1])) {
            return;
        }
        for (int i = n - 1; i >= Math.max(1, n - MACD_CROSS_LOOKBACK); i--) {
            if (Double.isNaN(histogram[i - 1])) {
                break;
            }
            if (histogram[i] > 0 && histogram[i - 1] <= 0) {
                signals.add("MACD crossed above its signal line " + sessionsAgo(n - 1 - i) + " (bullish crossover).");
                return;
            }
            if (histogram[i] < 0 && histogram[i - 1] >= 0) {
                signals.add("MACD crossed below its signal line " + sessionsAgo(n - 1 - i) + " (bearish crossover).");
                return;
            }
        }
        signals.add(histogram[n - 1] >= 0
                ? "MACD is above its signal line (positive momentum)."
                : "MACD is below its signal line (negative momentum).");
    }

    private static VolumeStats volumeStats(List<Candle> candles, boolean sessionInProgress,
                                           List<String> signals, List<String> caveats) {
        List<Candle> completed = sessionInProgress ? candles.subList(0, candles.size() - 1) : candles;
        if (!sessionInProgress && completed.size() > 21) {
            // Data providers publish a session's consolidated volume some time after the close; until
            // then the latest bar carries a partial count. Comparing that to the average would look
            // like a collapse in trading, so fall back to the session before it.
            double priorAverage = averageVolume(completed.subList(completed.size() - 21, completed.size() - 1));
            long latest = completed.getLast().volume();
            if (priorAverage > 0 && latest < PROVISIONAL_VOLUME_RATIO * priorAverage) {
                caveats.add("The latest session's reported volume (%,d) is far below normal, which usually means the "
                        .formatted(latest)
                        + "data provider has not published the final figure yet. Volume figures use the session before it.");
                completed = completed.subList(0, completed.size() - 1);
            }
        }
        if (completed.isEmpty()) {
            return new VolumeStats(null, null, null);
        }
        long lastVolume = completed.getLast().volume();
        if (completed.size() < 21) {
            return new VolumeStats(lastVolume, null, null);
        }
        // Average of the 20 sessions before the last completed one, so a spike is not diluted by itself.
        double average = averageVolume(completed.subList(completed.size() - 21, completed.size() - 1));
        Double relative = average == 0 ? null : lastVolume / average;
        if (relative != null && relative >= 1.5) {
            signals.add("Last completed session traded %.1fx its 20-day average volume.".formatted(relative));
        }
        return new VolumeStats(lastVolume, Math.round(average), round(relative, 2));
    }

    private static double averageVolume(List<Candle> sessions) {
        return sessions.stream().mapToLong(Candle::volume).average().orElseThrow();
    }

    private static String sessionsAgo(int sessions) {
        return switch (sessions) {
            case 0 -> "in the latest session";
            case 1 -> "1 session ago";
            default -> sessions + " sessions ago";
        };
    }

    private static Double percentVs(double last, Double reference) {
        return reference == null || reference == 0 ? null : (last - reference) / reference * 100;
    }

    /** Prices keep 2 decimals, or 4 for sub-dollar values where 2 would lose the signal. */
    static Double price(Double value) {
        if (value == null) {
            return null;
        }
        return round(value, Math.abs(value) < 1 ? 4 : 2);
    }

    static Double round(Double value, int digits) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }
}
