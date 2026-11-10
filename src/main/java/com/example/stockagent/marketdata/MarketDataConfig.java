package com.example.stockagent.marketdata;

import com.example.stockagent.marketdata.finnhub.FinnhubCompanyProvider;
import com.example.stockagent.marketdata.finnhub.FinnhubProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class MarketDataConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Fundamentals and news are optional: without a Finnhub key the agent runs on price data alone. */
    @Bean
    @Conditional(FinnhubKeyPresent.class)
    CompanyDataProvider finnhubCompanyProvider(RestClient.Builder builder, FinnhubProperties properties) {
        return new FinnhubCompanyProvider(builder, properties);
    }

    /** True when the key is non-blank; a plain property condition would also match an empty value. */
    static class FinnhubKeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("stockagent.finnhub.api-key"));
        }
    }
}
