package com.example.stockagent.marketdata.twelvedata;

import com.example.stockagent.marketdata.Candle;
import com.example.stockagent.marketdata.MarketDataException;
import com.example.stockagent.marketdata.MarketDataException.Kind;
import com.example.stockagent.marketdata.PriceDataProvider;
import com.example.stockagent.marketdata.PriceHistory;
import com.example.stockagent.marketdata.Quote;
import com.example.stockagent.marketdata.Retry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

/**
 * Quotes and daily bars from the Twelve Data REST API.
 *
 * <p>Twelve Data reports failures as a JSON body ({@code {"code":404,"message":"...","status":"error"}})
 * and does not always mirror the code in the HTTP status, so both are checked.
 */
@Component
public class TwelveDataPriceProvider implements PriceDataProvider {

    private static final double PROVISIONAL_VOLUME_RATIO = 0.2;

    private final RestClient restClient;
    private final TwelveDataProperties properties;

    public TwelveDataPriceProvider(RestClient.Builder builder, TwelveDataProperties properties) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                // Header auth keeps the key out of URLs, and so out of access logs and error messages.
                .defaultHeader("Authorization", "apikey " + properties.apiKey())
                .build();
    }

    @Override
    public String name() {
        return properties.usingDemoKey() ? "Twelve Data (demo key)" : "Twelve Data";
    }

    @Override
    public Quote quote(String symbol) {
        QuoteResponse body = get(symbol, QuoteResponse.class, "/quote?symbol={symbol}", symbol);
        failIfError(symbol, body.code(), body.message(), body.status());
        if (body.close() == null || body.previousClose() == null) {
            throw new MarketDataException(Kind.UPSTREAM_ERROR, "Twelve Data returned a quote for " + symbol + " without a price.");
        }
        FiftyTwoWeek range = body.fiftyTwoWeek();
        Long asOfEpoch = body.lastQuoteAt() != null ? body.lastQuoteAt() : body.timestamp();
        boolean marketOpen = Boolean.TRUE.equals(body.marketOpen());
        long volume = body.volume() == null ? 0 : body.volume();
        // Intraday volume is a partial tally, and it stays partial for a while after the close until
        // the consolidated figure is published. A finished session never trades under a fifth of average.
        boolean volumeProvisional = marketOpen
                || body.averageVolume() != null && volume < PROVISIONAL_VOLUME_RATIO * body.averageVolume();
        return new Quote(
                symbol,
                body.name(),
                body.exchange(),
                body.currency(),
                body.close(),
                body.previousClose(),
                body.change() != null ? body.change() : body.close() - body.previousClose(),
                body.percentChange() != null ? body.percentChange() : (body.close() - body.previousClose()) / body.previousClose() * 100,
                orZero(body.open()),
                orZero(body.high()),
                orZero(body.low()),
                volume,
                volumeProvisional,
                body.averageVolume(),
                range == null ? null : range.high(),
                range == null ? null : range.low(),
                parseDate(symbol, body.datetime()),
                marketOpen,
                asOfEpoch == null ? Instant.now() : Instant.ofEpochSecond(asOfEpoch));
    }

    @Override
    public PriceHistory dailyHistory(String symbol, int bars) {
        TimeSeriesResponse body = get(symbol, TimeSeriesResponse.class,
                "/time_series?symbol={symbol}&interval=1day&outputsize={bars}&order=asc", symbol, bars);
        failIfError(symbol, body.code(), body.message(), body.status());
        if (body.values() == null || body.values().isEmpty()) {
            throw new MarketDataException(Kind.SYMBOL_NOT_FOUND, "Twelve Data has no daily price history for " + symbol + ".");
        }
        List<Candle> candles = body.values().stream()
                .filter(bar -> bar.close() != null && bar.high() != null && bar.low() != null)
                .map(bar -> new Candle(
                        parseDate(symbol, bar.datetime()),
                        bar.open() == null ? bar.close() : bar.open(),
                        bar.high(),
                        bar.low(),
                        bar.close(),
                        bar.volume() == null ? 0 : bar.volume()))
                .toList();
        if (candles.isEmpty()) {
            throw new MarketDataException(Kind.UPSTREAM_ERROR, "Twelve Data returned no usable daily bars for " + symbol + ".");
        }
        Meta meta = body.meta();
        return new PriceHistory(symbol, meta == null ? null : meta.currency(), meta == null ? null : meta.exchange(), candles);
    }

    private <T> T get(String symbol, Class<T> type, String uriTemplate, Object... uriVariables) {
        try {
            return Retry.onceOnConnectionFailure(() -> restClient.get()
                    .uri(uriTemplate, uriVariables)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        boolean httpError = response.getStatusCode().isError();
                        T body;
                        try {
                            // Error responses normally carry a JSON body too, so always try to read it.
                            body = response.bodyTo(type);
                        } catch (RestClientException e) {
                            if (httpError) {
                                // e.g. a plain-text 429 from a proxy: the HTTP status is all we have.
                                throw toException(symbol, status, "");
                            }
                            throw e;
                        }
                        if (body == null) {
                            throw httpError
                                    ? toException(symbol, status, "")
                                    : new MarketDataException(Kind.UPSTREAM_ERROR, "Twelve Data returned an empty response for " + symbol + ".");
                        }
                        // An error body without a "code" still has to fail: fall back to the HTTP status.
                        if (httpError && body instanceof ApiResult result && result.code() == null) {
                            throw toException(symbol, status, result.message());
                        }
                        return body;
                    }));
        } catch (RestClientException e) {
            throw new MarketDataException(Kind.UPSTREAM_ERROR,
                    "Could not get data for " + symbol + " from Twelve Data: " + rootMessage(e), e);
        }
    }

    private void failIfError(String symbol, Integer code, String message, String status) {
        if (code != null && code >= 400 || "error".equalsIgnoreCase(status)) {
            throw toException(symbol, code == null ? 500 : code, message);
        }
    }

    private MarketDataException toException(String symbol, int code, String rawMessage) {
        String message = rawMessage == null ? "" : rawMessage.replace("**", "");
        Kind kind = kindFor(code, message);
        String text = switch (kind) {
            case SYMBOL_NOT_FOUND -> "Twelve Data does not recognise the symbol " + symbol + ".";
            case RATE_LIMITED -> "Twelve Data rate limit reached (the free plan allows 8 requests a minute). Try again in a minute.";
            case ACCESS_DENIED -> properties.usingDemoKey()
                    ? "The Twelve Data demo key only covers a few symbols such as AAPL, not " + symbol
                            + ". Set TWELVEDATA_API_KEY to a free key from twelvedata.com to analyse any symbol."
                    : "Twelve Data refused the request for " + symbol + " (API key invalid, or the plan does not cover this symbol): " + message;
            default -> "Twelve Data error " + code + " for " + symbol + ": " + message;
        };
        return new MarketDataException(kind, text);
    }

    private static Kind kindFor(int code, String message) {
        return switch (code) {
            case 404 -> Kind.SYMBOL_NOT_FOUND;
            case 400 -> message.toLowerCase().contains("symbol") ? Kind.SYMBOL_NOT_FOUND : Kind.UPSTREAM_ERROR;
            case 401, 403 -> Kind.ACCESS_DENIED;
            case 429 -> Kind.RATE_LIMITED;
            default -> Kind.UPSTREAM_ERROR;
        };
    }

    private static LocalDate parseDate(String symbol, String datetime) {
        try {
            // Daily bars are "2026-11-09"; tolerate a trailing time component.
            return LocalDate.parse(Objects.requireNonNull(datetime).substring(0, 10));
        } catch (DateTimeParseException | NullPointerException | IndexOutOfBoundsException e) {
            throw new MarketDataException(Kind.UPSTREAM_ERROR,
                    "Twelve Data returned an unreadable date for " + symbol + ": " + datetime, e);
        }
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    // --- wire format. Twelve Data sends every number as a JSON string; Jackson coerces them.

    private interface ApiResult {
        Integer code();

        String message();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TimeSeriesResponse(Meta meta, List<Bar> values, String status, Integer code, String message) implements ApiResult {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(String symbol, String currency, String exchange) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Bar(String datetime, Double open, Double high, Double low, Double close, Long volume) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuoteResponse(
            String symbol,
            String name,
            String exchange,
            String currency,
            String datetime,
            Long timestamp,
            @JsonProperty("last_quote_at") Long lastQuoteAt,
            Double open,
            Double high,
            Double low,
            Double close,
            Long volume,
            @JsonProperty("previous_close") Double previousClose,
            Double change,
            @JsonProperty("percent_change") Double percentChange,
            @JsonProperty("average_volume") Long averageVolume,
            @JsonProperty("is_market_open") Boolean marketOpen,
            @JsonProperty("fifty_two_week") FiftyTwoWeek fiftyTwoWeek,
            String status,
            Integer code,
            String message) implements ApiResult {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FiftyTwoWeek(Double low, Double high) {
    }
}
