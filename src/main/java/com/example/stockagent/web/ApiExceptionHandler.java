package com.example.stockagent.web;

import com.example.stockagent.agent.AgentException;
import com.example.stockagent.marketdata.MarketDataException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** Turns domain failures into RFC 9457 problem responses with a status a client can act on. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(MarketDataException.class)
    ProblemDetail marketData(MarketDataException e) {
        HttpStatus status = switch (e.kind()) {
            case INVALID_SYMBOL -> HttpStatus.BAD_REQUEST;
            case SYMBOL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            // The data provider refused or failed; the caller did nothing wrong.
            case ACCESS_DENIED, UPSTREAM_ERROR -> HttpStatus.BAD_GATEWAY;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(switch (e.kind()) {
            case INVALID_SYMBOL -> "Invalid symbol";
            case SYMBOL_NOT_FOUND -> "Symbol not found";
            case RATE_LIMITED -> "Data provider rate limit reached";
            case NOT_CONFIGURED -> "Data provider not configured";
            case ACCESS_DENIED -> "Data provider refused the request";
            case UPSTREAM_ERROR -> "Data provider error";
        });
        problem.setProperty("kind", e.kind().name());
        return problem;
    }

    @ExceptionHandler(AgentException.class)
    ProblemDetail agent(AgentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setTitle("Language model unavailable");
        problem.setProperty("kind", "MODEL_ERROR");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid request");
        problem.setProperty("kind", "INVALID_REQUEST");
        return problem;
    }
}
