#!/bin/bash
# Full four-method DEV only. Freeze remains an explicit step after reviewing DEV.
set -euo pipefail
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if (( $# > 1 )); then
  printf 'Использование: bash scripts/run-dev.sh [НОВАЯ_ПАПКА_DEV]\n' >&2
  exit 2
fi
task_run=${1:-"$task_root/research-runs/dev-four-methods"}
if [[ "$task_run" != /* ]]; then task_run="$PWD/$task_run"; fi
task_jar="$task_root/backend/target/bibliography-1.0.0.jar"
task_dataset="$task_root/Dataset.xlsx"
if [[ -e "$task_run" || -e "$task_run.pipeline-status" ]]; then
  printf 'Папка или состояние запуска уже существуют: %s\n' "$task_run" >&2
  exit 2
fi
if [[ ! -f "$task_jar" ]]; then
  printf 'Сначала соберите приложение: cd backend && ./mvnw verify\n' >&2
  exit 2
fi
mkdir -p "$(dirname "$task_run")"
cd "$task_root/backend"
task_child=''
task_sleep_guard=''
task_set_state() {
  printf '%s\n' "$1" > "$task_run.pipeline-status.tmp.$$"
  mv "$task_run.pipeline-status.tmp.$$" "$task_run.pipeline-status"
}
task_cleanup() {
  local task_exit=$?
  trap - EXIT
  if [[ -n "$task_child" ]]; then kill -TERM "$task_child" 2>/dev/null || true; fi
  if [[ -n "$task_sleep_guard" ]]; then kill "$task_sleep_guard" 2>/dev/null || true; fi
  if (( task_exit != 0 )); then
    if [[ "$(cat "$task_run.pipeline-status" 2>/dev/null || true)" != CANCELLED ]]; then task_set_state FAILED; fi
  fi
  rm -f "$task_run.java.pid"
  exit "$task_exit"
}
trap task_cleanup EXIT
trap 'task_set_state CANCELLED; exit 130' INT
trap 'task_set_state CANCELLED; exit 143' TERM
printf '%s\n' "$$" > "$task_run.pipeline.pid"
date +%s > "$task_run.started"
task_set_state RUNNING
if [[ -x /usr/bin/caffeinate ]]; then
  /usr/bin/caffeinate -is -w "$$" >/dev/null 2>&1 &
  task_sleep_guard=$!
fi
task_java() {
  java -jar "$task_jar" "$@" &
  task_child=$!
  printf '%s\n' "$task_child" > "$task_run.java.pid"
  local task_code=0
  wait "$task_child" || task_code=$?
  task_child=''
  return "$task_code"
}
printf 'Начало DEV: %s\nПапка: %s\n' "$(TZ=Europe/Samara date '+%d.%m.%Y %H:%M:%S')" "$task_run"
task_parse_code=0
task_java "--parse-dataset=$task_dataset" --method=all --split=dev "--output=$task_run" || task_parse_code=$?
if (( task_parse_code != 0 && task_parse_code != 3 )); then exit "$task_parse_code"; fi
test -f "$task_run/summary.json"
task_set_state EVALUATING
task_java "--evaluate-run=$task_run" "--dataset=$task_dataset"
task_set_state FORMATTING
task_java "--format-run=$task_run" "--dataset=$task_dataset"
task_set_state COMPLETED
printf 'DEV завершён. Изучите ошибки, затем зафиксируйте протокол отдельной командой.\n'
