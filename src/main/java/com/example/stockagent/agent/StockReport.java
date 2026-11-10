package com.example.stockagent.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * The model's written assessment of one stock, returned as structured output.
 * It holds interpretation only; the figures it is based on travel separately as tool evidence.
 */
public record StockReport(
        @JsonPropertyDescription("Company name as reported by the tools; the ticker if no name was available")
        String companyName,

        @JsonPropertyDescription("Balance of the evidence across technicals, fundamentals and news")
        Outlook outlook,

        @JsonPropertyDescription("How strongly and consistently the data supports the outlook. LOW if key data was unavailable or signals conflict")
        Confidence confidence,

        @JsonPropertyDescription("One sentence, under 20 words, capturing the main takeaway")
        String headline,

        @JsonPropertyDescription("3 to 4 sentences a busy reader could stop after")
        String summary,

        @JsonPropertyDescription("2 to 4 sentences on trend, momentum, volatility and key levels, citing specific figures from getTechnicalAnalysis")
        String technicalView,

        @JsonPropertyDescription("2 to 4 sentences on valuation, profitability and growth, citing specific figures. If fundamentals were unavailable, say so here instead of guessing")
        String fundamentalView,

        @JsonPropertyDescription("2 to 4 sentences on what recent news implies. If news was unavailable or empty, say so here instead of guessing")
        String newsView,

        @JsonPropertyDescription("2 to 5 short evidence-based points in the stock's favour")
        List<String> strengths,

        @JsonPropertyDescription("2 to 5 short evidence-based risks or points against")
        List<String> risks,

        @JsonPropertyDescription("Each piece of data that could not be retrieved, with the reason. Empty if nothing was missing")
        List<String> dataGaps) {

    public enum Outlook {
        BULLISH, NEUTRAL, BEARISH
    }

    public enum Confidence {
        LOW, MEDIUM, HIGH
    }
}
