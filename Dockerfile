# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY lib/ lib/
COPY src/ src/

# Build the fat JAR
RUN mvn -q clean package -DskipTests

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/target/chess-server-1.0.0.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Dserver.port=$PORT -jar app.jar"]