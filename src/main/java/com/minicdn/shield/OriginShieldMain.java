package com.minicdn.shield;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.minicdn.common.CacheEntry;
import io.javalin.Javalin;
import io.javalin.http.Header;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class OriginShieldMain {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getProperty("port", "9000"));
        String originUrl = System.getProperty("originUrl", "http://localhost:8000");
        long ttlSeconds = Long.parseLong(System.getProperty("ttl", "120")); // longer TTL
        int maxSize = Integer.parseInt(System.getProperty("maxSize", "5000"));

        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        Cache<String, CacheEntry> cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .recordStats()
                .executor(executor)
                .build();

        CaffeineCacheMetrics.monitor(meterRegistry, cache, "origin-shield-cache");

        var originRequestCounter = meterRegistry.counter("origin.requests", "path", "none");
        var originRequestDuration = meterRegistry.timer("origin.request.duration");

        ConcurrentHashMap<String, CompletableFuture<CacheEntry>> refreshInProgress = new ConcurrentHashMap<>();

        HttpClient httpClient = HttpClient.newBuilder()
                .executor(executor)
                .build();

        Javalin app = Javalin.create(config -> config.useVirtualThreads = true).start(port);

        // Main content handler – same pattern as edge, but fetches from origin
        app.get("/content/{path}", ctx -> {
            String path = ctx.pathParam("path");
            CacheEntry cached = cache.getIfPresent(path);

            if (cached != null) {
                ctx.header("X-Shield-Cache", "HIT");
                cached.headers().forEach(ctx::header);
                ctx.status(cached.statusCode());
                ctx.result(cached.body());
                return;
            }

            // MISS – collapsing fetch from origin
            CompletableFuture<CacheEntry> future = refreshInProgress.computeIfAbsent(path, p -> {
                var sample = Timer.start(meterRegistry);
                return CompletableFuture.supplyAsync(() -> {
                    CacheEntry entry = fetchFromOrigin(p, originUrl, httpClient);
                    sample.stop(originRequestDuration);
                    originRequestCounter.increment();
                    return entry;
                }, executor).whenComplete((entry, ex) -> {
                    refreshInProgress.remove(p);
                    if (entry != null) cache.put(p, entry);
                });
            });

            try {
                CacheEntry fresh = future.get();
                if (fresh != null) {
                    ctx.header("X-Shield-Cache", "MISS");
                    fresh.headers().forEach(ctx::header);
                    ctx.status(fresh.statusCode());
                    ctx.result(fresh.body());
                } else {
                    ctx.status(502).result("Bad Gateway");
                }
            } catch (Exception e) {
                ctx.status(502).result("Bad Gateway");
            }
        });

        // Health
        app.get("/health", ctx -> ctx.result("OK"));

        // Purge – allows manual invalidation
        app.delete("/purge/{path}", ctx -> {
            cache.invalidate(ctx.pathParam("path"));
            ctx.result("Purged");
        });

        // Prometheus metrics
        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(meterRegistry.scrape());
        });

        System.out.println("Origin Shield started on http://localhost:" + port);
    }

    private static CacheEntry fetchFromOrigin(String path, String originUrl, HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(originUrl + "/content/" + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                Map<String, String> headers = new HashMap<>();
                response.headers().map().forEach((k, v) -> {
                    if (!"Transfer-Encoding".equalsIgnoreCase(k) && !"Connection".equalsIgnoreCase(k))
                        headers.put(k, String.join(",", v));
                });
                return new CacheEntry(
                        response.statusCode(),
                        response.headers().firstValue(Header.CONTENT_TYPE).orElse("application/octet-stream"),
                        response.body(),
                        headers
                );
            }
        } catch (Exception e) { /* log */ }
        return null;
    }
}