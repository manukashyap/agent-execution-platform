#!/bin/sh
# One-shot: create and migrate the Temporal persistence + visibility databases on the shared Postgres.
set -eu
SCHEMA=/etc/temporal/schema/postgresql/v12
for db in temporal temporal_visibility; do
  dir=temporal
  [ "$db" = temporal_visibility ] && dir=visibility
  temporal-sql-tool --db "$db" create-database || true
  temporal-sql-tool --db "$db" setup-schema -v 0.0
  temporal-sql-tool --db "$db" update-schema -d "$SCHEMA/$dir/versioned"
done
