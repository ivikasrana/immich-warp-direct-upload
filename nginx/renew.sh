#!/bin/sh
cd "$(dirname "$0")" || exit 1
docker run --rm \
  -v "$PWD/letsencrypt:/etc/letsencrypt" \
  -v "$PWD/cloudflare.ini:/cloudflare.ini:ro" \
  certbot/dns-cloudflare renew \
  --dns-cloudflare \
  --dns-cloudflare-credentials /cloudflare.ini \
  --quiet
docker exec photos-nginx nginx -s reload
