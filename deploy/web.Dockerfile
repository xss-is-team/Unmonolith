# Web image: Next.js build; /api/v1/* is proxied to the API origin that is fixed at build time.
FROM node:22-slim AS build
ARG BLUEPRINTS_API_ORIGIN=http://api:3000
ENV BLUEPRINTS_API_ORIGIN=$BLUEPRINTS_API_ORIGIN \
    NEXT_TELEMETRY_DISABLED=1
RUN corepack enable
WORKDIR /app
COPY apps/web/package.json apps/web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY apps/web .
RUN pnpm build

FROM node:22-slim
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1
RUN corepack enable
WORKDIR /app
COPY --from=build /app .
EXPOSE 3001
CMD ["pnpm", "start"]
