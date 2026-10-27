# Multi-stage build so the runtime image contains only the packaged jar and JRE.
FROM eclipse-temurin:21-jdk-jammy AS build

# The Maven Wrapper needs unzip to use its pinned .zip distribution checksum.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY . .
RUN chmod +x backend/mvnw \
    && cd backend \
    && ./mvnw -B -DskipTests package

# Development target: source is synchronized by Compose Watch, then Maven recompiles
# from scratch and runs Spring Boot. Keeping target/ inside the container preserves
# clean builds and class deletions without writing build output back to the host.
FROM eclipse-temurin:21-jdk-jammy AS development

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY . .

RUN chmod +x backend/mvnw \
    && cp docker/development-entrypoint.sh /usr/local/bin/finresolve-development \
    && chmod +x /usr/local/bin/finresolve-development

ENV FINRESOLVE_DB_PATH=/data/finresolve.db \
    FINRESOLVE_DEMO_SAMPLE_PATH=/workspace/data/cfpb/sample.csv \
    SERVER_ADDRESS=0.0.0.0

WORKDIR /workspace/backend
EXPOSE 8080
# The entrypoint runs ./mvnw -B -DskipTests clean spring-boot:run and keeps the
# container waiting after a failed build so Compose Watch can retry on the next change.
CMD ["finresolve-development"]

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 finresolve \
    && useradd --uid 10001 --gid finresolve --create-home --home-dir /app \
        --shell /usr/sbin/nologin finresolve \
    && mkdir -p /app/data/cfpb /data \
    && chown -R finresolve:finresolve /app /data

WORKDIR /app
COPY --from=build --chown=finresolve:finresolve \
    /workspace/backend/target/finresolve-backend-0.1.0.jar /app/finresolve-backend.jar
COPY --from=build --chown=finresolve:finresolve \
    /workspace/data/cfpb/sample.csv /app/data/cfpb/sample.csv
COPY --from=build --chown=finresolve:finresolve \
    /workspace/data/cfpb/manifest.json /app/data/cfpb/manifest.json

ENV FINRESOLVE_DB_PATH=/data/finresolve.db \
    FINRESOLVE_DEMO_SAMPLE_PATH=/app/data/cfpb/sample.csv \
    SERVER_ADDRESS=0.0.0.0

USER finresolve
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/finresolve-backend.jar"]
