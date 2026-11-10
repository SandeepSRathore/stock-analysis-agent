package com.example.stockagent.marketdata;

import java.time.LocalDate;

/** One daily price bar. */
public record Candle(LocalDate date, double open, double high, double low, double close, long volume) {
}
