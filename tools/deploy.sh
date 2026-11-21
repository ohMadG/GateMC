#!/bin/bash
set -euo pipefail

target=${1:-}
case "$target" in
  dev)
    branch=dev
    volume=8cffb6a6-34fa-49c1-ab57-061a16e6f453
    server_id=9
    ;;
  production)
    branch=master
    volume=bd69a4bd-4b61-4a5d-938b-40038a260c27
    server_id=
    ;;
  *)
    echo "usage: tools/deploy.sh dev|production" >&2
    exit 1
    ;;
esac

repo=$(cd "$(dirname "$0")/.." && pwd)
mvn=${MVN:-$HOME/tools/apache-maven-3.9.9/bin/mvn}
dest=/var/lib/pterodactyl/volumes/$volume/plugins/update/GensCore-1.0.0.jar

git -C "$repo" fetch -q origin
work=$(mktemp -d)
trap 'git -C "$repo" worktree remove --force "$work" >/dev/null 2>&1 || true' EXIT
git -C "$repo" worktree add -q --detach "$work" "origin/$branch"
echo "building origin/$branch at $(git -C "$work" log -1 --format='%h %s')"
(cd "$work" && "$mvn" -q clean package -DskipTests)

sudo install -D -o pterodactyl -g pterodactyl -m 644 "$work/target/GensCore-1.0.0.jar" "$dest"
echo "staged $(sha1sum "$work/target/GensCore-1.0.0.jar" | cut -c1-12) in $dest"

if [ -n "$server_id" ]; then
  (cd /var/www/pterodactyl && sudo -u www-data HOME=/tmp XDG_CONFIG_HOME=/tmp/psysh-cfg php artisan tinker --execute="\$s=\\Pterodactyl\\Models\\Server::find($server_id); app(\\Pterodactyl\\Repositories\\Wings\\DaemonPowerRepository::class)->setServer(\$s)->send('restart'); echo 'restarting dev';")
  echo
else
  echo "production is not restarted; it goes live at the next restart"
fi
