# 🚀 MiniCDN – A Production‑Grade Content Delivery Network in Pure Java

**Live Demo:** [https://minicdn.onrender.com](https://minicdn.onrender.com)  
**Source Code:** [https://github.com/theaprince001/minicdn](https://github.com/theaprince001/minicdn)  
**Status:** ✅ US & EU edges healthy, multi‑tier caching active, 24/7 on Render (free tier).

MiniCDN is a **fully functional, geo‑aware Content Delivery Network** built from scratch using **only Java 21** and lightweight libraries — no Spring Boot, no external caching services.  
It demonstrates the core architecture of real CDNs like Cloudflare or Akamai: request routing, consistent hashing, multi‑tier caching, stale‑while‑revalidate, health‑check‑based failover, and real‑time observability.

---

## 📸 Screenshots

| Router Dashboard (Live) | Grafana Monitoring (Local) |
|-------------------------|----------------------------|
| ![Router Dashboard](screenshots/router.png) | ![Grafana](screenshots/grafana.png) |

*The Grafana dashboard shows global cache hit ratio, edge health, and request rate. Prometheus scrapes metrics every 5 seconds.*

---

## 🧠 Architecture
<<<<<<< HEAD
| Layer               | Role                                              |
| ------------------- | ------------------------------------------------- |
| Client              | Sends HTTPS requests                              |
| Router :8080        | Geo-IP routing, consistent hashing, reverse proxy |
| Edge US :9003       | Regional cache with Caffeine and SWR              |
| Edge EU :9002       | Regional cache with Caffeine and SWR              |
| Origin Shield :9000 | Shared cache and request collapsing               |
| Origin :8000        | Static file server                                |
=======
┌─────────────┐
│ Client │
└──────┬──────┘
│ HTTPS
┌──────▼──────────────────────────┐
│ Router (8080) │
│ - Geo‑IP mapping │
│ - Consistent hashing │
│ - Reverse proxy to edges │
└──┬──────────────┬───────────────┘
│ │
│ │
┌────────────▼──┐ ┌──────▼────────────┐
│ Edge US (9003)│ │ Edge EU (9002) │
│ Caffeine LRU │ │ Caffeine LRU │
│ SWR + Collap.│ │ SWR + Collap. │
└──────┬────────┘ └──────┬────────────┘
│ │
└─────────┬─────────┘
│
┌────────────▼──────────┐
│ Origin Shield (9000)│
│ Shared cache │
│ Request collapsing │
└──────────┬────────────┘
│
┌──────────▼──────────┐
│ Origin (8000) │
│ Static file server │
└─────────────────────┘
>>>>>>> 

### Why this design?

- **Multi‑tier caching** – Edges → Shield → Origin. The Shield collapses concurrent requests, so the origin sees only one fetch even if 100 edges ask for the same file.
- **Consistent hashing** – Each piece of content is mapped to a specific edge. When an edge fails, only its content is redistributed, not the entire CDN.
- **Stale‑while‑revalidate (SWR)** – When a cached item expires, the edge returns the stale copy while asynchronously fetching a fresh one. Combined with request collapsing, this eliminates thundering herds.

---

## 🔧 Tech Stack

| Layer              | Technology                          |
|--------------------|-------------------------------------|
| Language           | Java 21                             |
| HTTP Server        | Javalin (embedded Jetty)            |
| Caching            | Caffeine (LRU, TTL, SWR, stats)     |
| Observability      | Micrometer → Prometheus → Grafana   |
| Build              | Maven (fat JAR with dependencies)   |
| Deployment         | Docker, Render (free tier)          |
| Concurrency        | Virtual Threads (Java 21)           |

---

## ✨ Features

- **Geo‑aware routing** – Clients are mapped to the nearest edge based on their IP (X‑Forwarded‑For for testing).
- **Consistent hashing** with 150 virtual nodes – ensures cache affinity and smooth redistribution when edges are added/removed.
- **Multi‑tier caching** – Edge → Origin Shield → Origin. Shield collapses concurrent requests and reduces origin traffic.
- **Stale‑while‑revalidate** – Expired content is returned immediately while the edge refreshes in the background. Request collapsing prevents duplicate origin fetches.
- **Automatic health checking** – Router polls edges every 10 seconds. Unhealthy edges are removed; recovered edges are automatically rejoined.
- **Cache purge** – Instant invalidation via DELETE /purge/{path}.
- **Full observability** – Prometheus metrics (cache hit ratio, origin latency, edge health), Grafana dashboard with live panels.
- **Live dashboard** – Router shows edge statuses (UP/DOWN), with a link to the source code.
- **Zero cost** – Runs on Render's free tier; no credit card needed for demo.

---

## 🚀 Getting Started (Run Locally)

### Prerequisites

- Java 21
- Maven
- Docker (optional, for local container testing)

### 1. Clone & build

```bash
git clone https://github.com/theaprince001/minicdn.git
cd minicdn
mvn clean package -DskipTests
```

### 2. Run the unified application

```bash
java -cp target/minicdn-1.0-SNAPSHOT-jar-with-dependencies.jar com.minicdn.MinicdnApp
```

This starts all 5 services (Origin, Shield, Edges, Router) in one JVM.

### 3. Test the CDN

```bash
# US region (default)
curl http://localhost:8080/content/hello.txt

# EU region (simulated)
curl -H "X-Forwarded-For: 10.0.0.1" http://localhost:8080/content/hello.txt

# Dashboard
open http://localhost:8080
```

### 4. (Optional) Run with Docker

```bash
docker build -t minicdn-render .
docker run -p 8080:8080 minicdn-render
```

---

## 📊 Observability (Local Prometheus + Grafana)

Start the monitoring stack (already configured in the repo):

```bash
docker-compose up -d
```

- **Prometheus** → http://localhost:9090
- **Grafana** → http://localhost:3000
- Import the dashboard JSON from grafana/dashboard.json (or create one using the PromQL queries in the docs).

Edge metrics include:

- cache_gets_total (hit/miss)
- origin_request_duration_seconds
- origin_requests_total

Router metrics:

- 
edirects_total
- edge_health gauge

---

## ☁️ Deployment (Render)

The project is deployed on Render's free tier using a single Docker container.  
The 
ender.yaml and Dockerfile are included.

**Steps to deploy your own:**

1. Fork this repository.
2. On [Render](https://render.com), create a new **Web Service**, connect your forked repo.
3. Set:
   - **Dockerfile Path:** Dockerfile
   - **Health Check Path:** /health
   - **Port:** 8080
4. Deploy!

---


---

## 🤝 Contributing / Feedback

This is a learning project. If you have suggestions or want to discuss system design trade‑offs, feel free to open an issue or reach out on [LinkedIn](https://linkedin.com/in/theaprince001).

---

**Built with ☕ and curiosity by The Prince**
