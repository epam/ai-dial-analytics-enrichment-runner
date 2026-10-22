# Build stage
# Runs on the build host's platform: the jar is architecture-independent, so a multi-platform build
# compiles it once natively instead of running Gradle under emulation for each target platform
FROM --platform=$BUILDPLATFORM gradle:9.6.0-jdk25-alpine AS builder
WORKDIR /build-workspace

COPY build.gradle .
COPY settings.gradle .
COPY gradle.properties .
COPY lombok.config .
COPY checkstyle/ checkstyle/
COPY src/ src/

RUN gradle --no-daemon clean bootJar

# Runtime stage
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# No writable directory is created under /app: the service persists nothing to the container
# filesystem (all state lives in PostgreSQL, logs go to stdout/OTLP), so it runs with a read-only
# root filesystem given a writable /tmp
RUN adduser -u 1001 --disabled-password --gecos "" appuser && \
    chown -R appuser:appuser /app

RUN apk upgrade --no-cache && apk add --no-cache bash

COPY --from=builder --chown=appuser:appuser /build-workspace/build/libs/analytics-enrichment-runner*.jar ./app.jar
COPY --chown=appuser:appuser docker-entrypoint.sh /usr/local/bin/

RUN chmod +x /usr/local/bin/docker-entrypoint.sh

USER appuser

EXPOSE 5000

# Follows HTTP_PORT so overriding the port does not silently break the check.
HEALTHCHECK --start-period=30s --interval=1m --timeout=3s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${HTTP_PORT:-5000}/actuator/health/liveness || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
