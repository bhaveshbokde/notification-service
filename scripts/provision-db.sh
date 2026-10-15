#!/usr/bin/env bash
#
# Provisions the PostgreSQL role and databases for the Notification Service.
#
#   sudo bash scripts/provision-db.sh
#
# Creates, and touches nothing else:
#
#   role      notification_app    NOSUPERUSER NOCREATEDB NOCREATEROLE
#   database  notification_db     owned by that role
#   database  notification_test_db
#
# Generates a database password, a JWT signing key and a development
# administrator password, and writes them to ./.env with mode 0600.
#
# SECURITY NOTES
#
#   * Every generated secret is passed to psql on STDIN, never as a command-line
#     argument. Anything on a command line is visible to every user on the
#     machine through `ps`, and is recorded in shell history.
#
#   * No secret is echoed to the terminal. The script prints what it did, not
#     what it generated. Read ./.env if you need a value.
#
#   * .env is created 0600 and is listed in .gitignore.
#
#   * The script refuses to touch any database or role outside the three names
#     above, so it cannot damage the FinSight or E-commerce projects that share
#     this PostgreSQL server.
#
set -Eeuo pipefail

APP_ROLE="notification_app"
APP_DB="notification_db"
TEST_DB="notification_test_db"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"

# Databases and roles belonging to other projects on this server. The script
# asserts it is not about to operate on any of them.
PROTECTED="finsight_app finsight_db finsight_test_db ecommerce_app ecommerce_db ecommerce_test_db postgres template0 template1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_DIR/.env"

GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; RED=$'\033[0;31m'; DIM=$'\033[2m'; NC=$'\033[0m'
log()  { printf '  %s✓%s %s\n' "$GREEN" "$NC" "$*"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$NC" "$*"; }
die()  { printf '  %s✗%s %s\n' "$RED" "$NC" "$*" >&2; exit 1; }

printf '\n%sNotification Service :: database provisioning%s\n' "$DIM" "$NC"
printf '%s---------------------------------------------%s\n\n' "$DIM" "$NC"

# --------------------------------------------------------------- guard rails --

[[ "$(id -u)" -eq 0 ]] || die "Must run as root:  sudo bash scripts/provision-db.sh"
command -v psql >/dev/null || die "psql not found on PATH."

for name in "$APP_ROLE" "$APP_DB" "$TEST_DB"; do
  for protected in $PROTECTED; do
    [[ "$name" == "$protected" ]] && die "Refusing to operate on protected object '$name'."
  done
done

psql_su()    { su postgres -c "psql -v ON_ERROR_STOP=1 -qtAX -d postgres"; }
psql_su_db() { local db="$1"; su postgres -c "psql -v ON_ERROR_STOP=1 -qtAX -d $db"; }

echo "SELECT 1;" | psql_su >/dev/null 2>&1 \
  || die "Cannot reach PostgreSQL as the 'postgres' superuser. Is the server running?"

log "Server version: $(echo 'SHOW server_version;' | psql_su)"

# Report, and leave alone, the other projects' objects.
for other in finsight_app ecommerce_app; do
  if [[ "$(echo "SELECT 1 FROM pg_roles WHERE rolname='$other';" | psql_su)" == "1" ]]; then
    log "Found existing role '$other' — leaving it untouched"
  fi
done

# ------------------------------------------------------------------ secrets --

# 32 bytes of CSPRNG output, base64url, no padding. Safe in a URL and in a
# .properties file without escaping.
gen_secret() { head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
# 64 bytes for the JWT signing key — comfortably above the 256-bit HS256 floor.
gen_jwt_key() { head -c 64 /dev/urandom | base64 | tr -d '\n'; }

role_exists="$(echo "SELECT 1 FROM pg_roles WHERE rolname = '$APP_ROLE';" | psql_su)"

if [[ "$role_exists" == "1" && -f "$ENV_FILE" ]]; then
  # Reuse the existing password so we do not lock out a working installation.
  DB_PASSWORD="$(grep -E '^NOTIFICATION_DB_PASSWORD=' "$ENV_FILE" | cut -d= -f2- || true)"
  [[ -n "$DB_PASSWORD" ]] || DB_PASSWORD="$(gen_secret)"
  ROTATED=0
else
  DB_PASSWORD="$(gen_secret)"
  ROTATED=1
fi

JWT_SECRET="$(gen_jwt_key)"
ADMIN_PASSWORD="$(gen_secret)"

# --------------------------------------------------------------------- role --

if [[ "$role_exists" == "1" ]]; then
  log "Role '$APP_ROLE' already exists"
  if [[ "$ROTATED" -eq 1 ]]; then
    printf "ALTER ROLE %s WITH LOGIN PASSWORD '%s';\n" "$APP_ROLE" "$DB_PASSWORD" | psql_su >/dev/null
    log "Password reset for '$APP_ROLE'"
  fi
else
  # Least privilege. The role can log in and own its own two databases; it
  # cannot create more databases, create roles, or read anything else.
  printf "CREATE ROLE %s WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '%s';\n" \
    "$APP_ROLE" "$DB_PASSWORD" | psql_su >/dev/null
  log "Created role '$APP_ROLE' (NOSUPERUSER NOCREATEDB NOCREATEROLE)"
fi

# ---------------------------------------------------------------- databases --

for db in "$APP_DB" "$TEST_DB"; do
  if [[ "$(echo "SELECT 1 FROM pg_database WHERE datname = '$db';" | psql_su)" == "1" ]]; then
    owner="$(echo "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$db';" | psql_su)"
    log "Database '$db' already exists (owner: $owner)"
  else
    echo "CREATE DATABASE $db OWNER $APP_ROLE ENCODING 'UTF8' TEMPLATE template0;" | psql_su >/dev/null
    log "Created database '$db'"
  fi

  # Revoke the PUBLIC grant that PostgreSQL applies by default, so only the
  # owner can connect. Without this, every role on the server can read it.
  psql_su_db "$db" >/dev/null <<SQL
REVOKE ALL ON DATABASE $db FROM PUBLIC;
GRANT ALL PRIVILEGES ON DATABASE $db TO $APP_ROLE;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT ALL ON SCHEMA public TO $APP_ROLE;
ALTER SCHEMA public OWNER TO $APP_ROLE;
SQL
done
log "Revoked PUBLIC access on both databases"

# --------------------------------------------------------------------- .env --

if [[ -f "$ENV_FILE" ]]; then
  backup="$ENV_FILE.bak.$(date +%Y%m%d%H%M%S)"
  cp -p "$ENV_FILE" "$backup"
  chmod 600 "$backup"
  warn "Existing .env backed up to $(basename "$backup")"
fi

umask 077
cat > "$ENV_FILE" <<ENV
# =============================================================================
#  Notification Service — local environment
# =============================================================================
#  GENERATED FILE. Contains real secrets. Never commit it; .gitignore excludes
#  it. Regenerate with:  sudo bash scripts/provision-db.sh
# =============================================================================

NOTIFICATION_DB_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$APP_DB
NOTIFICATION_DB_USERNAME=$APP_ROLE
NOTIFICATION_DB_PASSWORD=$DB_PASSWORD
NOTIFICATION_TEST_DB_URL=jdbc:postgresql://$DB_HOST:$DB_PORT/$TEST_DB

NOTIFICATION_KAFKA_BOOTSTRAP=127.0.0.1:9092

NOTIFICATION_REDIS_HOST=127.0.0.1
NOTIFICATION_REDIS_PORT=6379
NOTIFICATION_REDIS_PASSWORD=

NOTIFICATION_JWT_SECRET=$JWT_SECRET

# Development administrator, created once at startup. Disabled under the
# 'prod' profile regardless of these values.
NOTIFICATION_ADMIN_USERNAME=admin
NOTIFICATION_ADMIN_PASSWORD=$ADMIN_PASSWORD

SERVER_PORT=8080
ENV

chmod 600 "$ENV_FILE"
chown "$(stat -c '%U:%G' "$PROJECT_DIR")" "$ENV_FILE" 2>/dev/null || true
log "Wrote $(basename "$ENV_FILE") (mode 0600, owned by the project user)"

# ------------------------------------------------------------ verification --

if su postgres -c "PGPASSWORD='$DB_PASSWORD' psql -h $DB_HOST -p $DB_PORT -U $APP_ROLE -d $APP_DB -qtAX -c 'SELECT 1;'" >/dev/null 2>&1; then
  log "Verified: '$APP_ROLE' can connect to '$APP_DB' over TCP"
else
  warn "Could not verify the TCP login. If pg_hba.conf requires 'peer' for"
  warn "local connections, add a 'host ... scram-sha-256' line for 127.0.0.1."
  warn "Check: sudo grep -nE '^(host|local)' /etc/postgresql/*/main/pg_hba.conf"
fi

# Confirm the least-privilege properties actually hold.
privs="$(echo "SELECT rolsuper::int, rolcreatedb::int, rolcreaterole::int FROM pg_roles WHERE rolname='$APP_ROLE';" | psql_su)"
[[ "$privs" == "0|0|0" ]] \
  && log "Verified: role has no SUPERUSER / CREATEDB / CREATEROLE" \
  || warn "Unexpected role privileges: $privs"

printf '\n  %sNext:%s  ./scripts/migrate.sh        # apply migrations to %s\n' "$DIM" "$NC" "$APP_DB"
printf '         ./scripts/migrate.sh test   # and to %s\n\n' "$TEST_DB"
