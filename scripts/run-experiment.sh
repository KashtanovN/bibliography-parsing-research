#!/bin/bash
# Full fixed-configuration comparison. Human review decisions are never fabricated.
set -euo pipefail
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if (( $# != 0 )); then
  printf 'Использование: bash scripts/run-experiment.sh\n' >&2
  exit 2
fi
task_dev="$task_root/research-runs/dev-four-methods"
task_test="$task_root/research-runs/test-four-methods"
task_protocol="$task_dev/frozen-protocol"
task_state="$task_root/research-runs/experiment"
task_dataset="$task_root/Dataset.xlsx"
task_jar="$task_root/backend/target/bibliography-1.0.0.jar"
if [[ ! -f "$task_jar" ]]; then printf 'Нет собранного JAR. Выполните cd backend && ./mvnw verify\n' >&2; exit 2; fi
for task_path in "$task_dev" "$task_dev.pipeline-status" "$task_test" "$task_test.pipeline-status" "$task_state.pipeline-status"; do
  if [[ -e "$task_path" ]]; then printf 'Путь эксперимента уже существует: %s\n' "$task_path" >&2; exit 2; fi
done
if ! command -v python3 >/dev/null 2>&1; then printf 'Нужен Python 3.9+ для сводки.\n' >&2; exit 2; fi
cd "$task_root"
shasum -a 256 -c Dataset.sha256
mkdir -p "$task_root/research-runs"
if ! mkdir "$task_state.lock"; then printf 'Другой общий процесс уже запущен.\n' >&2; exit 2; fi
task_child=''
task_sleep_guard=''
task_set_state() {
  printf '%s\n' "$1" > "$task_state.pipeline-status.tmp.$$"
  mv "$task_state.pipeline-status.tmp.$$" "$task_state.pipeline-status"
}
task_cleanup() {
  local task_exit=$?
  trap - EXIT
  if [[ -n "$task_child" ]]; then kill -TERM "$task_child" 2>/dev/null || true; fi
  if [[ -n "$task_sleep_guard" ]]; then kill "$task_sleep_guard" 2>/dev/null || true; fi
  if (( task_exit != 0 )); then
    if [[ "$(cat "$task_state.pipeline-status" 2>/dev/null || true)" != CANCELLED ]]; then task_set_state FAILED; fi
  fi
  rmdir "$task_state.lock" 2>/dev/null || true
  exit "$task_exit"
}
trap task_cleanup EXIT
trap 'task_set_state CANCELLED; exit 130' INT
trap 'task_set_state CANCELLED; exit 143' TERM
printf '%s\n' "$$" > "$task_state.pipeline.pid"
date +%s > "$task_state.started"
if [[ -x /usr/bin/caffeinate ]]; then /usr/bin/caffeinate -is -w "$$" >/dev/null 2>&1 & task_sleep_guard=$!; fi
task_run() {
  "$@" &
  task_child=$!
  local task_code=0
  wait "$task_child" || task_code=$?
  task_child=''
  return "$task_code"
}
task_check_dataset() { (cd "$task_root" && shasum -a 256 -c Dataset.sha256); }
task_set_state CHECKING_ENVIRONMENT
task_run bash "$task_root/scripts/check-research-environment.sh" > "$task_root/research-runs/environment.json"
task_check_dataset
task_set_state DEV
task_run bash "$task_root/scripts/run-dev.sh" "$task_dev"
task_check_dataset
task_set_state FREEZING
cd "$task_root/backend"
task_run java -jar "$task_jar" "--freeze-experiment=$task_dev" "--dataset=$task_dataset"
cd "$task_root"
task_check_dataset
task_set_state TEST
task_run bash "$task_root/scripts/run-final-test.sh" "$task_test" "$task_protocol"
task_check_dataset
task_set_state SUMMARIZING
task_run python3 "$task_root/scripts/summarize-run.py" "$task_test"
task_check_dataset
task_set_state AUTOMATIC_COMPLETED_REVIEW_REQUIRED
printf 'Автоматические этапы завершены. Сводка: %s/article/report.md\nПроверка оформления и добавлений: %s/quality/review.json\n' "$task_test" "$task_test"
