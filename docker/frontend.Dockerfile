# Development and production images for the FinResolve Next.js frontend.
# Build from the repository root so npm workspaces and the generated client are available.

FROM node:24-bookworm-slim AS dependencies
WORKDIR /workspace
ENV NEXT_TELEMETRY_DISABLED=1
COPY package.json package-lock.json ./
COPY packages/api-client/package.json ./packages/api-client/package.json
COPY packages/frontend/package.json ./packages/frontend/package.json
RUN npm ci

FROM dependencies AS development
COPY packages/api-client ./packages/api-client
COPY packages/frontend ./packages/frontend
RUN npm run client:build
ENV FINRESOLVE_API_ORIGIN=http://api:8080 \
    NEXT_TELEMETRY_DISABLED=1
EXPOSE 3000
CMD ["npm", "run", "frontend:dev"]

FROM dependencies AS build
ARG FINRESOLVE_API_ORIGIN=http://api:8080
ENV FINRESOLVE_API_ORIGIN=${FINRESOLVE_API_ORIGIN} \
    NEXT_TELEMETRY_DISABLED=1 \
    NEXT_STANDALONE=1
COPY packages/api-client ./packages/api-client
COPY packages/frontend ./packages/frontend
RUN npm run client:build \
    && npm run build --workspace @finresolve/frontend

FROM node:24-bookworm-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
RUN groupadd --gid 10001 finresolve \
    && useradd --uid 10001 --gid finresolve --create-home --home-dir /app \
        --shell /usr/sbin/nologin finresolve \
    && chown -R finresolve:finresolve /app
COPY --from=build --chown=finresolve:finresolve /workspace/packages/frontend/.next/standalone ./
COPY --from=build --chown=finresolve:finresolve /workspace/packages/frontend/.next/static ./packages/frontend/.next/static
COPY --from=build --chown=finresolve:finresolve /workspace/packages/frontend/public ./packages/frontend/public
USER finresolve
EXPOSE 3000
CMD ["node", "packages/frontend/server.js"]
