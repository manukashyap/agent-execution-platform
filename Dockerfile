# syntax=docker/dockerfile:1.7
# One multi-stage build for both deployables: --build-arg MODULE=app|mocks
FROM eclipse-temurin:21-jdk AS build
ARG MODULE=app
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY app/build.gradle.kts app/
COPY mocks/build.gradle.kts mocks/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :${MODULE}:dependencies > /dev/null
COPY app/src app/src
COPY mocks/src mocks/src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :${MODULE}:bootJar \
    && cp ${MODULE}/build/libs/${MODULE}-*.jar /src/service.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 aep
USER aep
WORKDIR /opt/aep
COPY --from=build /src/service.jar service.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/aep/service.jar"]
