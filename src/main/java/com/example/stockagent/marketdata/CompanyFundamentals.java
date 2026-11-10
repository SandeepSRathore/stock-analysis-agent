package com.example.stockagent.marketdata;

import java.time.LocalDate;

/**
 * Company profile plus headline valuation, profitability and growth metrics.
 * Any metric the provider does not report is null; nothing is estimated.
 * Percent fields are already in percent (12.5 means 12.5%).
 */
public record CompanyFundamentals(
        String symbol,
        String name,
        String industry,
        String exchange,
        String country,
        String currency,
        LocalDate ipoDate,
        String website,
        Double marketCapMillions,
        Double sharesOutstandingMillions,
        Double peRatioTtm,
        Double priceToBook,
        Double priceToSalesTtm,
        Double epsTtm,
        Double dividendYieldPercent,
        Double beta,
        Double returnOnEquityPercent,
        Double netMarginPercent,
        Double grossMarginPercent,
        Double operatingMarginPercent,
        Double revenueGrowthYoyPercent,
        Double epsGrowthYoyPercent,
        Double currentRatio,
        Double debtToEquity) {
}
