#!/bin/bash
# Read-only terminal monitor. It never invokes models or edits experiment files.
set -u
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
task_run=${1:-"$task_root/research-runs/final-test-20261029"}
task_once=${2:-}
if [[ "$task_once" != "" && "$task_once" != "--once" ]]; then
  printf 'Использование: bash scripts/watch-test-progress.sh ПАПКА [--once]\n' >&2
  exit 2
fi
task_time() {
  local seconds=$1
  printf '%02d:%02d:%02d' "$((seconds / 3600))" "$(((seconds % 3600) / 60))" "$((seconds % 60))"
}
while :; do
  if [[ -t 1 && "$task_once" == "" ]]; then printf '\033[H\033[2J'; fi
  task_expected=408
  if [[ -f "$task_run/run.json" ]]; then
    task_found=$(sed -n 's/^[[:space:]]*"records"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$task_run/run.json" | head -n 1)
    if [[ "$task_found" =~ ^[1-9][0-9]*$ ]]; then task_expected=$task_found; fi
  fi
  task_state=WAITING
  if [[ -f "$task_run.pipeline-status" ]]; then
    task_state=$(cat "$task_run.pipeline-status")
  elif [[ -f "$task_run/summary.json" ]]; then
    task_state=EXTRACTION_COMPLETED
  fi
  if [[ "$task_state" != "COMPLETED" && "$task_state" != "FAILED" && "$task_state" != "CANCELLED" && -f "$task_run.pipeline.pid" ]]; then
    task_pid=$(cat "$task_run.pipeline.pid")
    if [[ "$task_pid" =~ ^[0-9]+$ ]] && ! kill -0 "$task_pid" 2>/dev/null; then task_state=STOPPED; fi
  fi
  printf 'Прогресс эксперимента    %s\n' "$(TZ=Europe/Samara date '+%d.%m.%Y %H:%M:%S')"
  printf 'Папка: %s\nСостояние: %s\n\n' "$task_run" "$task_state"
  printf 'Метод        Обработано      Готово   ERROR  EMPTY\n'
  task_total=0
  task_llm_count=0
  task_llm_millis=0
  for task_method in rules grobid ollama; do
    task_count=0; task_errors=0; task_empty=0; task_millis=0
    if [[ -f "$task_run/$task_method.jsonl" ]]; then
      task_lines=$(wc -l < "$task_run/$task_method.jsonl")
      # Ignore an unfinished trailing line while Java is writing it.
      read -r task_count task_errors task_empty task_millis < <(awk -v limit="$task_lines" '
        NR <= limit {
          count++
          if ($0 ~ /"status"[[:space:]]*:[[:space:]]*"ERROR"/) errors++
          if ($0 ~ /"status"[[:space:]]*:[[:space:]]*"EMPTY"/) empty++
          if (match($0, /"durationMillis"[[:space:]]*:[[:space:]]*[0-9]+/)) {
            value=substr($0,RSTART,RLENGTH); gsub(/[^0-9]/,"",value); millis+=value
          }
        }
        END { printf "%d %d %d %.0f\n", count, errors, empty, millis }
      ' "$task_run/$task_method.jsonl")
    fi
    case "$task_method" in
      rules) task_name=Rules ;;
      grobid) task_name=GROBID ;;
      ollama) task_name=Qwen3-8B; task_llm_count=$task_count; task_llm_millis=$task_millis ;;
    esac
    task_percent=$(awk -v n="$task_count" -v d="$task_expected" 'BEGIN {printf "%.1f",100*n/d}')
    printf '%-12s %4d / %-4d %6s%% %6d %6d\n' "$task_name" "$task_count" "$task_expected" "$task_percent" "$task_errors" "$task_empty"
    task_total=$((task_total + task_count))
  done
  printf '\nВсего завершено вызовов: %d / %d\n' "$task_total" "$((3 * task_expected))"
  if [[ -f "$task_run.started" ]]; then
    task_started=$(cat "$task_run.started")
    task_elapsed=$(($(date +%s) - task_started))
    printf 'Прошло: '; task_time "$task_elapsed"; printf '\n'
  fi
  if (( task_llm_count < task_expected )); then
    if (( task_llm_count > 0 )); then
      task_eta=$(awk -v n="$task_llm_count" -v d="$task_expected" -v ms="$task_llm_millis" 'BEGIN {printf "%.0f",(d-n)*ms/(1000*n)}')
      printf 'Остаток Qwen по средней длительности завершённых вызовов: '
    else
      task_eta=$(((task_expected - task_llm_count) * 33))
      printf 'Ориентир для Qwen по DEV: '
    fi
    task_time "$task_eta"; printf '\n'
  fi
  printf '\nERROR — технические ошибки, не ошибки извлечения полей.\n'
  printf 'Процент показывает долю завершённых вызовов, а не долю времени.\n'
  if [[ "$task_state" == "COMPLETED" ]]; then
    printf '\nГотово. Общий отчёт: %s/quality/report.md\n' "$task_run"
    break
  elif [[ "$task_state" == "FAILED" || "$task_state" == "CANCELLED" || "$task_state" == "STOPPED" ]]; then
    printf '\nЗапуск остановлен. Журнал: %s.pipeline.log\n' "$task_run"
    break
  elif [[ "$task_state" == "EXTRACTION_COMPLETED" ]]; then
    printf '\nИзвлечение завершено.\n'
    break
  fi
  if [[ "$task_once" == "--once" ]]; then break; fi
  printf '\nCtrl+C закрывает только монитор. Фоновый TEST продолжит работу.\n'
  sleep 5
done
