package com.example.stockagent.agent;

/** The language model call failed or returned something unusable. The message is safe to show a user. */
public class AgentException extends RuntimeException {

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
