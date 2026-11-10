package com.example.stockagent.web;

import com.example.stockagent.agent.AgentException;
import com.example.stockagent.agent.ChatReply;
import com.example.stockagent.agent.StockAnalysis;
import com.example.stockagent.agent.StockAnalysis.Evidence;
import com.example.stockagent.agent.StockAnalysisAgent;
import com.example.stockagent.agent.StockReport;
import com.example.stockagent.agent.StockReport.Confidence;
import com.example.stockagent.agent.StockReport.Outlook;
import com.example.stockagent.agent.ToolCallLog.ToolCall;
import com.example.stockagent.analysis.TechnicalAnalysisService;
import com.example.stockagent.analysis.TechnicalAnalyzer;
import com.example.stockagent.marketdata.FakeProviders;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.example.stockagent.marketdata.MarketDataService;
import com.example.stockagent.marketdata.NewsArticle;
import com.example.stockagent.marketdata.twelvedata.TwelveDataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@TestPropertySource(properties = {"spring.ai.openai.chat.model=test-model", "spring.mvc.problemdetails.enabled=true"})
class ApiControllersTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    @TestConfiguration
    static class Config {
        @Bean
        TwelveDataProperties twelveDataProperties() {
            return new TwelveDataProperties("https://twelvedata.test", "demo");
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    StockAnalysisAgent agent;

    @MockitoBean
    MarketDataService marketData;

    @MockitoBean
    TechnicalAnalysisService technicalAnalysis;

    @BeforeEach
    void providerNames() {
        given(marketData.priceProviderName()).willReturn("Twelve Data (demo key)");
        given(marketData.companyProviderName()).willReturn(Optional.empty());
    }

    // --- chat

    @Test
    void chatReturnsTheAgentsReply() throws Exception {
        given(agent.chat("conv-1", "Is AAPL in an uptrend?")).willReturn(new ChatReply("conv-1", "Yes, a strong one.",
                List.of(new ToolCall("getTechnicalAnalysis", "AAPL", true, null, 12))));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv-1\",\"message\":\"  Is AAPL in an uptrend?  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.answer").value("Yes, a strong one."))
                .andExpect(jsonPath("$.toolCalls[0].tool").value("getTechnicalAnalysis"))
                .andExpect(jsonPath("$.toolCalls[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.toolCalls[0].succeeded").value(true));
    }

    @Test
    void chatWithoutAConversationIdStartsANewConversation() throws Exception {
        given(agent.chat(null, "hello")).willReturn(new ChatReply("generated-id", "Hi.", List.of()));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("generated-id"));

        verify(agent).chat(null, "hello");
    }

    @Test
    void blankMessageAndBadConversationIdAreRejectedBeforeReachingTheAgent() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"bad id!\",\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.kind").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value(
                        "conversationId may only contain letters, digits, '-' and '_'; message must not be blank"));

        verifyNoInteractions(agent);
    }

    @Test
    void overlongMessageIsRejected() throws Exception {
        String body = "{\"message\":\"" + "x".repeat(2001) + "\"}";

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("message must be at most 2000 characters"));

        verifyNoInteractions(agent);
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(agent);
    }

    @Test
    void modelFailureIsABadGatewayWithASafeMessage() throws Exception {
        given(agent.chat(any(), any())).willThrow(new AgentException(
                "The language model request failed. See the server log for details.", new RuntimeException("sk-secret")));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hello\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.kind").value("MODEL_ERROR"))
                .andExpect(jsonPath("$.title").value("Language model unavailable"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("sk-secret"))));
    }

    // --- analysis

    @Test
    void analysisReturnsReportEvidenceAndToolCalls() throws Exception {
        StockReport report = new StockReport("Apple Inc.", Outlook.BULLISH, Confidence.MEDIUM, "Headline.", "Summary.",
                "Technical.", "Fundamental.", "News.", List.of("Strength"), List.of("Risk"), List.of());
        Evidence evidence = new Evidence(
                FakeProviders.quote("AAPL", 359, DAY, false),
                TechnicalAnalyzer.analyze(FakeProviders.history("AAPL", 260, DAY, i -> 100 + i), false),
                null, null);
        given(agent.analyze("AAPL")).willReturn(new StockAnalysis("AAPL", Instant.parse("2026-11-09T21:00:00Z"), "test-model",
                report, evidence, List.of(new ToolCall("getQuote", "AAPL", true, null, 3)), StockAnalysis.DISCLAIMER));

        mvc.perform(get("/api/analysis/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.generatedAt").value("2026-11-09T21:00:00Z"))
                .andExpect(jsonPath("$.report.outlook").value("BULLISH"))
                .andExpect(jsonPath("$.report.confidence").value("MEDIUM"))
                .andExpect(jsonPath("$.report.strengths[0]").value("Strength"))
                .andExpect(jsonPath("$.evidence.quote.price").value(359.0))
                .andExpect(jsonPath("$.evidence.quote.tradingDay").value("2026-11-09"))
                .andExpect(jsonPath("$.evidence.technicals.trend").value("STRONG_UPTREND"))
                .andExpect(jsonPath("$.evidence.technicals.movingAverages.sma200").value(259.5))
                .andExpect(jsonPath("$.evidence.fundamentals").value(nullValue()))
                .andExpect(jsonPath("$.toolCalls[0].tool").value("getQuote"))
                .andExpect(jsonPath("$.disclaimer").value("This is information, not investment advice."));
    }

    @ParameterizedTest
    @CsvSource({
            "INVALID_SYMBOL,   400, Invalid symbol",
            "SYMBOL_NOT_FOUND, 404, Symbol not found",
            "RATE_LIMITED,     429, Data provider rate limit reached",
            "NOT_CONFIGURED,   503, Data provider not configured",
            "ACCESS_DENIED,    502, Data provider refused the request",
            "UPSTREAM_ERROR,   502, Data provider error"})
    void marketDataFailuresMapToStatusesAClientCanActOn(Kind kind, int expectedStatus, String expectedTitle) throws Exception {
        given(agent.analyze("XYZ")).willThrow(new MarketDataException(kind, "Explanation for the user."));

        mvc.perform(get("/api/analysis/XYZ"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.kind").value(kind.name()))
                .andExpect(jsonPath("$.title").value(expectedTitle))
                .andExpect(jsonPath("$.detail").value("Explanation for the user."))
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    // --- raw market data

    @Test
    void quoteEndpointReturnsTheProviderData() throws Exception {
        given(marketData.quote("aapl")).willReturn(FakeProviders.quote("AAPL", 359, DAY, false));

        mvc.perform(get("/api/stocks/aapl/quote"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.price").value(359.0))
                .andExpect(jsonPath("$.marketOpen").value(false))
                .andExpect(jsonPath("$.asOf").value("2026-11-09T21:00:00Z"));
    }

    @Test
    void technicalsEndpointReturnsTheSnapshot() throws Exception {
        given(technicalAnalysis.analyze("AAPL"))
                .willReturn(TechnicalAnalyzer.analyze(FakeProviders.history("AAPL", 260, DAY, i -> 100 + i), false));

        mvc.perform(get("/api/stocks/AAPL/technicals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trend").value("STRONG_UPTREND"))
                .andExpect(jsonPath("$.asOf").value("2026-11-09"))
                .andExpect(jsonPath("$.momentum.rsi14").value(100.0))
                .andExpect(jsonPath("$.signals").isArray());
    }

    @Test
    void fundamentalsEndpointReportsAMissingProviderAsServiceUnavailable() throws Exception {
        given(marketData.fundamentals("AAPL")).willThrow(new MarketDataException(Kind.NOT_CONFIGURED, "Set FINNHUB_API_KEY."));

        mvc.perform(get("/api/stocks/AAPL/fundamentals"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Set FINNHUB_API_KEY."));
    }

    @Test
    void newsEndpointDefaultsToSevenDays() throws Exception {
        given(marketData.news("AAPL", 7)).willReturn(List.of(
                new NewsArticle("Headline", "Reuters", "Summary", "https://example.com/1", Instant.parse("2026-11-09T10:00:00Z"))));

        mvc.perform(get("/api/stocks/AAPL/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].headline").value("Headline"))
                .andExpect(jsonPath("$[0].publishedAt").value("2026-11-09T10:00:00Z"));

        verify(marketData).news("AAPL", 7);
    }

    @Test
    void newsEndpointPassesTheRequestedWindow() throws Exception {
        given(marketData.news("AAPL", 14)).willReturn(List.of());

        mvc.perform(get("/api/stocks/AAPL/news").param("days", "14")).andExpect(status().isOk());

        verify(marketData).news("AAPL", 14);
    }

    @Test
    void nonNumericDaysIsABadRequest() throws Exception {
        mvc.perform(get("/api/stocks/AAPL/news").param("days", "soon"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // --- status

    @Test
    void statusReportsModelAndDataSourcesWithoutKeys() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.priceDataProvider").value("Twelve Data (demo key)"))
                .andExpect(jsonPath("$.priceDataLimitedToDemoSymbols").value(true))
                .andExpect(jsonPath("$.companyDataProvider").value(nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("apiKey"))));
    }

    @Test
    void unknownApiRouteIsANotFoundProblem() throws Exception {
        mvc.perform(get("/api/nope")).andExpect(status().isNotFound());
    }
}
