package com.example.stockagent.web;

import com.example.stockagent.agent.StockAnalysis;
import com.example.stockagent.agent.StockAnalysisAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final StockAnalysisAgent agent;

    public AnalysisController(StockAnalysisAgent agent) {
        this.agent = agent;
    }

    /** Runs the agent on one ticker and returns its structured report plus the data behind it. */
    @GetMapping("/{symbol}")
    public StockAnalysis analyze(@PathVariable String symbol) {
        return agent.analyze(symbol);
    }
}
