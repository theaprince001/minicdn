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

    // Shared HTTP client for proxy requests (reuse, not create per request)
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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

        // REVERSE PROXY endpoint – fetches content from edge/origin and returns directly
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

            // Build the backend URL
            String backendUrl;
            if (selected != null) {
                backendUrl = "http://" + selected.host + ":" + selected.port + "/content/" + path;
                redirectCounter.increment(); // count successful routing
            } else {
                // Fallback to origin
                backendUrl = "http://" + config.originHost + ":" + config.originPort + "/content/" + path;
            }

            try {
                // Proxy the request to the backend (edge or origin)
                HttpRequest backendReq = HttpRequest.newBuilder()
                        .uri(URI.create(backendUrl))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<byte[]> backendResp = httpClient.send(backendReq, HttpResponse.BodyHandlers.ofByteArray());

                // Forward status and headers (skip hop‑by‑hop headers)
                ctx.status(backendResp.statusCode());
                backendResp.headers().map().forEach((key, values) -> {
                    if (!"Transfer-Encoding".equalsIgnoreCase(key) && !"Connection".equalsIgnoreCase(key)) {
                        values.forEach(val -> ctx.header(key, val));
                    }
                });
                ctx.result(backendResp.body());
            } catch (Exception e) {
                ctx.status(502).result("Bad Gateway");
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

        // Health endpoint (for Render)
        app.get("/health", ctx -> ctx.result("OK"));

        System.out.println("Router (reverse proxy mode) started on http://localhost:" + config.routerPort);
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