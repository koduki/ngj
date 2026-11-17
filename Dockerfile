# NGJ Compiler - Multi-stage Dockerfile
# =============================================================================
# Valhalla JDK Configuration
# =============================================================================
# NGJ targets JDK 28 Early-Access with JEP 401 (Value Objects) preview feature.
# Pinned build: JDK 28 EA Build 24 (2026-11-12)
# Download: https://jdk.java.net/28/
#
# Build Strategy:
# - Builder stage: Uses JDK 25 (Gradle compatible) for compilation
# - Runtime stage: Uses JDK 28 EA with --enable-preview for Valhalla features
# =============================================================================

ARG VALHALLA_JDK_VERSION=28
ARG VALHALLA_JDK_BUILD=24
ARG VALHALLA_JDK_DATE=2026-11-12
ARG VALHALLA_JDK_URL=https://download.java.net/java/early_access/jdk28/24/GPL/openjdk-28-ea+24_linux-x64_bin.tar.gz

# =============================================================================
# Stage 0: JDK Download - Fetch JDK 28 EA Build 24 (for runtime)
# =============================================================================
FROM debian:bookworm-slim AS jdk-download

ARG VALHALLA_JDK_URL

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN curl -sSL -o /tmp/jdk.tar.gz "${VALHALLA_JDK_URL}" \
    && mkdir -p /opt/jdk \
    && tar -xzf /tmp/jdk.tar.gz -C /opt/jdk --strip-components=1 \
    && rm /tmp/jdk.tar.gz

# =============================================================================
# Stage 1: Builder - Compile the NGJ compiler with JDK 25
# =============================================================================
FROM eclipse-temurin:25-jdk-jammy AS builder

WORKDIR /build

# Copy Gradle wrapper and build files first (for layer caching)
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY compiler/build.gradle.kts compiler/

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY compiler/src/ compiler/src/

# Build the fat JAR with preview features enabled
RUN ./gradlew :compiler:fatJar --no-daemon

# =============================================================================
# Stage 2: Runtime - Minimal image for running the compiler with JDK 28
# =============================================================================
FROM debian:bookworm-slim AS runtime

COPY --from=jdk-download /opt/jdk /opt/jdk
ENV JAVA_HOME=/opt/jdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

LABEL org.opencontainers.image.title="NGJ Compiler"
LABEL org.opencontainers.image.description="AIエージェント向け型安全JVM言語コンパイラ (Valhalla foundation)"
LABEL org.opencontainers.image.version="0.1.0-SNAPSHOT"
LABEL org.opencontainers.image.source="https://github.com/koduki/ngj"
LABEL org.opencontainers.image.base.name="JDK 28 EA Build 24"
LABEL org.opencontainers.image.base.feature="JEP 401 Value Objects (Preview)"

WORKDIR /app

# Copy the fat JAR from builder
COPY --from=builder /build/compiler/build/libs/compiler-*-all.jar /app/ngjc.jar

# Create a non-root user for security
RUN groupadd -r ngj && useradd -r -g ngj ngj
USER ngj

# Default entrypoint with preview features enabled
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/ngjc.jar"]
CMD ["--help"]

# =============================================================================
# Stage 3: Development - Full JDK 25 for development (Gradle compatible)
# =============================================================================
FROM eclipse-temurin:25-jdk-jammy AS development

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
