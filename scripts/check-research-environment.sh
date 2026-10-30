#!/bin/bash
# Read-only metadata check: no citation parsing or LLM generation.
set -euo pipefail
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if (( $# != 0 )); then
  printf 'Использование: bash scripts/check-research-environment.sh\n' >&2
  exit 2
fi
task_jar="$task_root/backend/target/bibliography-1.0.0.jar"
if [[ ! -f "$task_jar" ]]; then
  printf 'Сначала соберите приложение: cd backend && ./mvnw verify\n' >&2
  exit 2
fi
cd "$task_root/backend"
exec java -jar "$task_jar" "--check-research-environment=$task_root/Dataset.xlsx"
