#!/bin/bash
# Zero-downtime deploy for the appeals app: builds the new image, starts it on the
# currently-inactive port, health-checks it, switches nginx over, THEN removes the
# old container. nginx never points at a dead backend.
set -euo pipefail
cd ~/appeals

CONF=/etc/nginx/sites-available/appeals.conf
CURRENT_PORT=$(grep -oP 'proxy_pass http://127\.0\.0\.1:\K[0-9]+' "$CONF")

if [ "$CURRENT_PORT" = "8095" ]; then
    NEW_PORT=8096
    NEW_NAME=gatemc-appeals-green
    OLD_NAME=gatemc-appeals-blue
else
    NEW_PORT=8095
    NEW_NAME=gatemc-appeals-blue
    OLD_NAME=gatemc-appeals-green
fi

echo "==> Current live port: $CURRENT_PORT. Deploying $NEW_NAME on port $NEW_PORT."

echo "==> Building image..."
sudo docker build -t gatemc-appeals:latest .

echo "==> Starting $NEW_NAME..."
sudo docker rm -f "$NEW_NAME" >/dev/null 2>&1 || true
sudo docker run -d --name "$NEW_NAME" --restart unless-stopped --network pterodactyl_nw \
    -p "127.0.0.1:${NEW_PORT}:8095" \
    -v /home/gatemc/appeals-uploads:/app/uploads \
    --env-file /home/gatemc/appeals/.env \
    gatemc-appeals:latest >/dev/null

echo "==> Waiting for $NEW_NAME to become healthy..."
HEALTHY=0
for i in $(seq 1 30); do
    if curl -sf -o /dev/null "http://127.0.0.1:${NEW_PORT}/staff/login"; then
        HEALTHY=1
        break
    fi
    sleep 1
done

if [ "$HEALTHY" != "1" ]; then
    echo "==> FAILED health check after 30s. Leaving old container ($OLD_NAME, port $CURRENT_PORT) live."
    echo "==> Logs from the failed container:"
    sudo docker logs "$NEW_NAME" --tail 40
    exit 1
fi

echo "==> Healthy. Switching nginx to port $NEW_PORT..."
sudo sed -i "s/proxy_pass http:\/\/127\.0\.0\.1:[0-9]\+;/proxy_pass http:\/\/127.0.0.1:${NEW_PORT};/" "$CONF"
sudo nginx -t
sudo systemctl reload nginx

echo "==> Live on $NEW_NAME:$NEW_PORT. Removing old container ($OLD_NAME)..."
sudo docker rm -f "$OLD_NAME" >/dev/null 2>&1 || true

echo "==> Deploy complete, zero downtime."
