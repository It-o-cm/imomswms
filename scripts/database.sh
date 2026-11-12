#!/bin/sh
# Offline H2 maintenance. Stop the application before opening its file database.
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TASK_ROOT"
if [ "$#" -eq 0 ]; then
  echo 'Usage: DATABASE_URL=jdbc:h2:file:./data/imomswms scripts/database.sh status|migrate|backup [directory]|restore <backup-directory>' >&2
  exit 2
fi
# Recompile before maintenance so the CLI and migration resources cannot be stale.
./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.includeScope=runtime -Dmdep.outputFile=target/database-classpath.txt
TASK_CP=$(cat target/database-classpath.txt)
exec java -cp "target/classes:$TASK_CP" com.intermarche.backoffice.database.DatabaseMaintenance "$@"
