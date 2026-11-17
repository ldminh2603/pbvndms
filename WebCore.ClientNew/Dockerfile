FROM node:24-alpine AS build
WORKDIR /app

# NEXT_PUBLIC_* variables are embedded in the browser bundle by Next.js at build time.
ARG NEXT_PUBLIC_WEBCORE_WS_URL
ENV NEXT_PUBLIC_WEBCORE_WS_URL=${NEXT_PUBLIC_WEBCORE_WS_URL}

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY cbs/package.json cbs/package.json
RUN corepack enable && pnpm install --frozen-lockfile

COPY . ./
# The demo consumes the workspace library's generated declarations. Build the
# library in the image instead of relying on a host-generated dist directory.
RUN pnpm build && pnpm cbs:build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3001
ENV HOSTNAME=0.0.0.0

COPY --from=build /app/cbs/.next/standalone ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/cbs/public ./cbs/public
COPY --from=build /app/cbs/.next/static ./cbs/.next/static

EXPOSE 3001
WORKDIR /app/cbs
CMD ["node", "server.js"]
