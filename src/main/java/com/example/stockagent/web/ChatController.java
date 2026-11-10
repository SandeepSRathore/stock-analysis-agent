package com.example.stockagent.web;

import com.example.stockagent.agent.ChatReply;
import com.example.stockagent.agent.StockAnalysisAgent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final StockAnalysisAgent agent;

    public ChatController(StockAnalysisAgent agent) {
        this.agent = agent;
    }

    /**
     * @param conversationId omit to start a new conversation; send back the id from the reply to continue one
     */
    public record ChatRequest(
            @Size(max = 64, message = "must be at most 64 characters")
            @Pattern(regexp = "[A-Za-z0-9_-]*", message = "may only contain letters, digits, '-' and '_'")
            String conversationId,

            @NotBlank(message = "must not be blank")
            @Size(max = 2000, message = "must be at most 2000 characters")
            String message) {
    }

    @PostMapping
    public ChatReply chat(@Valid @RequestBody ChatRequest request) {
        return agent.chat(request.conversationId(), request.message().strip());
    }
}
