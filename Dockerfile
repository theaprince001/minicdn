FROM eclipse-temurin:21-jre-alpine

# Install wget for the health check
RUN apk add --no-cache wget

WORKDIR /app

# Copy the pre‑built fat JAR
COPY target/minicdn-1.0-SNAPSHOT-jar-with-dependencies.jar /app/minicdn.jar

# Copy static files & configuration
COPY origin-files /app/origin-files
COPY config.yml /app/config.yml

# Health check (needs wget)
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -q -O- http://localhost:8080/health || exit 1

EXPOSE 8080

CMD ["java", "-Xmx256m", "-jar", "/app/minicdn.jar"]