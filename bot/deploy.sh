#!/bin/bash
set -euo pipefail
cd ~/bot

sudo mkdir -p /home/hawkmc/ticket-files
sudo mkdir -p /home/hawkmc/bot-state

echo "==> Building image..."
sudo docker build -t hawkeye-bot:latest .

echo "==> Stopping old container (if any)..."
sudo docker rm -f hawkeye-bot >/dev/null 2>&1 || true

echo "==> Starting hawkeye-bot..."
sudo docker run -d --name hawkeye-bot --restart unless-stopped \
    --network pterodactyl_nw \
    --env-file /home/hawkmc/bot/.env \
    -v /home/hawkmc/ticket-files:/app/ticket-files \
    -v /home/hawkmc/bot-state:/app/state \
    -v /var/lib/pterodactyl/volumes/581f53f0-ac00-4b81-ad4e-f3a1185b28bd/plugins/HawkMC/config.yml:/app/hawkmc-config.yml:ro \
    hawkeye-bot:latest >/dev/null

echo "==> Waiting for it to log in..."
LOGGED_IN=0
for i in $(seq 1 20); do
    if sudo docker logs hawkeye-bot 2>&1 | grep -q 'logged in as'; then
        LOGGED_IN=1
        break
    fi
    if [ "$(sudo docker inspect -f '{{.State.Running}}' hawkeye-bot 2>/dev/null)" != "true" ]; then
        break
    fi
    sleep 1
done

if [ "$LOGGED_IN" != "1" ]; then
    echo "==> FAILED to log in after 20s."
    sudo docker logs hawkeye-bot --tail 40
    exit 1
fi

echo "==> hawkeye-bot is online."
