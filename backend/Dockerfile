FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 rail
COPY --from=build /workspace/target/rail-transit-sim-0.0.1-SNAPSHOT.jar app.jar

USER rail
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
