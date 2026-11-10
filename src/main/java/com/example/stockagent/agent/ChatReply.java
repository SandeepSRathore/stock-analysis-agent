package com.example.stockagent.agent;

import com.example.stockagent.agent.ToolCallLog.ToolCall;

import java.util.List;

/**
 * @param conversationId send this back with the next message to continue the same conversation
 * @param toolCalls      the data lookups the model made to produce this answer, in order
 */
public record ChatReply(String conversationId, String answer, List<ToolCall> toolCalls) {
}
