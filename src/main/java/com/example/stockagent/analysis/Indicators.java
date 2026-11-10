package com.example.stockagent.analysis;

import com.example.stockagent.marketdata.Candle;

import java.util.Arrays;
import java.util.List;

/**
 * Textbook technical-indicator math on plain arrays, oldest value first.
 *
 * <p>Every method returns {@code null} (or a NaN-padded series) when there is not enough history,
 * so a caller can never mistake "not computable" for a real reading.
 */
public final class Indicators {

    public static final int TRADING_DAYS_PER_YEAR = 252;

    private Indicators() {
    }

    /** Simple moving average of the last {@code period} values. */
    public static Double sma(double[] values, int period) {
        if (period <= 0 || values.length < period) {
            return null;
        }
        return mean(values, values.length - period, values.length);
    }

    /** SMA at every index; NaN until {@code period} values are available. */
    public static double[] smaSeries(double[] values, int period) {
        double[] out = nan(values.length);
        if (period <= 0 || values.length < period) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            if (i >= period - 1) {
                out[i] = sum / period;
            }
        }
        return out;
    }

    /**
     * Exponential moving average at every index, seeded with the SMA of the first {@code period}
     * values; NaN before that.
     */
    public static double[] emaSeries(double[] values, int period) {
        double[] out = nan(values.length);
        if (period <= 0 || values.length < period) {
            return out;
        }
        double multiplier = 2.0 / (period + 1);
        double ema = mean(values, 0, period);
        out[period - 1] = ema;
        for (int i = period; i < values.length; i++) {
            ema = (values[i] - ema) * multiplier + ema;
            out[i] = ema;
        }
        return out;
    }

    public static Double ema(double[] values, int period) {
        return lastOrNull(emaSeries(values, period));
    }

    /** Relative Strength Index with Wilder's smoothing. Needs {@code period + 1} closes. */
    public static Double rsi(double[] closes, int period) {
        if (period <= 0 || closes.length < period + 1) {
            return null;
        }
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes[i] - closes[i - 1];
            avgGain += Math.max(change, 0);
            avgLoss += Math.max(-change, 0);
        }
        avgGain /= period;
        avgLoss /= period;
        for (int i = period + 1; i < closes.length; i++) {
            double change = closes[i] - closes[i - 1];
            avgGain = (avgGain * (period - 1) + Math.max(change, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-change, 0)) / period;
        }
        if (avgLoss == 0) {
            return avgGain == 0 ? 50.0 : 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100 - 100 / (1 + rs);
    }

    /**
     * MACD line (fast EMA minus slow EMA), its signal line (EMA of the MACD line) and the histogram,
     * at every index. Needs {@code slow + signal - 1} closes for the first complete reading.
     */
    public static MacdSeries macdSeries(double[] closes, int fast, int slow, int signal) {
        double[] fastEma = emaSeries(closes, fast);
        double[] slowEma = emaSeries(closes, slow);
        double[] line = nan(closes.length);
        double[] signalLine = nan(closes.length);
        double[] histogram = nan(closes.length);
        int firstLine = slow - 1;
        if (closes.length > firstLine) {
            for (int i = firstLine; i < closes.length; i++) {
                line[i] = fastEma[i] - slowEma[i];
            }
            double[] signalOfDefined = emaSeries(Arrays.copyOfRange(line, firstLine, closes.length), signal);
            for (int i = 0; i < signalOfDefined.length; i++) {
                signalLine[firstLine + i] = signalOfDefined[i];
                histogram[firstLine + i] = line[firstLine + i] - signalOfDefined[i];
            }
        }
        return new MacdSeries(line, signalLine, histogram);
    }

    /** Bollinger bands: SMA plus/minus {@code k} population standard deviations. */
    public static Bollinger bollinger(double[] closes, int period, double k) {
        Double middle = sma(closes, period);
        if (middle == null) {
            return null;
        }
        double sumSquares = 0;
        for (int i = closes.length - period; i < closes.length; i++) {
            sumSquares += Math.pow(closes[i] - middle, 2);
        }
        double deviation = Math.sqrt(sumSquares / period);
        double upper = middle + k * deviation;
        double lower = middle - k * deviation;
        double last = closes[closes.length - 1];
        Double percentB = upper == lower ? null : (last - lower) / (upper - lower);
        return new Bollinger(middle, upper, lower, percentB);
    }

    /** Average True Range with Wilder's smoothing. Needs {@code period + 1} candles. */
    public static Double atr(List<Candle> candles, int period) {
        if (period <= 0 || candles.size() < period + 1) {
            return null;
        }
        double[] trueRanges = new double[candles.size() - 1];
        for (int i = 1; i < candles.size(); i++) {
            Candle today = candles.get(i);
            double previousClose = candles.get(i - 1).close();
            trueRanges[i - 1] = Math.max(today.high() - today.low(),
                    Math.max(Math.abs(today.high() - previousClose), Math.abs(today.low() - previousClose)));
        }
        double atr = mean(trueRanges, 0, period);
        for (int i = period; i < trueRanges.length; i++) {
            atr = (atr * (period - 1) + trueRanges[i]) / period;
        }
        return atr;
    }

    /**
     * Annualised volatility in percent: sample standard deviation of the last {@code lookback}
     * daily log returns, scaled by the square root of 252.
     */
    public static Double annualizedVolatilityPercent(double[] closes, int lookback) {
        if (lookback < 2 || closes.length < lookback + 1) {
            return null;
        }
        double[] returns = new double[lookback];
        int start = closes.length - lookback;
        for (int i = 0; i < lookback; i++) {
            returns[i] = Math.log(closes[start + i] / closes[start + i - 1]);
        }
        double mean = mean(returns, 0, lookback);
        double sumSquares = 0;
        for (double r : returns) {
            sumSquares += Math.pow(r - mean, 2);
        }
        return Math.sqrt(sumSquares / (lookback - 1)) * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100;
    }

    /**
     * Largest peak-to-trough decline over the last {@code lookback} closes, as a negative percent
     * (0 when the series never fell). Uses whatever history exists if it is shorter than the lookback.
     */
    public static Double maxDrawdownPercent(double[] closes, int lookback) {
        if (closes.length < 2) {
            return null;
        }
        int start = Math.max(0, closes.length - lookback);
        double peak = closes[start];
        double worst = 0;
        for (int i = start; i < closes.length; i++) {
            peak = Math.max(peak, closes[i]);
            worst = Math.min(worst, (closes[i] - peak) / peak);
        }
        return worst * 100;
    }

    /** Percent change of the last close versus the close {@code barsBack} sessions earlier. */
    public static Double percentChange(double[] closes, int barsBack) {
        if (barsBack <= 0 || closes.length < barsBack + 1) {
            return null;
        }
        double then = closes[closes.length - 1 - barsBack];
        if (then == 0) {
            return null;
        }
        return (closes[closes.length - 1] - then) / then * 100;
    }

    static Double lastOrNull(double[] series) {
        if (series.length == 0 || Double.isNaN(series[series.length - 1])) {
            return null;
        }
        return series[series.length - 1];
    }

    private static double mean(double[] values, int fromInclusive, int toExclusive) {
        double sum = 0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            sum += values[i];
        }
        return sum / (toExclusive - fromInclusive);
    }

    private static double[] nan(int length) {
        double[] out = new double[length];
        Arrays.fill(out, Double.NaN);
        return out;
    }

    /** NaN-padded MACD series, aligned index-for-index with the input closes. */
    public record MacdSeries(double[] line, double[] signal, double[] histogram) {
    }

    /** @param percentB where the last close sits in the band: 0 = lower band, 1 = upper band */
    public record Bollinger(double middle, double upper, double lower, Double percentB) {
    }
}
