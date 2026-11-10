package com.example.stockagent.marketdata;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Latest price for one symbol.
 *
 * @param tradingDay the exchange-local date of the session this quote belongs to
 * @param marketOpen true while that session is still trading, so volume and close are not final
 * @param volume     the session's volume as reported so far
 * @param volumeProvisional true when {@code volume} is known or suspected to be incomplete and
 *                   must not be compared with {@code averageVolume}
 */
public record Quote(
        String symbol,
        String name,
        String exchange,
        String currency,
        double price,
        double previousClose,
        double change,
        double changePercent,
        double open,
        double dayHigh,
        double dayLow,
        long volume,
        boolean volumeProvisional,
        Long averageVolume,
        Double fiftyTwoWeekHigh,
        Double fiftyTwoWeekLow,
        LocalDate tradingDay,
        boolean marketOpen,
        Instant asOf) {
}
