package com.example.stockagent.marketdata;

import java.time.LocalDate;
import java.util.List;

/** Source of company fundamentals and news. Implementations throw {@link MarketDataException}. */
public interface CompanyDataProvider {

    String name();

    CompanyFundamentals fundamentals(String symbol);

    /** Articles published between the two dates (inclusive), newest first. */
    List<NewsArticle> news(String symbol, LocalDate from, LocalDate to);
}
