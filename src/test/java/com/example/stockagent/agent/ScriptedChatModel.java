package com.example.stockagent.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A stand-in for the language model that plays back a fixed script, one reply per call, and
 * remembers every prompt it was sent. Lets tests drive the real tool-calling loop offline.
 */
class ScriptedChatModel implements ChatModel {

    final List<Prompt> prompts = new ArrayList<>();
    private final Deque<Object> script = new ArrayDeque<>();

    /** Next reply: the model asks for these tool calls. Each pair is (tool name, JSON arguments). */
    ScriptedChatModel thenCallTools(String... nameAndArguments) {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < nameAndArguments.length; i += 2) {
            calls.add(new ToolCall("call-" + script.size() + "-" + i, "function", nameAndArguments[i], nameAndArguments[i + 1]));
        }
        script.add(AssistantMessage.builder().content("").toolCalls(calls).build());
        return this;
    }

    /** Next reply: the model answers with this text. */
    ScriptedChatModel thenAnswer(String text) {
        script.add(new AssistantMessage(text));
        return this;
    }

    /** Next call fails as a provider outage would. */
    ScriptedChatModel thenFail(RuntimeException e) {
        script.add(e);
        return this;
    }

    /**
     * Real models advertise tool-calling options; ChatClient only attaches tools, and so only runs
     * its tool loop, when the model's options can carry them.
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Object next = script.pollFirst();
        if (next == null) {
            throw new IllegalStateException("The model was called more times than the test scripted");
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return new ChatResponse(List.of(new Generation((AssistantMessage) next)));
    }
}
