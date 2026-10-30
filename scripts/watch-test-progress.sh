#!/bin/bash
# Read-only terminal monitor. It never invokes models or edits experiment files.
set -u
task_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
task_requested=${1:-}
task_run=${1:-"$task_root/research-runs/dev-four-methods"}
task_once=${2:-}
if [[ "$task_requested" == --once ]]; then task_requested=''; task_once=--once; fi
if [[ "$task_once" != "" && "$task_once" != "--once" ]]; then
  printf 'Использование: bash scripts/watch-test-progress.sh ПАПКА [--once]\n' >&2
  exit 2
fi
task_live=0
if [[ -t 1 && "${TERM:-dumb}" != dumb && "$task_once" != --once ]]; then
  task_live=1
else
  # IDE panels and redirected output receive one snapshot, never endless logs.
  task_once=--once
fi
task_frame=''
task_cleanup() {
  if (( task_live )); then
    printf '\033[?25h\033[?1049l'
    if [[ -n "$task_frame" ]]; then printf '%s\n' "$task_frame"; fi
  fi
}
trap task_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if (( task_live )); then printf '\033[?1049h\033[?25l'; fi
task_time() {
  local seconds=$1
  printf '%02d:%02d:%02d' "$((seconds / 3600))" "$(((seconds % 3600) / 60))" "$((seconds % 60))"
}
while :; do
  if [[ -z "$task_requested" ]]; then
    if [[ -f "$task_root/research-runs/test-four-methods.pipeline-status" || -f "$task_root/research-runs/test-four-methods/run.json" ]]; then
      task_run="$task_root/research-runs/test-four-methods"
    else task_run="$task_root/research-runs/dev-four-methods"; fi
  fi
  task_methods=(rules grobid ollama anystyle)
  if [[ -f "$task_run/run.json" ]]; then
    task_selected=()
    for task_candidate in "${task_methods[@]}"; do
      if grep -Eq '^[[:space:]]*"'"$task_candidate"'"[[:space:]]*:' "$task_run/run.json"; then
        task_selected+=("$task_candidate")
      fi
    done
    if (( ${#task_selected[@]} > 0 )); then task_methods=("${task_selected[@]}"); fi
  fi
  task_split=DEV; task_expected=92
  if [[ "$task_run" == *test* ]]; then task_split=TEST; task_expected=408; fi
  if [[ -f "$task_run/run.json" ]]; then
    task_found=$(sed -n 's/^[[:space:]]*"records"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$task_run/run.json" | head -n 1)
    if [[ "$task_found" =~ ^[1-9][0-9]*$ ]]; then task_expected=$task_found; fi
    task_found=$(sed -n 's/^[[:space:]]*"split"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$task_run/run.json" | head -n 1)
    case "$task_found" in dev) task_split=DEV;; test) task_split=TEST;; esac
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
  case "$task_state" in
    WAITING) task_stage='Ожидание запуска';;
    RUNNING) task_stage='1. Разбор ссылок';;
    EVALUATING) task_stage='2. Расчёт precision / recall / F1';;
    FORMATTING) task_stage='3. Оформление по профилям ГОСТ';;
    PREPARING_REVIEW) task_stage='4. Подготовка бланка предметной проверки';;
    COMPLETED) task_stage='Завершено';;
    EXTRACTION_COMPLETED) task_stage='Извлечение завершено';;
    FAILED) task_stage='Ошибка запуска или этапа';;
    CANCELLED) task_stage='Запуск отменён';;
    STOPPED) task_stage='Процесс больше не работает';;
    *) task_stage=$task_state;;
  esac
  task_draw() {
  printf 'ЗАМЕРЫ / %s    %s\n' "$task_split" "$(TZ=Europe/Samara date '+%d.%m.%Y %H:%M:%S')"
  printf 'Этап: %s\n\n' "$task_stage"
  printf 'Метод        Обработано      Готово   ERROR  EMPTY\n'
  task_total=0
  task_active=0
  task_llm_count=0
  task_llm_millis=0
  for task_method in "${task_methods[@]}"; do
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
      anystyle) task_name=AnyStyle ;;
      ollama) task_name=Qwen3-8B; task_llm_count=$task_count; task_llm_millis=$task_millis ;;
    esac
    task_percent=$(awk -v n="$task_count" -v d="$task_expected" 'BEGIN {printf "%.1f",100*n/d}')
    task_mark=' '
    if [[ "$task_state" == RUNNING ]] && (( task_count < task_expected && task_active == 0 )); then task_mark='>'; task_active=1; fi
    printf '%s %-10s %4d / %-4d %6s%% %6d %6d\n' "$task_mark" "$task_name" "$task_count" "$task_expected" "$task_percent" "$task_errors" "$task_empty"
    task_total=$((task_total + task_count))
  done
  printf '\nВсего завершено вызовов: %d / %d\n' "$task_total" "$((${#task_methods[@]} * task_expected))"
  if [[ -f "$task_run.started" ]]; then
    task_started=$(cat "$task_run.started")
    task_elapsed=$(($(date +%s) - task_started))
    printf 'Прошло: '; task_time "$task_elapsed"; printf '\n'
  fi
  if [[ " ${task_methods[*]} " == *" ollama "* ]] && (( task_llm_count < task_expected )); then
    if (( task_llm_count >= 3 )); then
      task_eta=$(awk -v n="$task_llm_count" -v d="$task_expected" -v ms="$task_llm_millis" 'BEGIN {printf "%.0f",(d-n)*ms/(1000*n)}')
      printf 'Остаток Qwen по средней длительности завершённых вызовов: '
      task_time "$task_eta"; printf '\n'
    else
      printf 'Остаток Qwen: оценка появится после первых трёх ответов.\n'
    fi
  fi
  printf '\nERROR — технические ошибки, не ошибки извлечения полей.\n'
  printf 'Процент показывает долю завершённых вызовов, а не долю времени.\n'
  printf '> — выполняемый метод. Обновление каждые 2 с.\n'
  printf 'Ctrl+C закрывает только монитор; замеры продолжат работу.\n'
  printf '\nПапка: %s\n' "$task_run"
  if [[ "$task_state" == "COMPLETED" ]]; then
    if [[ -f "$task_run/quality/report.md" ]]; then printf 'Отчёт: %s/quality/report.md\n' "$task_run"
    else printf 'Отчёт: %s/evaluation/report.md\n' "$task_run"; fi
    if [[ "$task_split" == DEV ]]; then printf 'Далее: анализ DEV и фиксация протокола перед TEST.\n'; fi
  elif [[ "$task_state" == "FAILED" || "$task_state" == "CANCELLED" || "$task_state" == "STOPPED" ]]; then
    printf '\nЗапуск остановлен. Журнал: %s.pipeline.log\n' "$task_run"
  elif [[ "$task_state" == "EXTRACTION_COMPLETED" ]]; then
    printf '\nИзвлечение завершено.\n'
  fi
  }
  task_frame=$(task_draw)
  if (( task_live )); then printf '\033[H%s\033[J' "$task_frame"; else printf '%s\n' "$task_frame"; fi
  case "$task_state" in COMPLETED|FAILED|CANCELLED|STOPPED|EXTRACTION_COMPLETED) break;; esac
  if [[ "$task_once" == "--once" ]]; then break; fi
  sleep 2
done
