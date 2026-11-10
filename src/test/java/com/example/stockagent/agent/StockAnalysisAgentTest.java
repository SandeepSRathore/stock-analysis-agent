package com.example.stockagent.agent;

import com.example.stockagent.agent.StockReport.Confidence;
import com.example.stockagent.agent.StockReport.Outlook;
import com.example.stockagent.agent.ToolCallLog.ToolCall;
import com.example.stockagent.analysis.TechnicalAnalysisService;
import com.example.stockagent.analysis.TechnicalSnapshot.Trend;
import com.example.stockagent.marketdata.FakeProviders.FakeCompanyProvider;
import com.example.stockagent.marketdata.FakeProviders.FakePriceProvider;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.example.stockagent.marketdata.MarketDataProperties;
import com.example.stockagent.marketdata.MarketDataService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Runs the real agent (prompts, tools, memory, Spring AI's tool-calling loop) against a scripted
 * model and in-memory market data. No network.
 */
class StockAnalysisAgentTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
    private static final String REPORT_JSON = """
            {"companyName":"AAPL Inc.","outlook":"BULLISH","confidence":"MEDIUM",
             "headline":"Strong uptrend near highs.","summary":"Summary text.",
             "technicalView":"Technical text.","fundamentalView":"Fundamental text.","newsView":"News text.",
             "strengths":["Above both averages"],"risks":["Overbought"],"dataGaps":[]}
            """;

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final FakePriceProvider prices = new FakePriceProvider();
    private final FakeCompanyProvider company = new FakeCompanyProvider();
    private final ChatMemory memory = MessageWindowChatMemory.builder().build();
    private final StockAnalysisAgent agent = newAgent();

    private StockAnalysisAgent newAgent() {
        MarketDataService marketData = new MarketDataService(prices, company,
                new MarketDataProperties(320, 8, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(6), Duration.ofMinutes(15)),
                CLOCK);
        StockTools tools = new StockTools(marketData, new TechnicalAnalysisService(marketData));
        return new StockAnalysisAgent(ChatClient.builder(model), tools, memory, marketData, CLOCK,
                new ClassPathResource("prompts/system.st"), new ClassPathResource("prompts/analysis.st"), "test-model");
    }

    @Test
    void chatRunsTheToolTheModelAsksForAndFeedsTheResultBack() {
        model.thenCallTools("getQuote", "{\"symbol\":\"AAPL\"}").thenAnswer("AAPL trades at 359.");

        ChatReply reply = agent.chat(null, "What is Apple trading at?");

        assertThat(reply.answer()).isEqualTo("AAPL trades at 359.");
        assertThat(reply.conversationId()).isNotBlank();
        assertThat(reply.toolCalls()).containsExactly(new ToolCall("getQuote", "AAPL", true, null, reply.toolCalls().getFirst().durationMs()));
        assertThat(prices.quoteCalls).containsExactly("AAPL");

        // Two model calls: the question, then the question plus the tool result.
        assertThat(model.prompts).hasSize(2);
        Prompt second = model.prompts.get(1);
        assertThat(second.getInstructions()).last().isInstanceOfSatisfying(ToolResponseMessage.class, toolResponse -> {
            assertThat(toolResponse.getResponses()).singleElement().satisfies(response -> {
                assertThat(response.name()).isEqualTo("getQuote");
                assertThat(response.responseData()).contains("\"price\":359.0");
            });
        });
    }

    @Test
    void systemPromptCarriesTodaysDateAndTheGroundRules() {
        model.thenAnswer("Hello.");

        agent.chat(null, "hi");

        String system = model.prompts.getFirst().getSystemMessage().getText();
        assertThat(system)
                .contains("Today is Monday, 9 November 2026.")
                .contains("Every figure you state must come from a tool result")
                .contains("Do not tell them to buy, sell or hold")
                .doesNotContain("{today}");
    }

    @Test
    void userMessageReachesTheModelVerbatimEvenWithTemplateCharacters() {
        model.thenAnswer("ok");
        String message = "Compare {AAPL} with <MSFT> using {\"metric\": \"pe\"} please";

        agent.chat(null, message);

        assertThat(model.prompts.getFirst().getUserMessage().getText()).isEqualTo(message);
    }

    @Test
    void sameConversationIdCarriesEarlierTurnsButNotToolChatter() {
        model.thenCallTools("getQuote", "{\"symbol\":\"AAPL\"}").thenAnswer("AAPL trades at 359.")
                .thenAnswer("We were discussing AAPL.");

        String id = agent.chat(null, "What is Apple trading at?").conversationId();
        ChatReply followUp = agent.chat(id, "Which stock was that?");

        assertThat(followUp.conversationId()).isEqualTo(id);
        List<Message> sent = model.prompts.getLast().getInstructions();
        assertThat(sent).extracting(Message::getMessageType).containsExactly(
                MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(sent).extracting(Message::getText).containsSubsequence(
                "What is Apple trading at?", "AAPL trades at 359.", "Which stock was that?");
        assertThat(memory.get(id)).hasSize(4);
    }

    @Test
    void differentConversationsDoNotShareMemory() {
        model.thenAnswer("First.").thenAnswer("Second.");

        agent.chat("conversation-a", "Remember AAPL");
        agent.chat("conversation-b", "What did I ask?");

        assertThat(model.prompts.getLast().getInstructions()).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
    }

    @Test
    void toolFailureIsGivenToTheModelAndReportedInTheReply() {
        prices.failNext = new MarketDataException(Kind.SYMBOL_NOT_FOUND, "Unknown symbol ZZZZ.");
        model.thenCallTools("getQuote", "{\"symbol\":\"ZZZZ\"}").thenAnswer("I could not find ZZZZ.");

        ChatReply reply = agent.chat(null, "Price of ZZZZ?");

        assertThat(reply.answer()).isEqualTo("I could not find ZZZZ.");
        assertThat(reply.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.succeeded()).isFalse();
            assertThat(call.error()).isEqualTo("Unknown symbol ZZZZ.");
        });
        ToolResponseMessage toolResponse = (ToolResponseMessage) model.prompts.getLast().getInstructions().getLast();
        assertThat(toolResponse.getResponses().getFirst().responseData())
                .contains("Unknown symbol ZZZZ.").contains("SYMBOL_NOT_FOUND");
    }

    @Test
    void modelFailureBecomesAnAgentExceptionWithASafeMessage() {
        model.thenFail(new IllegalStateException("401 Incorrect API key provided: sk-secret"));

        AgentException e = catchThrowableOfType(AgentException.class, () -> agent.chat(null, "hi"));

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).isEqualTo("The language model request failed. See the server log for details.");
        assertThat(e.getCause()).hasMessageContaining("Incorrect API key");
    }

    @Test
    void emptyAnswerIsAnError() {
        model.thenAnswer("");

        assertThat(catchThrowableOfType(AgentException.class, () -> agent.chat(null, "hi"))).isNotNull();
    }

    @Test
    void analyzeReturnsTheParsedReportWithTheEvidenceBehindIt() {
        model.thenCallTools(
                        "getQuote", "{\"symbol\":\"AAPL\"}",
                        "getTechnicalAnalysis", "{\"symbol\":\"AAPL\"}",
                        "getCompanyFundamentals", "{\"symbol\":\"AAPL\"}",
                        "getCompanyNews", "{\"symbol\":\"AAPL\",\"days\":7}")
                .thenAnswer(REPORT_JSON);

        StockAnalysis analysis = agent.analyze(" aapl ");

        assertThat(analysis.symbol()).isEqualTo("AAPL");
        assertThat(analysis.model()).isEqualTo("test-model");
        assertThat(analysis.generatedAt()).isEqualTo(Instant.parse("2026-11-09T12:00:00Z"));
        assertThat(analysis.disclaimer()).isEqualTo("This is information, not investment advice.");

        assertThat(analysis.report().outlook()).isEqualTo(Outlook.BULLISH);
        assertThat(analysis.report().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(analysis.report().headline()).isEqualTo("Strong uptrend near highs.");
        assertThat(analysis.report().strengths()).containsExactly("Above both averages");
        assertThat(analysis.report().dataGaps()).isEmpty();

        assertThat(analysis.evidence().quote().price()).isEqualTo(359.0);
        assertThat(analysis.evidence().technicals().trend()).isEqualTo(Trend.STRONG_UPTREND);
        assertThat(analysis.evidence().fundamentals().peRatioTtm()).isEqualTo(30.0);
        assertThat(analysis.evidence().news().articleCount()).isEqualTo(2);
        assertThat(analysis.toolCalls()).extracting(ToolCall::tool).containsExactly(
                "getQuote", "getTechnicalAnalysis", "getCompanyFundamentals", "getCompanyNews");

        assertThat(model.prompts.getFirst().getUserMessage().getText())
                .contains("Produce a full analysis report for the stock AAPL.")
                .doesNotContain("{symbol}");
        // The pre-check and the model's getQuote share one cached provider call.
        assertThat(prices.quoteCalls).containsExactly("AAPL");
    }

    @Test
    void analyzeLeavesEvidenceNullForToolsThatFailedOrWereNotCalled() {
        StockAnalysisAgent priceOnlyAgent = priceOnlyAgent();
        model.thenCallTools(
                        "getQuote", "{\"symbol\":\"AAPL\"}",
                        "getCompanyFundamentals", "{\"symbol\":\"AAPL\"}")
                .thenAnswer(REPORT_JSON);

        StockAnalysis analysis = priceOnlyAgent.analyze("AAPL");

        assertThat(analysis.evidence().quote()).isNotNull();
        assertThat(analysis.evidence().technicals()).isNull();
        assertThat(analysis.evidence().fundamentals()).isNull();
        assertThat(analysis.evidence().news()).isNull();
        assertThat(analysis.toolCalls()).extracting(ToolCall::succeeded).containsExactly(true, false);
    }

    @Test
    void analyzeRejectsAnInvalidSymbolBeforeCallingTheModel() {
        MarketDataException e = catchThrowableOfType(MarketDataException.class, () -> agent.analyze("not a ticker"));

        assertThat(e.kind()).isEqualTo(Kind.INVALID_SYMBOL);
        assertThat(model.prompts).isEmpty();
        assertThat(prices.quoteCalls).isEmpty();
    }

    @Test
    void analyzeFailsFastOnAnUnknownSymbolWithoutSpendingModelTokens() {
        prices.failNext = new MarketDataException(Kind.SYMBOL_NOT_FOUND, "Unknown symbol ZZZZ.");

        MarketDataException e = catchThrowableOfType(MarketDataException.class, () -> agent.analyze("ZZZZ"));

        assertThat(e.kind()).isEqualTo(Kind.SYMBOL_NOT_FOUND);
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void analyzeReportsUnparseableModelOutputAsAnAgentError() {
        model.thenAnswer("Sorry, I cannot produce JSON today.");

        assertThat(catchThrowableOfType(AgentException.class, () -> agent.analyze("AAPL"))).isNotNull();
    }

    private StockAnalysisAgent priceOnlyAgent() {
        MarketDataService marketData = new MarketDataService(prices, (com.example.stockagent.marketdata.CompanyDataProvider) null,
                new MarketDataProperties(320, 8, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofHours(6), Duration.ofMinutes(15)),
                CLOCK);
        StockTools tools = new StockTools(marketData, new TechnicalAnalysisService(marketData));
        return new StockAnalysisAgent(ChatClient.builder(model), tools, memory, marketData, CLOCK,
                new ClassPathResource("prompts/system.st"), new ClassPathResource("prompts/analysis.st"), "test-model");
    }
}
