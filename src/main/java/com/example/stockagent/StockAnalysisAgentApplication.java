package com.example.stockagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockAnalysisAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAnalysisAgentApplication.class, args);
    }
}
