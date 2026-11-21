#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

CONF=/etc/nginx/sites-available/gatemc-media.conf
CURRENT_PORT=$(grep -oP 'proxy_pass http://127\.0\.0\.1:\K[0-9]+' "$CONF" 2>/dev/null || echo none)

if [ "$CURRENT_PORT" = "8106" ]; then
    NEW_PORT=8107
    NEW_NAME=gatemc-media-green
    OLD_NAME=gatemc-media-blue
else
    NEW_PORT=8106
    NEW_NAME=gatemc-media-blue
    OLD_NAME=gatemc-media-green
fi

echo "==> Current live port: $CURRENT_PORT. Deploying $NEW_NAME on port $NEW_PORT."
sudo docker build -t gatemc-media:latest .

sudo docker rm -f "$NEW_NAME" >/dev/null 2>&1 || true
sudo docker run -d --name "$NEW_NAME" --restart unless-stopped --network pterodactyl_nw \
    -p "127.0.0.1:${NEW_PORT}:8104" \
    --env-file /home/hawkmc/gatemc-media/.env \
    gatemc-media:latest >/dev/null

HEALTHY=0
for i in $(seq 1 30); do
    if curl -sf -o /dev/null "http://127.0.0.1:${NEW_PORT}/healthz"; then
        HEALTHY=1
        break
    fi
    sleep 1
done

if [ "$HEALTHY" != "1" ]; then
    echo "==> FAILED health check. Leaving the old container live."
    sudo docker logs "$NEW_NAME" --tail 40
    exit 1
fi

if [ -f "$CONF" ]; then
    sudo sed -i "s/proxy_pass http:\/\/127\.0\.0\.1:[0-9]\+;/proxy_pass http:\/\/127.0.0.1:${NEW_PORT};/" "$CONF"
    sudo nginx -t
    sudo systemctl reload nginx
fi

sudo docker rm -f "$OLD_NAME" >/dev/null 2>&1 || true
echo "==> Live on $NEW_NAME:$NEW_PORT."
