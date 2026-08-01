package com.minicdn.router;

import com.minicdn.common.Config;
import com.minicdn.common.ConsistentHashRing;
import com.minicdn.common.EdgeInfo;
import io.javalin.Javalin;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class RouterMain {

    private static final Map<String, List<EdgeInfo>> regionEdges = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> edgeHealthy = new ConcurrentHashMap<>();
    private static final Map<String, ConsistentHashRing> regionHashRings = new ConcurrentHashMap<>();
    private static final int VIRTUAL_NODES = 150;
    private static Config config;

    // Micrometer
    private static PrometheusMeterRegistry meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private static Counter redirectCounter;

    public static void main(String[] args) throws Exception {
        config = Config.load("config.yml");

        for (EdgeInfo e : config.edges) {
            regionEdges.computeIfAbsent(e.region, k -> new CopyOnWriteArrayList<>()).add(e);
            edgeHealthy.put(e.id, true);
        }

        for (var entry : regionEdges.entrySet()) {
            ConsistentHashRing ring = new ConsistentHashRing(entry.getValue(), VIRTUAL_NODES);
            regionHashRings.put(entry.getKey(), ring);
        }

        // Redirect counter (by region and status)
        redirectCounter = meterRegistry.counter("redirects.total");

        // Edge health gauge
        for (EdgeInfo e : config.edges) {
            Gauge.builder("edge.health", () -> edgeHealthy.getOrDefault(e.id, false) ? 1 : 0)
                    .tag("edge", e.id)
                    .register(meterRegistry);
        }

        // Health checker (unchanged)
        ScheduledExecutorService healthExecutor = Executors.newScheduledThreadPool(1);
        healthExecutor.scheduleAtFixedRate(RouterMain::checkEdges, 0, 10, TimeUnit.SECONDS);

        Javalin app = Javalin.create().start(config.routerPort);

        // Content redirect (with metrics)
        app.get("/content/{path}", ctx -> {
            String path = ctx.pathParam("path");
            String clientIp = ctx.header("X-Forwarded-For") != null
                    ? ctx.header("X-Forwarded-For")
                    : ctx.req().getRemoteAddr();
            String region = resolveRegion(clientIp);

            ConsistentHashRing ring = regionHashRings.get(region);
            if (ring == null) ring = regionHashRings.get("us-east");

            EdgeInfo selected = null;
            if (ring != null) {
                selected = ring.getHealthyNode("/content/" + path, edgeHealthy);
            }

            if (selected != null) {
                String target = "http://" + selected.host + ":" + selected.port + "/content/" + path;
                redirectCounter.increment(); // count successful redirect
                ctx.redirect(target);
            } else {
                // fallback to origin
                ctx.redirect("http://" + config.originHost + ":" + config.originPort + "/content/" + path);
            }
        });

        // Dashboard (unchanged)
        app.get("/", ctx -> {
            StringBuilder html = new StringBuilder("<h1>MiniCDN Router</h1><ul>");
            for (EdgeInfo e : config.edges) {
                html.append("<li>")
                        .append(e.id).append(" (").append(e.region).append(") ")
                        .append(edgeHealthy.get(e.id) ? "UP" : "DOWN")
                        .append("</li>");
            }
            html.append("</ul>");
            ctx.html(html.toString());
        });

        // Prometheus metrics endpoint
        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(meterRegistry.scrape());
        });
        app.get("/health", ctx -> ctx.result("OK"));


        System.out.println("Router started on http://localhost:" + config.routerPort);
    }

    private static void checkEdges() {
        HttpClient client = HttpClient.newHttpClient();
        for (EdgeInfo edge : config.edges) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + edge.host + ":" + edge.port + "/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                edgeHealthy.put(edge.id, resp.statusCode() == 200);
            } catch (Exception ex) {
                edgeHealthy.put(edge.id, false);
            }
        }
    }

    private static String resolveRegion(String ip) {
        for (var entry : config.ipRegionMapping.entrySet()) {
            if (ip.startsWith(entry.getKey())) return entry.getValue();
        }
        return "us-east";
    }
}