#!/bin/bash
# Nightly backup of the appeals app's uploaded screenshots. Keeps the last 14 days.
set -euo pipefail

SRC=/home/gatemc/appeals-uploads
DEST=/home/gatemc/appeals-backups
TS=$(date +%Y%m%d-%H%M%S)

mkdir -p "$DEST"
tar -czf "$DEST/appeals-uploads-${TS}.tar.gz" -C "$SRC" .

ls -1t "$DEST"/appeals-uploads-*.tar.gz 2>/dev/null | tail -n +15 | xargs -r rm --

echo "$(date -Iseconds) backed up to appeals-uploads-${TS}.tar.gz"
