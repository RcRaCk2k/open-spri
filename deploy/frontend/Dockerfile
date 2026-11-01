# Container image "frontend": static React build served by nginx. Build context: repository root.
FROM node:24-alpine AS build
RUN npm install -g pnpm@12.13.0
WORKDIR /src/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY docs/api/openapi.json /src/docs/api/openapi.json
COPY frontend/ ./
RUN pnpm check:api && pnpm build

FROM nginx:1.30-alpine
COPY deploy/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/frontend/dist /usr/share/nginx/html
