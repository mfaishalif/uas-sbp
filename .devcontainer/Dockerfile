# Copy preinstalled tools instead of downloading them through SDKMAN at build time.
FROM node:24-bookworm-slim AS node
FROM maven:3.9-eclipse-temurin-21 AS maven
FROM gradle:9-jdk21 AS gradle

FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
COPY --from=maven /usr/share/maven /usr/share/maven
COPY --from=gradle /opt/gradle /opt/gradle

ENV MAVEN_HOME=/usr/share/maven \
    GRADLE_HOME=/opt/gradle
ENV PATH="${MAVEN_HOME}/bin:${GRADLE_HOME}/bin:${PATH}"

RUN ln -sf ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -sf ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && ln -sf /usr/share/maven/bin/mvn /usr/local/bin/mvn \
    && ln -sf /opt/gradle/bin/gradle /usr/local/bin/gradle \
    && npm install --global @openai/codex @opencode/cli \
    && node --version && npm --version \
    && java --version && javac --version \
    && mvn --version && gradle --version \
    && codex --version && opencode --version
