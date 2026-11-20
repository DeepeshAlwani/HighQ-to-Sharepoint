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
COPY --from=api /src/target/backend-0.0.1-SNAPSHOT.jar app.jar
# Small hosting plans have little memory: let the JVM size itself from the container limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
