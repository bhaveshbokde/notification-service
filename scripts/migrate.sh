#!/usr/bin/env bash
#
# Applies Flyway migrations.
#
#   ./scripts/migrate.sh          notification_db       (development)
#   ./scripts/migrate.sh test     notification_test_db  (integration tests)
#   ./scripts/migrate.sh info     show the applied/pending migration list
#
# Credentials are read from ./.env and exported as FLYWAY_* environment
# variables, which Flyway reads natively. They are deliberately NOT passed as
# -D arguments: anything on a command line is visible to every user on the
# machine via `ps`, and lands in shell history.
#
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; DIM=$'\033[2m'; NC=$'\033[0m'
log() { printf '  %s✓%s %s\n' "$GREEN" "$NC" "$*"; }
die() { printf '  %s✗%s %s\n' "$RED" "$NC" "$*" >&2; exit 1; }

[[ -f .env ]] || die ".env not found. Run:  sudo bash scripts/provision-db.sh"

# shellcheck disable=SC1091
set -a; . ./.env; set +a

GOAL="migrate"
case "${1:-}" in
  test)
    TARGET_URL="$NOTIFICATION_TEST_DB_URL"; TARGET_NAME="notification_test_db" ;;
  info)
    TARGET_URL="$NOTIFICATION_DB_URL";      TARGET_NAME="notification_db"; GOAL="info" ;;
  "")
    TARGET_URL="$NOTIFICATION_DB_URL";      TARGET_NAME="notification_db" ;;
  *)
    die "Usage: $0 [test|info]" ;;
esac

printf '\n%sNotification Service :: Flyway — %s%s\n' "$DIM" "$TARGET_NAME" "$NC"
printf '%s------------------------------------------%s\n' "$DIM" "$NC"

export FLYWAY_URL="$TARGET_URL"
export FLYWAY_USER="$NOTIFICATION_DB_USERNAME"
export FLYWAY_PASSWORD="$NOTIFICATION_DB_PASSWORD"
export FLYWAY_LOCATIONS="filesystem:src/main/resources/db/migration"
export FLYWAY_BASELINE_ON_MIGRATE=false
export FLYWAY_VALIDATE_ON_MIGRATE=true
export FLYWAY_CLEAN_DISABLED=true

./mvnw -q -o "flyway:$GOAL" || ./mvnw -q "flyway:$GOAL"

if [[ "$GOAL" == "migrate" ]]; then
  tables="$(PGPASSWORD="$NOTIFICATION_DB_PASSWORD" psql \
    -h "$(sed -E 's#.*//([^:]+):.*#\1#' <<<"$TARGET_URL")" \
    -p "$(sed -E 's#.*:([0-9]+)/.*#\1#'  <<<"$TARGET_URL")" \
    -U "$NOTIFICATION_DB_USERNAME" \
    -d "$(sed -E 's#.*/([^/?]+).*#\1#'   <<<"$TARGET_URL")" \
    -qtAX -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" 2>/dev/null || echo '?')"
  log "$TARGET_NAME: migrations applied, $tables table(s) present"
fi

printf '\n'
