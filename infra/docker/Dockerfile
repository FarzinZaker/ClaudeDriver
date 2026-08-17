# syntax=docker/dockerfile:1

# ---- Stage 0: build the React operator dashboard ----
# Produces web/dist/ (static SPA), served same-origin by the backend so the
# WebAuthn RP ID / origin match the API host.
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ---- Stage 1: build the Ktor backend distribution with Gradle ----
# Produces backend/build/install/backend/ (an install/distribution with a
# launch script under bin/ and libs under lib/) via `:backend:installDist`.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /src

# Copy the Gradle wrapper + build scripts first for better layer caching.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle.properties* ./

# Copy the multi-module sources needed to build :backend (shared is consumed).
COPY shared ./shared
COPY backend ./backend

RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon :backend:installDist

# ---- Stage 2: minimal JRE runtime, non-root ----
FROM eclipse-temurin:21-jre AS runtime

# Run as an unprivileged user (least-privilege at the container layer).
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# Copy only the built distribution from the build stage.
COPY --from=build --chown=app:app /src/backend/build/install/backend/ /app/

# Copy the built SPA; the backend serves it from WEB_ROOT (default "web" → /app/web).
COPY --from=web --chown=app:app /web/dist/ /app/web/

USER app

EXPOSE 8080

ENV JAVA_OPTS=""

# installDist emits bin/backend as the launch script.
ENTRYPOINT ["/app/bin/backend"]
