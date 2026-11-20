# One image for hosting (Render): the React site is built into the Spring Boot app and served
# from the same address as the API, so sign-in cookies just work. Local development still uses
# docker compose (backend/Dockerfile) plus the Vite dev server.
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm test && npm run build

FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src src
COPY --from=web /web/dist src/main/resources/static
RUN mvn -q -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
# 7-Zip unpacks .7z and .rar archives found during a copy (7zip-rar adds RAR support)
RUN apt-get update && apt-get install -y --no-install-recommends 7zip 7zip-rar && rm -rf /var/lib/apt/lists/*
COPY --from=api /src/target/backend-0.0.1-SNAPSHOT.jar app.jar
# Heap is half the container: on Render's 512 MB plan, 75% (384 MB) plus the JVM's own memory can
# pass the limit; the instance is then killed mid-request and users see HTTP 520.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
