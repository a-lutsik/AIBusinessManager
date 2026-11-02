# syntax=docker/dockerfile:1.7
#
# Cadence web console and public booking pages: the static web export of frontend/ behind nginx.
# Build context: the repository root (docker-compose.prod.yml), which docker/web/Dockerfile.dockerignore narrows down.
#
# EXPO_PUBLIC_* are compiled into the bundle, so they are build arguments and a changed value needs a rebuild. None of
# them has a default: the source falls back to localhost when one is missing, which a production bundle must never do.

FROM node:22.16.0-bookworm-slim AS build
ARG EXPO_PUBLIC_API_URL
ARG EXPO_PUBLIC_OIDC_ISSUER
ARG EXPO_PUBLIC_AUTH_MODE
# First, before anything slow: an empty value stops the build with the name of the variable.
RUN set -eu; \
    for name in EXPO_PUBLIC_API_URL EXPO_PUBLIC_OIDC_ISSUER EXPO_PUBLIC_AUTH_MODE; do \
        eval "value=\${$name:-}"; \
        if [ -z "$value" ]; then echo "ERROR: build argument $name is empty, the bundle would fall back to localhost" >&2; exit 1; fi; \
    done; \
    if [ "$EXPO_PUBLIC_AUTH_MODE" != oidc ]; then echo "ERROR: EXPO_PUBLIC_AUTH_MODE must be oidc in this image" >&2; exit 1; fi
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
ENV CI=1 EXPO_NO_TELEMETRY=1 NODE_ENV=production
RUN npx expo export --platform web --output-dir /out/html
# The Content-Security-Policy names the two origins the bundle talks to.
COPY docker/web/security-headers.conf.template /tmp/security-headers.conf.template
RUN set -eu; \
    origin() { printf '%s' "$1" | sed -E 's#^(https?://[^/]+).*#\1#'; }; \
    sed -e "s#__API_ORIGIN__#$(origin "$EXPO_PUBLIC_API_URL")#g" \
        -e "s#__ISSUER_ORIGIN__#$(origin "$EXPO_PUBLIC_OIDC_ISSUER")#g" \
        /tmp/security-headers.conf.template > /out/security-headers.conf

# Runs as uid 101 and keeps its pid and temp files in /tmp, so the container works with a read-only root file system.
FROM nginxinc/nginx-unprivileged:1.31.7-alpine AS runtime
COPY docker/web/default.conf /etc/nginx/conf.d/default.conf
COPY docker/web/html-headers.conf docker/web/hashed-headers.conf /etc/nginx/cadence/
COPY --from=build /out/security-headers.conf /etc/nginx/cadence/security-headers.conf
COPY --from=build /out/html /usr/share/nginx/html
EXPOSE 8082
