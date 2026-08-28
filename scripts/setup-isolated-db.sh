#!/usr/bin/env bash
set -euo pipefail

# Run from the WSL Docker host. This script never alters an existing database.
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$project_dir/tools/runtime-db.env"
container="polygate-postgres-1"

docker inspect "$container" >/dev/null
database_exists="$(docker exec "$container" psql -U polygate -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='polygate'")"
if [[ "$database_exists" != "1" ]]; then
  docker exec "$container" createdb -U polygate polygate
fi

role_exists="$(docker exec "$container" psql -U polygate -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='polygate'")"
if [[ "$role_exists" != "1" ]]; then
  password="$(openssl rand -hex 32)"
  docker exec "$container" psql -U polygate -d postgres -v ON_ERROR_STOP=1 \
    -c "CREATE ROLE polygate LOGIN PASSWORD '$password' NOSUPERUSER NOCREATEDB NOCREATEROLE"
  docker exec "$container" psql -U polygate -d postgres -v ON_ERROR_STOP=1 \
    -c "ALTER DATABASE polygate OWNER TO polygate"
  umask 077
  printf 'POLYGATE_DATABASE_ENABLED=true\nPOLYGATE_DATABASE_URL=jdbc:postgresql://postgres:5432/polygate\nPOLYGATE_DATABASE_USER=polygate\nPOLYGATE_DATABASE_PASSWORD=%s\n' "$password" > "$env_file"
elif [[ ! -s "$env_file" ]]; then
  echo "The Java role exists but its local credential file is missing; stop to avoid resetting credentials." >&2
  exit 1
fi

if ! grep -q '^POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=' "$env_file"; then
  admin_password="$(openssl rand -hex 24)"
  printf 'POLYGATE_ADMIN_BOOTSTRAP_PASSWORD=%s\n' "$admin_password" >> "$env_file"
fi

echo "Isolated Java database and role are ready. Credentials remain in an ignored local runtime file."
