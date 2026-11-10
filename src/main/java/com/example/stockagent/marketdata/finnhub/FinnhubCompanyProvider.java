package com.example.stockagent.marketdata.finnhub;

import com.example.stockagent.marketdata.CompanyDataProvider;
import com.example.stockagent.marketdata.CompanyFundamentals;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.example.stockagent.marketdata.NewsArticle;
import com.example.stockagent.marketdata.Retry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Company profile, headline metrics and news from the Finnhub REST API (free tier: US-listed
 * companies). Only created when an API key is configured.
 */
public class FinnhubCompanyProvider implements CompanyDataProvider {

    private static final ParameterizedTypeReference<List<NewsItem>> NEWS_LIST = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;

    public FinnhubCompanyProvider(RestClient.Builder builder, FinnhubProperties properties) {
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Finnhub-Token", properties.apiKey())
                .build();
    }

    @Override
    public String name() {
        return "Finnhub";
    }

    @Override
    public CompanyFundamentals fundamentals(String symbol) {
        String ticker = finnhubTicker(symbol);
        Profile profile = get(symbol, new ParameterizedTypeReference<Profile>() {
        }, "/stock/profile2?symbol={symbol}", ticker);
        // Finnhub answers an unknown ticker with HTTP 200 and an empty object.
        if (profile == null || !StringUtils.hasText(profile.name())) {
            throw new MarketDataException(Kind.SYMBOL_NOT_FOUND, "Finnhub has no company profile for " + symbol + ".");
        }
        Metrics metrics = get(symbol, new ParameterizedTypeReference<Metrics>() {
        }, "/stock/metric?symbol={symbol}&metric=all", ticker);
        Map<String, Object> m = metrics == null || metrics.metric() == null ? Map.of() : metrics.metric();

        return new CompanyFundamentals(
                symbol,
                profile.name(),
                profile.finnhubIndustry(),
                profile.exchange(),
                profile.country(),
                profile.currency(),
                parseDate(profile.ipo()),
                profile.weburl(),
                profile.marketCapitalization(),
                profile.shareOutstanding(),
                number(m, "peTTM", "peBasicExclExtraTTM", "peExclExtraTTM", "peAnnual"),
                number(m, "pbQuarterly", "pbAnnual", "pb"),
                number(m, "psTTM", "psAnnual"),
                number(m, "epsTTM", "epsBasicExclExtraItemsTTM", "epsExclExtraItemsTTM", "epsAnnual"),
                number(m, "dividendYieldIndicatedAnnual", "currentDividendYieldTTM"),
                number(m, "beta"),
                number(m, "roeTTM", "roeRfy"),
                number(m, "netProfitMarginTTM", "netProfitMarginAnnual"),
                number(m, "grossMarginTTM", "grossMarginAnnual"),
                number(m, "operatingMarginTTM", "operatingMarginAnnual"),
                number(m, "revenueGrowthTTMYoy", "revenueGrowthQuarterlyYoy"),
                number(m, "epsGrowthTTMYoy", "epsGrowthQuarterlyYoy"),
                number(m, "currentRatioQuarterly", "currentRatioAnnual"),
                number(m, "totalDebt/totalEquityQuarterly", "totalDebt/totalEquityAnnual"));
    }

    @Override
    public List<NewsArticle> news(String symbol, LocalDate from, LocalDate to) {
        List<NewsItem> items = get(symbol, NEWS_LIST, "/company-news?symbol={symbol}&from={from}&to={to}",
                finnhubTicker(symbol), from, to);
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(item -> StringUtils.hasText(item.headline()) && item.datetime() != null)
                .map(item -> new NewsArticle(item.headline().strip(), item.source(), item.summary(), item.url(),
                        Instant.ofEpochSecond(item.datetime())))
                .sorted(Comparator.comparing(NewsArticle::publishedAt).reversed())
                .toList();
    }

    private <T> T get(String symbol, ParameterizedTypeReference<T> type, String uriTemplate, Object... uriVariables) {
        ResponseEntity<T> response;
        try {
            response = Retry.onceOnConnectionFailure(() -> restClient.get()
                    .uri(uriTemplate, uriVariables)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, errorResponse) -> {
                        int status = errorResponse.getStatusCode().value();
                        throw switch (status) {
                            case 401 -> new MarketDataException(Kind.ACCESS_DENIED,
                                    "Finnhub rejected the API key. Check FINNHUB_API_KEY.");
                            case 403 -> new MarketDataException(Kind.ACCESS_DENIED,
                                    "Finnhub's free plan does not cover " + symbol + " (it covers US-listed companies).");
                            case 429 -> new MarketDataException(Kind.RATE_LIMITED,
                                    "Finnhub rate limit reached (the free plan allows 60 requests a minute). Try again shortly.");
                            default -> new MarketDataException(Kind.UPSTREAM_ERROR,
                                    "Finnhub returned HTTP " + status + " for " + symbol + ".");
                        };
                    })
                    .toEntity(type));
        } catch (RestClientException e) {
            throw new MarketDataException(Kind.UPSTREAM_ERROR,
                    "Could not get data for " + symbol + " from Finnhub: " + e.getMostSpecificCause().getMessage(), e);
        }
        return response.getBody();
    }

    /** Finnhub uses bare US tickers; drop a Twelve Data style ":EXCHANGE" suffix. */
    private static String finnhubTicker(String symbol) {
        int colon = symbol.indexOf(':');
        return colon < 0 ? symbol : symbol.substring(0, colon);
    }

    /** First of the candidate metric names that Finnhub reports as a number. */
    private static Double number(Map<String, Object> metrics, String... candidateKeys) {
        for (String key : candidateKeys) {
            if (metrics.get(key) instanceof Number number && Double.isFinite(number.doubleValue())) {
                return number.doubleValue();
            }
        }
        return null;
    }

    private static LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // --- wire format

    /** @param marketCapitalization in millions of {@code currency}; shareOutstanding in millions of shares */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(
            String name,
            String ticker,
            String exchange,
            String country,
            String currency,
            String ipo,
            String weburl,
            String finnhubIndustry,
            Double marketCapitalization,
            Double shareOutstanding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metrics(String symbol, Map<String, Object> metric) {
    }

    /** @param datetime publication time in epoch seconds */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NewsItem(String headline, String source, String summary, String url, Long datetime) {
    }
}
