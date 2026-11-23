FROM node:22-bookworm-slim

WORKDIR /app
COPY --chown=node:node . .
RUN --mount=type=secret,id=npm_ca,uid=1000 \
    apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && install -d -o node -g node /var/lib/momento/media \
    && chown node:node /app \
    && runuser -u node -- sh -c 'if [ -f /run/secrets/npm_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/npm_ca; fi; npm ci --omit=dev --no-audit --no-fund'

ENV NODE_ENV=production \
    PORT=8080 \
    MOMENTO_MEDIA_DIR=/var/lib/momento/media

USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD node -e 'fetch("http://127.0.0.1:"+process.env.PORT+"/ready").then(r=>{if(!r.ok)process.exitCode=1}).catch(()=>{process.exitCode=1})'
CMD ["node", "src/server.js"]
