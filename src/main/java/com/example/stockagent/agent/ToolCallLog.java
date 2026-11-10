package com.example.stockagent.agent;

import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records what the model's tool calls actually did during one request, so the response can show
 * the exact data the answer was based on rather than the model's retelling of it.
 *
 * <p>One instance per request, handed to the tools through Spring AI's {@link ToolContext}
 * (which is never sent to the model).
 */
public final class ToolCallLog {

    public static final String CONTEXT_KEY = "toolCallLog";

    /**
     * @param error null when the call succeeded
     */
    public record ToolCall(String tool, String symbol, boolean succeeded, String error, long durationMs) {
    }

    private final List<ToolCall> calls = new ArrayList<>();
    private final Map<String, Object> results = new LinkedHashMap<>();

    /** The log for the current request, or a throwaway one if a tool is invoked outside a request. */
    public static ToolCallLog from(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext().get(CONTEXT_KEY) instanceof ToolCallLog log) {
            return log;
        }
        return new ToolCallLog();
    }

    public Map<String, Object> asToolContext() {
        return Map.of(CONTEXT_KEY, this);
    }

    synchronized void recordSuccess(String tool, String symbol, Object result, long durationMs) {
        calls.add(new ToolCall(tool, symbol, true, null, durationMs));
        results.put(key(tool, symbol), result);
    }

    synchronized void recordFailure(String tool, String symbol, String error, long durationMs) {
        calls.add(new ToolCall(tool, symbol, false, error, durationMs));
    }

    public synchronized List<ToolCall> calls() {
        return List.copyOf(calls);
    }

    /** The most recent successful result of {@code tool} for {@code symbol}, if the model called it. */
    public synchronized <T> Optional<T> result(String tool, String symbol, Class<T> type) {
        return Optional.ofNullable(results.get(key(tool, symbol))).filter(type::isInstance).map(type::cast);
    }

    private static String key(String tool, String symbol) {
        return tool + "|" + symbol;
    }
}
