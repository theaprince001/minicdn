package com.minicdn.edge;


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.minicdn.common.CacheEntry;
import io.javalin.Javalin;
import io.javalin.http.Header;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class EdgeMain {

    public static void main(String[] args) {
        int port = Integer.parseInt(args.length > 0 ? args[0] : System.getProperty("port", "9001"));
        String upstreamUrl = args.length > 1 ? args[1] : System.getProperty("upstreamUrl", "http://localhost:9000");
        long ttlSeconds = Long.parseLong(System.getProperty("ttl", "60"));
        int maxSize = Integer.parseInt(System.getProperty("maxSize", "1000"));

        // Micrometer Prometheus registry
        PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        var executor = Executors.newVirtualThreadPerTaskExecutor();

        // Build Caffeine cache with metrics binding
        Cache<String, CacheEntry> cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .recordStats()
                .executor(executor)
                .build();

        // Bind Caffeine cache metrics to Micrometer
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "cdn-cache");

        // Custom metrics
        var originRequestCounter = meterRegistry.counter("origin.requests", "path", "none");
        var originRequestDuration = meterRegistry.timer("origin.request.duration");

        // Collapsing map
        ConcurrentHashMap<String, CompletableFuture<CacheEntry>> refreshInProgress = new ConcurrentHashMap<>();

        HttpClient httpClient = HttpClient.newBuilder()
                .executor(executor)
                .build();

        Javalin app = Javalin.create(config -> {
            config.useVirtualThreads = true;
        }).start(port);

        // Main content handler (unchanged logic, but now records metrics)
        app.get("/content/{path}", ctx -> {
            String path = ctx.pathParam("path");
            CacheEntry cached = cache.getIfPresent(path);

            if (cached != null) {
                // HIT – record hit (Caffeine metrics do it automatically)
                ctx.header("X-Cache", "HIT");
                cached.headers().forEach(ctx::header);
                ctx.status(cached.statusCode());
                ctx.result(cached.body());
                return;
            }

            // MISS with collapsing
            CompletableFuture<CacheEntry> future = refreshInProgress.computeIfAbsent(path,
                    p -> {
                        // Start a timer for origin fetch
                        var sample = Timer.start(meterRegistry);
                        return CompletableFuture.supplyAsync(() -> {
                            CacheEntry entry = fetchFromOrigin(p, upstreamUrl, httpClient);
                            sample.stop(originRequestDuration);
                            originRequestCounter.increment();
                            return entry;
                        }, executor).whenComplete((entry, ex) -> {
                            refreshInProgress.remove(p);
                            if (entry != null) {
                                cache.put(p, entry);
                            }
                        });
                    });

            try {
                CacheEntry fresh = future.get();
                if (fresh != null) {
                    ctx.header("X-Cache", "MISS");
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

        // Purge
        app.delete("/purge/{path}", ctx -> {
            String path = ctx.pathParam("path");
            cache.invalidate(path);
            ctx.result("Purged: " + path);
        });

        // Health
        app.get("/health", ctx -> {
            System.out.println("Health endpoint hit on port " + port);
            ctx.result("OK");
        });
        // Prometheus metrics endpoint (standard /metrics)
        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(meterRegistry.scrape());
        });

        // Old JSON stats now at /cache-stats (optional)
        app.get("/cache-stats", ctx -> {
            var stats = cache.stats();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("hitCount", stats.hitCount());
            map.put("missCount", stats.missCount());
            map.put("hitRate", stats.hitRate());
            map.put("evictionCount", stats.evictionCount());
            map.put("requestCount", stats.requestCount());
            ctx.json(map);
        });

        System.out.println("Edge server (with Prometheus) started on http://localhost:" + port);
    }

    private static CacheEntry fetchFromOrigin(String path, String upstreamUrl, HttpClient client) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(upstreamUrl + "/content/" + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                Map<String, String> headers = new HashMap<>();
                response.headers().map().forEach((k, v) -> {
                    if (!"Transfer-Encoding".equalsIgnoreCase(k) && !"Connection".equalsIgnoreCase(k)) {
                        headers.put(k, String.join(",", v));
                    }
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