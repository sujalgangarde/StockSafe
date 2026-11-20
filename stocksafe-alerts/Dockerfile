# Build from the repo root:  docker build -f stocksafe-alerts/Dockerfile -t stocksafe-alerts .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY stocksafe-core stocksafe-core
COPY stocksafe-alerts stocksafe-alerts
RUN mvn -B -q -pl stocksafe-alerts -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/stocksafe-alerts/target/stocksafe-alerts-*.jar app.jar
USER app
EXPOSE 8081
# Graceful shutdown: SIGTERM lets Spring finish in-flight requests and imports.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
