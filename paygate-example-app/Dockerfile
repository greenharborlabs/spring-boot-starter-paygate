# Stage 1: Build
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Copy Gradle wrapper and build files first for layer caching
COPY gradlew gradlew.bat ./
COPY gradle/wrapper/ gradle/wrapper/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Copy all module build files (needed for dependency resolution)
COPY paygate-core/build.gradle.kts paygate-core/
COPY paygate-lightning-lnd/build.gradle.kts paygate-lightning-lnd/
COPY paygate-lightning-lnbits/build.gradle.kts paygate-lightning-lnbits/
COPY paygate-spring-autoconfigure/build.gradle.kts paygate-spring-autoconfigure/
COPY paygate-spring-security/build.gradle.kts paygate-spring-security/
COPY paygate-spring-boot-starter/build.gradle.kts paygate-spring-boot-starter/
COPY paygate-example-app/build.gradle.kts paygate-example-app/

# Copy proto files required by paygate-lightning-lnd build
COPY paygate-lightning-lnd/src/main/proto/ paygate-lightning-lnd/src/main/proto/

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon || true

# Copy all source code
COPY paygate-core/src/ paygate-core/src/
COPY paygate-lightning-lnd/src/ paygate-lightning-lnd/src/
COPY paygate-lightning-lnbits/src/ paygate-lightning-lnbits/src/
COPY paygate-spring-autoconfigure/src/ paygate-spring-autoconfigure/src/
COPY paygate-spring-security/src/ paygate-spring-security/src/
COPY paygate-example-app/src/ paygate-example-app/src/

# Build the example app bootJar, skipping tests for faster image builds
RUN ./gradlew :paygate-example-app:bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Run as non-root user
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

COPY --from=build /workspace/paygate-example-app/build/libs/*-SNAPSHOT.jar app.jar
COPY paygate-example-app/docker-entrypoint.sh /app/docker-entrypoint.sh

RUN chmod +x /app/docker-entrypoint.sh && chown appuser:appgroup /app/docker-entrypoint.sh
RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

ENTRYPOINT ["bash", "/app/docker-entrypoint.sh"]
