#!/usr/bin/env bash
set -euo pipefail
: "${MOMENTO_TEST_DATABASE_URL:?Set an isolated test database URL}"
for migration in db/migrations/*.sql; do
  echo "Applying ${migration}"
  psql "$MOMENTO_TEST_DATABASE_URL" -v ON_ERROR_STOP=1 -f "$migration"
done
