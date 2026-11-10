package com.example.stockagent.agent;

import com.example.stockagent.agent.StockAnalysis.Evidence;
import com.example.stockagent.agent.StockTools.CompanyNews;
import com.example.stockagent.analysis.TechnicalSnapshot;
import com.example.stockagent.marketdata.CompanyFundamentals;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataService;
import com.example.stockagent.marketdata.Quote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The agent: a {@link ChatClient} with the stock tools attached. The model decides which tools to
 * call and in what order; Spring AI runs the call-tool-and-feed-back loop until it has an answer.
 */
@Service
public class StockAnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(StockAnalysisAgent.class);
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    private final ChatClient chatClient;
    private final MessageChatMemoryAdvisor memoryAdvisor;
    private final MarketDataService marketData;
    private final Resource analysisPrompt;
    private final Clock clock;
    private final String model;

    public StockAnalysisAgent(ChatClient.Builder chatClientBuilder,
                              StockTools stockTools,
                              ChatMemory chatMemory,
                              MarketDataService marketData,
                              Clock clock,
                              @Value("classpath:prompts/system.st") Resource systemPrompt,
                              @Value("classpath:prompts/analysis.st") Resource analysisPrompt,
                              @Value("${spring.ai.openai.chat.model:unknown}") String model) {
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultTools(stockTools)
                .build();
        this.memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        this.marketData = marketData;
        this.analysisPrompt = analysisPrompt;
        this.clock = clock;
        this.model = model;
    }

    /**
     * Free-form conversation. Turns that share a conversation id share memory, so follow-ups such
     * as "and how does that compare to MSFT?" work.
     *
     * @param conversationId null or blank starts a new conversation
     */
    public ChatReply chat(String conversationId, String message) {
        String id = StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
        ToolCallLog toolCalls = new ToolCallLog();
        String answer = callModel(() -> chatClient.prompt()
                .system(system -> system.param("today", today()))
                // Passed as a message rather than .user(text): user text is otherwise treated as a
                // prompt template, and a stray "{" in a question would break rendering.
                .messages(new UserMessage(message))
                .advisors(advisors -> advisors.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, id))
                .toolContext(toolCalls.asToolContext())
                .call()
                .content());
        if (!StringUtils.hasText(answer)) {
            throw new AgentException("The language model returned an empty answer. Please try again.", null);
        }
        return new ChatReply(id, answer, toolCalls.calls());
    }

    /**
     * One-shot structured report on a single stock.
     *
     * @throws MarketDataException if the symbol is invalid, unknown, or prices cannot be fetched;
     *                             raised before any model tokens are spent
     */
    public StockAnalysis analyze(String rawSymbol) {
        String symbol = MarketDataService.normalize(rawSymbol);
        // Fail fast on a bad ticker or an exhausted quota. The result is cached, so the model's
        // own getQuote call a moment later costs nothing extra.
        marketData.quote(symbol);

        ToolCallLog toolCalls = new ToolCallLog();
        StockReport report = callModel(() -> chatClient.prompt()
                .system(system -> system.param("today", today()))
                .user(user -> user.text(analysisPrompt).param("symbol", symbol))
                // Ask the provider to enforce the report's JSON schema instead of relying on prompt instructions.
                .advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT)
                .toolContext(toolCalls.asToolContext())
                .call()
                .entity(StockReport.class));
        if (report == null) {
            throw new AgentException("The language model did not return a report. Please try again.", null);
        }
        Evidence evidence = new Evidence(
                toolCalls.result(StockTools.QUOTE, symbol, Quote.class).orElse(null),
                toolCalls.result(StockTools.TECHNICALS, symbol, TechnicalSnapshot.class).orElse(null),
                toolCalls.result(StockTools.FUNDAMENTALS, symbol, CompanyFundamentals.class).orElse(null),
                toolCalls.result(StockTools.NEWS, symbol, CompanyNews.class).orElse(null));
        return new StockAnalysis(symbol, Instant.now(clock), model, report, evidence, toolCalls.calls(), StockAnalysis.DISCLAIMER);
    }

    private <T> T callModel(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.error("Language model call failed", e);
            throw new AgentException("The language model request failed. See the server log for details.", e);
        }
    }

    private String today() {
        return LocalDate.now(clock).format(TODAY);
    }
}
