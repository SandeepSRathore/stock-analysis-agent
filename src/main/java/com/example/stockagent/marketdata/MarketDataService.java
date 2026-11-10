package com.example.stockagent.marketdata;

import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Single entry point for market data: validates symbols, picks the provider and caches results
 * briefly so an agent that asks for the same thing twice does not spend a second API credit.
 * Failures are never cached.
 */
@Service
public class MarketDataService {

    /** Tickers such as AAPL, BRK.B, BF-B or the Twelve Data form TCS:NSE. */
    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9][A-Z0-9.\\-:]{0,19}");
    private static final int MAX_NEWS_DAYS = 30;
    private static final int MAX_SUMMARY_LENGTH = 280;

    private final PriceDataProvider priceProvider;
    private final CompanyDataProvider companyProvider;
    private final MarketDataProperties properties;
    private final Clock clock;

    private final Cache<String, Quote> quotes;
    private final Cache<String, PriceHistory> histories;
    private final Cache<String, CompanyFundamentals> fundamentals;
    private final Cache<String, List<NewsArticle>> news;

    @Autowired
    public MarketDataService(PriceDataProvider priceProvider,
                             ObjectProvider<CompanyDataProvider> companyProvider,
                             MarketDataProperties properties,
                             Clock clock) {
        this(priceProvider, companyProvider.getIfAvailable(), properties, clock);
    }

    /** @param companyProvider null when fundamentals and news are not configured */
    public MarketDataService(PriceDataProvider priceProvider,
                             CompanyDataProvider companyProvider,
                             MarketDataProperties properties,
                             Clock clock) {
        this.priceProvider = priceProvider;
        this.companyProvider = companyProvider;
        this.properties = properties;
        this.clock = clock;
        this.quotes = cache(properties.quoteTtl());
        this.histories = cache(properties.historyTtl());
        this.fundamentals = cache(properties.fundamentalsTtl());
        this.news = cache(properties.newsTtl());
    }

    /** Upper-cases and validates a ticker, without calling any provider. */
    public static String normalize(String symbol) {
        String normalized = symbol == null ? "" : symbol.strip().toUpperCase();
        if (!SYMBOL.matcher(normalized).matches()) {
            throw new MarketDataException(Kind.INVALID_SYMBOL,
                    "'" + (symbol == null ? "" : symbol.strip()) + "' is not a valid ticker symbol. Use a ticker such as AAPL or MSFT, not a company name.");
        }
        return normalized;
    }

    public Quote quote(String symbol) {
        String key = normalize(symbol);
        return load(quotes, key, () -> priceProvider.quote(key));
    }

    /** Daily bars, oldest first. */
    public PriceHistory history(String symbol) {
        String key = normalize(symbol);
        return load(histories, key, () -> priceProvider.dailyHistory(key, properties.historyBars()));
    }

    public CompanyFundamentals fundamentals(String symbol) {
        String key = normalize(symbol);
        CompanyDataProvider provider = requireCompanyProvider("Company fundamentals are");
        return load(fundamentals, key, () -> provider.fundamentals(key));
    }

    /** Most recent articles from the last {@code days} days (1 to 30), newest first. */
    public List<NewsArticle> news(String symbol, int days) {
        String key = normalize(symbol);
        int window = Math.clamp(days, 1, MAX_NEWS_DAYS);
        CompanyDataProvider provider = requireCompanyProvider("Company news is");
        return load(news, key + "|" + window, () -> {
            LocalDate today = LocalDate.now(clock);
            return provider.news(key, today.minusDays(window), today).stream()
                    .limit(properties.maxNewsArticles())
                    .map(MarketDataService::withShortSummary)
                    .toList();
        });
    }

    public String priceProviderName() {
        return priceProvider.name();
    }

    public Optional<String> companyProviderName() {
        return Optional.ofNullable(companyProvider).map(CompanyDataProvider::name);
    }

    /** @param whatIs subject and verb for the message, e.g. "Company news is" */
    private CompanyDataProvider requireCompanyProvider(String whatIs) {
        if (companyProvider == null) {
            throw new MarketDataException(Kind.NOT_CONFIGURED,
                    whatIs + " not available: no fundamentals/news provider is configured. "
                            + "Set FINNHUB_API_KEY (free at finnhub.io) and restart to enable them.");
        }
        return companyProvider;
    }

    private static NewsArticle withShortSummary(NewsArticle article) {
        String summary = article.summary();
        if (summary == null || summary.length() <= MAX_SUMMARY_LENGTH) {
            return article;
        }
        return new NewsArticle(article.headline(), article.source(),
                summary.substring(0, MAX_SUMMARY_LENGTH).stripTrailing() + "…", article.url(), article.publishedAt());
    }

    private static <T> T load(Cache<String, T> cache, String key, Supplier<T> loader) {
        return cache.get(key, ignored -> loader.get());
    }

    private static <T> Cache<String, T> cache(Duration ttl) {
        return Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(500).build();
    }
}
