# Stage 1: Build
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Copy Gradle wrapper and build files first for layer caching
COPY gradlew gradlew.bat ./
COPY gradle/wrapper/ gradle/wrapper/
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# Copy all module build files (needed for dependency resolution)
COPY l402-core/build.gradle.kts l402-core/
COPY l402-lightning-lnd/build.gradle.kts l402-lightning-lnd/
COPY l402-lightning-lnbits/build.gradle.kts l402-lightning-lnbits/
COPY l402-spring-autoconfigure/build.gradle.kts l402-spring-autoconfigure/
COPY l402-spring-security/build.gradle.kts l402-spring-security/
COPY l402-spring-boot-starter/build.gradle.kts l402-spring-boot-starter/
COPY l402-example-app/build.gradle.kts l402-example-app/

# Copy proto files required by l402-lightning-lnd build
COPY l402-lightning-lnd/src/main/proto/ l402-lightning-lnd/src/main/proto/

# Download dependencies (cached unless build files change)
RUN ./gradlew dependencies --no-daemon || true

# Copy all source code
COPY l402-core/src/ l402-core/src/
COPY l402-lightning-lnd/src/ l402-lightning-lnd/src/
COPY l402-lightning-lnbits/src/ l402-lightning-lnbits/src/
COPY l402-spring-autoconfigure/src/ l402-spring-autoconfigure/src/
COPY l402-spring-security/src/ l402-spring-security/src/
COPY l402-example-app/src/ l402-example-app/src/

# Build the example app bootJar, skipping tests for faster image builds
RUN ./gradlew :l402-example-app:bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Run as non-root user
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

COPY --from=build /workspace/l402-example-app/build/libs/*-SNAPSHOT.jar app.jar
COPY l402-example-app/docker-entrypoint.sh /app/docker-entrypoint.sh

RUN chmod +x /app/docker-entrypoint.sh && chown appuser:appgroup /app/docker-entrypoint.sh
RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

ENTRYPOINT ["bash", "/app/docker-entrypoint.sh"]
