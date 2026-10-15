# =============================================================================
#  Notification Service — container image
# =============================================================================
#  Multi-stage: the build stage carries the JDK, Maven and the whole dependency
#  cache; the runtime stage carries a JRE and one jar. Shipping the build stage
#  would mean a ~700MB image containing a compiler and the full source.
# =============================================================================

# -----------------------------------------------------------------------------
#  Stage 1 — build
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# The wrapper and the POM first, on their own layer. Docker caches layers by
# content, so as long as pom.xml is unchanged the dependency download below is
# reused — copying the source first would invalidate it on every code edit and
# re-download everything.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/

# Tests are NOT run here. They need PostgreSQL, Kafka and Redis, which a build
# container does not have, and `docker build` is the wrong place to discover
# that. CI runs `./mvnw verify -Pintegration` against real services before the
# image is built.
RUN ./mvnw -B clean package -DskipTests

# -----------------------------------------------------------------------------
#  Stage 2 — runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# wget for the HEALTHCHECK; the JRE alpine image does not ship one.
RUN apk add --no-cache wget

# A non-root user. A container process running as root that escapes its
# namespace is root on the host; this one is not.
RUN addgroup -S notification && adduser -S -G notification notification

WORKDIR /app

COPY --from=build --chown=notification:notification /build/target/*.jar app.jar

USER notification

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the cgroup limit, so
# the heap scales with whatever the orchestrator actually granted. A hard-coded
# -Xmx is either wasteful or gets the container OOM-killed when the limit
# changes.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# Uses the readiness probe, which reports the database, Kafka and Redis as well
# as the process — a container that is up but cannot reach its database is not
# ready to serve.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

# `sh -c` so $JAVA_OPTS is expanded. `exec` replaces the shell with the JVM, so
# the JVM becomes PID 1 and receives SIGTERM directly — without it the shell
# would hold PID 1, swallow the signal, and the container would be killed after
# the grace period instead of shutting down gracefully. That matters here: a
# graceful shutdown lets in-flight Kafka offsets commit.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
