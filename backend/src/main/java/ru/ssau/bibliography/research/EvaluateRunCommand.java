package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import ru.ssau.bibliography.research.ExtractionEvaluator.*;
import ru.ssau.bibliography.research.ResearchDataset.*;

public final class EvaluateRunCommand {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private EvaluateRunCommand() {}

  public static void run(String[] args) throws IOException {
    Map<String, String> options = new HashMap<>();
    for (String arg : args) {
      int sep = arg.indexOf('=');
      if (!arg.startsWith("--") || sep < 3 || sep == arg.length() - 1)
        throw new IllegalArgumentException("Ожидается --имя=значение");
      String key = arg.substring(2, sep);
      if (!Set.of("evaluate-run", "dataset", "evaluation-output").contains(key)
          || options.putIfAbsent(key, arg.substring(sep + 1)) != null)
        throw new IllegalArgumentException("Неизвестный или повторный параметр: " + key);
    }
    if (!options.containsKey("evaluate-run") || !options.containsKey("dataset"))
      throw new IllegalArgumentException("Нужны --evaluate-run и --dataset");
    Path run = Path.of(options.get("evaluate-run"));
    SavedResearchRun saved = SavedResearchRun.read(run, Path.of(options.get("dataset")));
    ResearchDataset dataset = saved.dataset();
    Split split = saved.split();
    int count = saved.references().size();
    Map<String, Result> results = new LinkedHashMap<>();
    saved
        .predictions()
        .forEach(
            (method, predictions) ->
                results.put(
                    method, new ExtractionEvaluator().evaluate(saved.references(), predictions)));
    Path output =
        Path.of(options.getOrDefault("evaluation-output", run.resolve("evaluation").toString()));
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.createDirectory(output);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("createdAt", Instant.now().toString());
    report.put("datasetSha256", dataset.sha256());
    report.put("split", split.name().toLowerCase(Locale.ROOT));
    report.put("records", count);
    report.put("normalizationVersion", FieldNormalization.VERSION);
    report.put("results", results);
    report.put(
        "scope",
        "Extraction only, not GOST formatting correctness. EXTRA fields are candidates for manual"
            + " review, not automatically proven hallucinations.");
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("metrics.json").toFile(), report);
    RecordComparisonWriter.write(saved, run, output);
    writeCsv(output.resolve("field_metrics.csv"), fieldRows(results));
    writeCsv(output.resolve("comparison.csv"), comparisonRows(results));
    List<List<String>> differences = new ArrayList<>(), additions = new ArrayList<>();
    var header =
        List.of(
            "method",
            "id",
            "field",
            "kind",
            "expected",
            "predicted",
            "lexical_presence",
            "raw_citation",
            "review_decision",
            "review_comment");
    differences.add(header);
    additions.add(header);
    results.forEach(
        (method, result) ->
            result
                .differences()
                .forEach(
                    d -> {
                      var row =
                          List.of(
                              method,
                              d.id(),
                              d.field().name(),
                              d.kind(),
                              Objects.toString(d.expected(), ""),
                              Objects.toString(d.predicted(), ""),
                              d.lexicalPresence(),
                              d.rawCitation(),
                              "",
                              "");
                      differences.add(row);
                      if (method.equals("ollama") && !d.kind().equals("MISSING"))
                        additions.add(row);
                    }));
    writeCsv(output.resolve("differences.csv"), differences);
    writeCsv(output.resolve("llm_additions_review.csv"), additions);
    Files.writeString(
        output.resolve("report.md"), markdown(results, split, count), StandardCharsets.UTF_8);
    System.out.println(
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "output",
                    output.toAbsolutePath().toString(),
                    "split",
                    split.name(),
                    "records",
                    count,
                    "summaries",
                    results.entrySet().stream()
                        .collect(
                            java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, e -> e.getValue().summary())))));
  }

  private static List<List<String>> fieldRows(Map<String, Result> results) {
    List<List<String>> rows = new ArrayList<>();
    rows.add(
        List.of(
            "method",
            "field",
            "TP",
            "FP",
            "FN",
            "excluded_ambiguous",
            "compared_nonempty_pairs",
            "precision",
            "recall",
            "F1"));
    results.forEach(
        (method, result) ->
            result
                .fields()
                .forEach(
                    (field, m) ->
                        rows.add(
                            List.of(
                                method,
                                field.name(),
                                "" + m.tp(),
                                "" + m.fp(),
                                "" + m.fn(),
                                "" + m.excludedAmbiguous(),
                                "" + m.comparedNonEmptyPairs(),
                                number(m.precision()),
                                number(m.recall()),
                                number(m.f1())))));
    return rows;
  }

  private static List<List<String>> comparisonRows(Map<String, Result> results) {
    List<List<String>> rows = new ArrayList<>();
    rows.add(
        List.of(
            "method",
            "records",
            "errors",
            "micro_precision",
            "micro_recall",
            "micro_F1",
            "macro_F1",
            "fully_correct_extraction",
            "eligible_records",
            "fully_correct_rate",
            "extra_filled_fields",
            "conflicting_fields"));
    results.forEach(
        (method, result) -> {
          Summary s = result.summary();
          rows.add(
              List.of(
                  method,
                  "" + s.records(),
                  "" + s.errors(),
                  number(s.precision()),
                  number(s.recall()),
                  number(s.microF1()),
                  number(s.macroF1()),
                  "" + s.fullyCorrect(),
                  "" + s.fullyCorrectEligible(),
                  number(s.fullyCorrectRate()),
                  "" + s.extraFilledFields(),
                  "" + s.conflictingFields()));
        });
    return rows;
  }

  static String markdown(Map<String, Result> results, Split split, int records) {
    StringBuilder text =
        new StringBuilder(
            "# Проверка извлечения полей\n\nЧасть: **"
                + split
                + "**, записей: **"
                + records
                + "**. ");
    text.append(
        split == Split.DEV
            ? "Это диагностический результат dev, не итоговая оценка для статьи.\n\n"
            : "Интерпретировать как итоговый результат можно после фиксации методов и"
                + " протокола.\n\n");
    text.append(
        "Версия нормализации: `"
            + FieldNormalization.VERSION
            + "`. Оценка оформления по ГОСТ не выполнялась. Ошибки сервисов включены в оценку как"
            + " отсутствие предсказаний. Неоднозначные пары исключены по полям; записи с ними"
            + " исключены из доли полностью правильного разбора. Совместно пустые поля не дают"
            + " TP.\n\n");
    text.append(
        "| Метод | Micro F1 | Полностью правильный разбор | Лишние заполненные поля | Ошибки"
            + " сервиса |\n"
            + "|---|---:|---:|---:|---:|\n");
    results.forEach(
        (method, result) -> {
          var s = result.summary();
          text.append("| ")
              .append(method)
              .append(" | ")
              .append(number(s.microF1()))
              .append(" | ")
              .append(s.fullyCorrect())
              .append(" / ")
              .append(s.fullyCorrectEligible())
              .append(" | ")
              .append(s.extraFilledFields())
              .append(" | ")
              .append(s.errors())
              .append(" |\n");
        });
    text.append("\n## F1 по полям\n\n| Поле |");
    results.keySet().forEach(k -> text.append(' ').append(k).append(" |"));
    text.append("\n|---|");
    results.forEach((k, v) -> text.append("---:|"));
    text.append('\n');
    for (ReferenceField field : ReferenceField.values()) {
      text.append("| ").append(field.header()).append(" |");
      results.forEach(
          (k, v) -> text.append(' ').append(number(v.fields().get(field).f1())).append(" |"));
      text.append('\n');
    }
    text.append(
        "\n"
            + "Лишнее заполненное поле — непустое предсказание при пустом эталоне. Это не"
            + " доказанная выдумка: возможны ошибка эталона или неверно назначенная роль. Проверить"
            + " исходную строку и заполнить `review_decision`/`review_comment` в"
            + " `llm_additions_review.csv`; там сохранены как лишние, так и конфликтующие"
            + " заполнения LLM. Лексический поиск — вспомогательный признак. Для полного"
            + " учёта, включая совпавшие с ошибочным эталоном значения, и суммирования"
            + " решений используется команда `--quality-run` (docs/QUALITY_REVIEW.md).\n");
    return text.toString();
  }

  private static String number(Double value) {
    return value == null ? "" : String.format(Locale.ROOT, "%.6f", value);
  }

  private static void writeCsv(Path path, List<List<String>> rows) throws IOException {
    try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      for (var row : rows) {
        writer.write(
            row.stream()
                .map(s -> "\"" + s.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",")));
        writer.newLine();
      }
    }
  }
}
