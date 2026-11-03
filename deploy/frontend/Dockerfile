# Container image "frontend": static React build served by nginx. Build context: repository root.
FROM node:24-alpine AS build
RUN npm install -g pnpm@12.13.0
WORKDIR /src/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY docs/api/openapi.json /src/docs/api/openapi.json
COPY frontend/ ./
RUN pnpm check:api && pnpm build

FROM nginx:1.31-alpine
# security fixes of Alpine packages ahead of the next nginx image (Trivy in CI)
RUN apk upgrade --no-cache
COPY deploy/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/frontend/security-headers.conf /etc/nginx/openspri/security-headers.conf
COPY --from=build /src/frontend/dist /usr/share/nginx/html
