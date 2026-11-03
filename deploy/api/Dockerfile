# Container image "api": Spring Boot application (SOAP, REST, jobs). Build context: repository root.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY backend backend
# Release builds pass the version from the Git tag (e.g. 1.0.0-rc1); local builds keep gradle.properties.
ARG OPENSPRI_VERSION=
RUN --mount=type=cache,target=/root/.gradle ./gradlew :app:bootJar --no-daemon --console=plain ${OPENSPRI_VERSION:+-Pversion=$OPENSPRI_VERSION}

FROM eclipse-temurin:25-jre-noble
# Fixed UID/GID 10001 so that mounted secrets can be given to exactly this user (docs/betrieb.md).
RUN groupadd --system --gid 10001 openspri && useradd --system --uid 10001 --gid openspri --home-dir /app openspri
WORKDIR /app
COPY --from=build /src/backend/app/build/libs/open-spri.jar app.jar
USER openspri
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
