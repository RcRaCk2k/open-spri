# Container image "api": Spring Boot application (SOAP, REST, jobs). Build context: repository root.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY backend backend
RUN --mount=type=cache,target=/root/.gradle ./gradlew :app:bootJar --no-daemon --console=plain

FROM eclipse-temurin:25-jre-noble
RUN groupadd --system openspri && useradd --system --gid openspri --home-dir /app openspri
WORKDIR /app
COPY --from=build /src/backend/app/build/libs/open-spri.jar app.jar
USER openspri
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
