package com.example.stockagent.marketdata;

import java.time.Instant;

public record NewsArticle(String headline, String source, String summary, String url, Instant publishedAt) {
}
