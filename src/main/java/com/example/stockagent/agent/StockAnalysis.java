package com.example.stockagent.agent;

import com.example.stockagent.agent.StockTools.CompanyNews;
import com.example.stockagent.agent.ToolCallLog.ToolCall;
import com.example.stockagent.analysis.TechnicalSnapshot;
import com.example.stockagent.marketdata.CompanyFundamentals;
import com.example.stockagent.marketdata.Quote;

import java.time.Instant;
import java.util.List;

/**
 * A full report on one stock.
 *
 * @param report   the model's written assessment
 * @param evidence the exact tool results the model was given, straight from Java; any figure in
 *                 the report can be checked against these
 */
public record StockAnalysis(
        String symbol,
        Instant generatedAt,
        String model,
        StockReport report,
        Evidence evidence,
        List<ToolCall> toolCalls,
        String disclaimer) {

    public static final String DISCLAIMER = "This is information, not investment advice.";

    /** Each field is null if the model did not call that tool or the call failed (see toolCalls). */
    public record Evidence(Quote quote, TechnicalSnapshot technicals, CompanyFundamentals fundamentals, CompanyNews news) {
    }
}
