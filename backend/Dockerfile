FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
# 7-Zip unpacks .7z and .rar archives found during a copy (7zip-rar adds RAR support)
RUN apt-get update && apt-get install -y --no-install-recommends 7zip 7zip-rar && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
