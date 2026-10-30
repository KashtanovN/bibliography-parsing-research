#!/usr/bin/env python3
"""Collect saved automatic metrics and per-call timings; never infer review decisions."""
import collections
import hashlib
import json
from pathlib import Path
import statistics
import sys


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def build(run):
    meta = load(run / 'run.json')
    metrics = load(run / 'evaluation/metrics.json')
    manifest = load(run / 'evaluation/comparison_manifest.json')
    if meta['executionState'] != 'COMPLETED' or meta['split'] != 'test':
        raise ValueError('Нужен завершённый TEST')
    if metrics['datasetSha256'] != meta['datasetSha256'] or metrics['records'] != meta['records']:
        raise ValueError('Версия данных или размер оценки не совпадают')
    methods = ['rules', 'grobid', 'ollama', 'anystyle']
    if set(meta['methods']) != set(methods) or set(metrics['results']) != set(methods):
        raise ValueError('Нужны четыре метода общего опыта')
    for name, expected in manifest['sourceHashes'].items():
        target = (run / name).resolve()
        if not target.is_relative_to(run.resolve()) or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
            raise ValueError('Контрольная сумма ответа не совпала: ' + name)
    if hashlib.sha256((run / 'evaluation/records.jsonl').read_bytes()).hexdigest() != manifest['recordsSha256']:
        raise ValueError('Изменён журнал сравнений')
    times = {}
    common_ids = None
    for method in methods:
        content = (run / (method + '.jsonl')).read_bytes()
        if not content.endswith(b'\n'):
            raise ValueError('Незавершённый файл ответов: ' + method)
        rows = [json.loads(line) for line in content.decode('utf-8').splitlines()]
        ids = [r['id'] for r in rows]
        if len(rows) != meta['records'] or len(set(ids)) != len(ids) or (common_ids is not None and ids != common_ids):
            raise ValueError('Неполный или несовпадающий состав ID: ' + method)
        if any(r['method'] != method for r in rows):
            raise ValueError('Неверный метод в файле: ' + method)
        common_ids = ids
        durations = [r['durationMillis'] for r in rows]
        if any(type(x) is not int or x < 0 for x in durations):
            raise ValueError('Некорректная длительность: ' + method)
        times[method] = {
            'calls': len(rows), 'statuses': dict(collections.Counter(r['status'] for r in rows)),
            'totalSeconds': sum(durations) / 1000,
            'meanSeconds': statistics.mean(durations) / 1000,
            'medianSeconds': statistics.median(durations) / 1000,
            'zeroMillisCalls': durations.count(0),
        }
    return {'datasetSha256': meta['datasetSha256'], 'split': 'test', 'recordsPerMethod': meta['records'],
            'extractionMetrics': metrics, 'callDurations': times,
            'subjectMatterReviewRequired': True,
            'formattingCorrectRate': None, 'confirmedUnsupportedFacts': None,
            'timingScope': 'Сохранённая длительность отдельного вызова в миллисекундах; подготовка среды и загрузка модели вне этой суммы.'}


def report(data):
    def number(value):
        return '—' if value is None else f'{value:.6f}'
    lines = ['# Автоматические показатели TEST', '',
             f"Датасет SHA-256: `{data['datasetSha256']}`. Записей каждого метода: {data['recordsPerMethod']}.", '',
             '| Метод | Precision | Recall | Micro F1 | Macro F1 | Полное совпадение | Лишние | Конфликты |',
             '|---|---:|---:|---:|---:|---:|---:|---:|']
    results = data['extractionMetrics']['results']
    for method in data['callDurations']:
        s = results[method]['summary']
        lines.append(f"| {method} | {number(s['precision'])} | {number(s['recall'])} | {number(s['microF1'])} | {number(s['macroF1'])} | {s['fullyCorrect']}/{s['fullyCorrectEligible']} ({number(s['fullyCorrectRate'])}) | {s['extraFilledFields']} | {s['conflictingFields']} |")
    lines += ['', 'Доля полного совпадения в скобках дана как число от 0 до 1.', '',
              '| Поле | rules F1 | grobid F1 | ollama F1 | anystyle F1 |', '|---|---:|---:|---:|---:|']
    for field in results['rules']['fields']:
        lines.append('| ' + field + ' | ' + ' | '.join(number(results[m]['fields'][field]['f1']) for m in data['callDurations']) + ' |')
    lines += ['', '| Метод | Вызовы | Сумма, с | Среднее, с | Медиана, с | Нулевых мс |', '|---|---:|---:|---:|---:|---:|']
    for method, t in data['callDurations'].items():
        lines.append(f"| {method} | {t['calls']} | {number(t['totalSeconds'])} | {number(t['meanSeconds'])} | {number(t['medianSeconds'])} | {t['zeroMillisCalls']} |")
    lines += ['', data['timingScope'], '',
              'Корректность оформления и подтверждённые неподдержанные добавления требуют заполнения `../quality/review.json` и пересчёта `--quality-run`. Они не равны нулю до проверки.',
              'Статус FORMATTED не является экспертным решением. Согласие разметчиков рассчитывается отдельной процедурой.', '']
    return '\n'.join(lines)


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Использование: python3 scripts/summarize-run.py ПАПКА_TEST')
    run = Path(sys.argv[1]).resolve()
    data = build(run)
    output = run / 'article'
    output.mkdir(exist_ok=False)
    (output / 'automatic.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (output / 'report.md').write_text(report(data), encoding='utf-8')
    print(output / 'report.md')


if __name__ == '__main__':
    main()
