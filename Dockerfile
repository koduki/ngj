# NGJ Compiler - Multi-stage Dockerfile
# Base image: Eclipse Temurin JDK 25

# =============================================================================
# Stage 1: Builder - Compile the NGJ compiler
# =============================================================================
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

# Copy Gradle wrapper and build files first (for layer caching)
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY compiler/build.gradle.kts compiler/

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY compiler/src/ compiler/src/

# Build the fat JAR
RUN ./gradlew :compiler:fatJar --no-daemon

# =============================================================================
# Stage 2: Runtime - Minimal image for running the compiler
# =============================================================================
FROM eclipse-temurin:25-jre AS runtime

LABEL org.opencontainers.image.title="NGJ Compiler"
LABEL org.opencontainers.image.description="AIエージェント向け型安全JVM言語コンパイラ"
LABEL org.opencontainers.image.version="0.1.0-SNAPSHOT"
LABEL org.opencontainers.image.source="https://github.com/koduki/ngj"

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /build/compiler/build/libs/compiler-*-all.jar /app/ngjc.jar

# Create a non-root user for security
RUN groupadd -r ngj && useradd -r -g ngj ngj
USER ngj

# Default entrypoint
ENTRYPOINT ["java", "-jar", "/app/ngjc.jar"]
CMD ["--help"]

# =============================================================================
# Stage 3: Development - Full JDK with source for development
# =============================================================================
FROM eclipse-temurin:25-jdk AS development

WORKDIR /workspace

# Install useful development tools
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    && rm -rf /var/lib/apt/lists/*

# Copy entire project
COPY . .

# Pre-download dependencies
RUN ./gradlew dependencies --no-daemon || true

# Default command for development
CMD ["./gradlew", "build", "--no-daemon"]
