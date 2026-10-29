package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import ru.ssau.bibliography.research.ExtractionEvaluator.*;
import ru.ssau.bibliography.research.ResearchDataset.*;

/** Joins archived baseline answers and a later comparator without running any parser. */
public final class CompareRunsCommand {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  private CompareRunsCommand() {}

  public static void run(String[] args) throws IOException {
    Map<String, String> opts = new HashMap<>();
    for (String arg : args) {
      int eq = arg.indexOf('=');
      if (!arg.startsWith("--") || eq < 3 || eq == arg.length() - 1)
        throw new IllegalArgumentException("Ожидается --имя=значение");
      String key = arg.substring(2, eq);
      if (!Set.of("compare-runs", "additional-run", "dataset", "comparison-output").contains(key)
          || opts.putIfAbsent(key, arg.substring(eq + 1)) != null)
        throw new IllegalArgumentException("Неизвестный/повторный параметр: " + key);
    }
    if (!opts.keySet()
        .containsAll(Set.of("compare-runs", "additional-run", "dataset", "comparison-output")))
      throw new IllegalArgumentException(
          "Нужны --compare-runs, --additional-run, --dataset, --comparison-output");
    compare(
        Path.of(opts.get("compare-runs")),
        Path.of(opts.get("additional-run")),
        Path.of(opts.get("dataset")),
        Path.of(opts.get("comparison-output")));
  }

  static void compare(Path baseline, Path additional, Path datasetFile, Path output)
      throws IOException {
    var added = archivedAdditional(additional, datasetFile);
    if (added.split() != Split.TEST || !added.predictions().keySet().equals(Set.of("anystyle")))
      throw new IllegalArgumentException("Дополнение должно содержать полный TEST AnyStyle");
    var data = added.dataset();
    JsonNode old = JSON.readTree(baseline.resolve("run.json").toFile());
    JsonNode oldMetrics = JSON.readTree(baseline.resolve("evaluation/metrics.json").toFile());
    JsonNode audit =
        JSON.readTree(baseline.resolve("evaluation/comparison_manifest.json").toFile());
    Set<String> originalMethods = new TreeSet<>();
    old.path("methods").fieldNames().forEachRemaining(originalMethods::add);
    if (!originalMethods.equals(Set.of("rules", "grobid", "ollama"))
        || !old.path("executionState").asText().equals("COMPLETED")
        || !old.path("split").asText().equals("test")
        || old.path("records").asInt() != added.references().size()
        || !old.path("datasetSha256").asText().equals(data.sha256())
        || !oldMetrics.path("datasetSha256").asText().equals(data.sha256())
        || !oldMetrics.path("normalizationVersion").asText().equals(FieldNormalization.VERSION))
      throw new IllegalArgumentException("Состав/датасет/нормализация исходного TEST отличаются");
    var baselineProtocol = ExperimentProtocol.read(baseline.resolve("protocol"));
    if (!ExperimentSnapshot.sha(baseline.resolve("protocol/protocol.json"))
            .equals(old.path("protocolSha256").asText())
        || !baselineProtocol.path("methods").equals(old.path("methods"))
        || !baselineProtocol.path("implementation").equals(old.path("implementation")))
      throw new IllegalArgumentException("Исходный TEST не связан с архивным протоколом");
    var hashes = audit.path("sourceHashes").fields();
    while (hashes.hasNext()) {
      var e = hashes.next();
      if (!ExperimentSnapshot.sha(baseline.resolve(e.getKey())).equals(e.getValue().asText()))
        throw new IllegalArgumentException("Изменён архивный источник: " + e.getKey());
    }
    if (!ExperimentSnapshot.sha(baseline.resolve("evaluation/records.jsonl"))
        .equals(audit.path("recordsSha256").asText()))
      throw new IllegalArgumentException("Изменено архивное построчное сравнение");
    // Orchestration has been extended; evaluation/normalization/formatter and input semantics must
    // not change.
    var current = ExperimentSnapshot.implementation();
    for (var e : current.entrySet()) {
      String key = e.getKey();
      if (key.startsWith("dependency:")
          || key.matches(
              ".*(?:ExtractionEvaluator|FieldNormalization|ExcelDatasetLoader|ResearchDataset|ReferenceField|SharedCitationFormatter|CitationFormatter)(?:\\$[^/]*)?\\.class"))
        if (!e.getValue().equals(old.path("implementation").path(key).asText()))
          throw new IllegalArgumentException("Изменена общая логика оценки/оформления: " + key);
    }
    Map<String, List<Prediction>> predictions = new LinkedHashMap<>();
    Map<String, Result> results = new LinkedHashMap<>();
    for (String method : List.of("rules", "grobid", "ollama")) {
      var rows = readPredictions(baseline.resolve(method + ".jsonl"), method, added.references());
      Result evaluated = new ExtractionEvaluator().evaluate(added.references(), rows);
      if (!sameJson(evaluated, oldMetrics.path("results").path(method)))
        throw new IllegalArgumentException(
            "Пересчитанные показатели исходного метода изменились: " + method);
      predictions.put(method, rows);
      results.put(method, evaluated);
    }
    predictions.putAll(added.predictions());
    results.put(
        "anystyle",
        new ExtractionEvaluator()
            .evaluate(added.references(), added.predictions().get("anystyle")));
    JsonNode previousFormat =
        JSON.readTree(baseline.resolve("formatting/formatting.json").toFile());
    JsonNode addedFormat = JSON.readTree(additional.resolve("formatting/formatting.json").toFile());
    if (!previousFormat.path("contexts").equals(addedFormat.path("contexts"))
        || !previousFormat.path("contextSha256").equals(addedFormat.path("contextSha256"))
        || !previousFormat.path("formatterVersion").equals(addedFormat.path("formatterVersion")))
      throw new IllegalArgumentException(
          "Контекст/версия форматирования отличаются между прогонами");
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.createDirectory(output);
    var manifest = new LinkedHashMap<String, Object>();
    manifest.put("schemaVersion", "composite-comparison-v1");
    manifest.put("createdAt", Instant.now().toString());
    manifest.put("datasetSha256", data.sha256());
    manifest.put("records", added.references().size());
    manifest.put("split", "test");
    manifest.put(
        "scope",
        "Later-added AnyStyle comparator; baseline predictions reused byte-for-byte; not a single"
            + " preregistered four-method run");
    manifest.put("baselineRun", baseline.toAbsolutePath().normalize().toString());
    manifest.put("additionalRun", additional.toAbsolutePath().normalize().toString());
    manifest.put("baselineProtocolSha256", old.path("protocolSha256").asText());
    manifest.put("additionalProtocolSha256", added.manifest().path("protocolSha256").asText());
    var methods = JSON.createObjectNode();
    methods.setAll((com.fasterxml.jackson.databind.node.ObjectNode) old.path("methods"));
    methods.setAll(
        (com.fasterxml.jackson.databind.node.ObjectNode) added.manifest().path("methods"));
    manifest.put("methods", methods);
    JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("run.json").toFile(), manifest);
    for (String method : predictions.keySet())
      Files.copy(
          (method.equals("anystyle") ? additional : baseline).resolve(method + ".jsonl"),
          output.resolve(method + ".jsonl"));
    Path evaluation = output.resolve("evaluation");
    Files.createDirectory(evaluation);
    var metrics = new LinkedHashMap<String, Object>();
    metrics.put("createdAt", Instant.now().toString());
    metrics.put("datasetSha256", data.sha256());
    metrics.put("split", "test");
    metrics.put("records", added.references().size());
    metrics.put("normalizationVersion", FieldNormalization.VERSION);
    metrics.put("results", results);
    metrics.put("scope", manifest.get("scope"));
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(evaluation.resolve("metrics.json").toFile(), metrics);
    RecordComparisonWriter.write(
        new SavedResearchRun(
            data, Split.TEST, added.references(), predictions, JSON.valueToTree(manifest)),
        output,
        evaluation);
    List<List<String>> fieldRows = new ArrayList<>();
    fieldRows.add(List.of("method", "field", "TP", "FP", "FN", "precision", "recall", "F1"));
    List<List<String>> summaryRows = new ArrayList<>();
    summaryRows.add(
        List.of(
            "method",
            "micro_precision",
            "micro_recall",
            "micro_F1",
            "fully_correct",
            "eligible",
            "rate"));
    results.forEach(
        (method, r) -> {
          var s = r.summary();
          summaryRows.add(
              List.of(
                  method,
                  number(s.precision()),
                  number(s.recall()),
                  number(s.microF1()),
                  "" + s.fullyCorrect(),
                  "" + s.fullyCorrectEligible(),
                  number(s.fullyCorrectRate())));
          for (var e : r.fields().entrySet()) {
            var v = e.getValue();
            fieldRows.add(
                List.of(
                    method,
                    e.getKey().name(),
                    "" + v.tp(),
                    "" + v.fp(),
                    "" + v.fn(),
                    number(v.precision()),
                    number(v.recall()),
                    number(v.f1())));
          }
        });
    csv(evaluation.resolve("field_metrics.csv"), fieldRows);
    csv(evaluation.resolve("comparison.csv"), summaryRows);
    Files.writeString(
        evaluation.resolve("report.md"),
        EvaluateRunCommand.markdown(results, Split.TEST, added.references().size())
            + "\n"
            + "AnyStyle добавлен после первого TEST. Ответы трёх исходных методов не"
            + " перезапускались и не изменялись. Оформление по ГОСТ и неподтверждённые реквизиты"
            + " LLM здесь не оценены.\n");
    Path formatting = output.resolve("formatting");
    Files.createDirectory(formatting);
    var formattedMeta =
        ((com.fasterxml.jackson.databind.node.ObjectNode) previousFormat).deepCopy();
    var statuses = (com.fasterxml.jackson.databind.node.ObjectNode) formattedMeta.path("statuses");
    statuses.set("anystyle", addedFormat.path("statuses").path("anystyle"));
    formattedMeta.put("sourceRun", output.toAbsolutePath().normalize().toString());
    formattedMeta.put("scope", manifest.get("scope").toString());
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(formatting.resolve("formatting.json").toFile(), formattedMeta);
    for (String method : predictions.keySet())
      Files.copy(
          (method.equals("anystyle") ? additional : baseline)
              .resolve("formatting/" + method + ".jsonl"),
          formatting.resolve(method + ".jsonl"));
    System.out.println(
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "output",
                    output.toAbsolutePath().normalize().toString(),
                    "summaries",
                    results.entrySet().stream()
                        .collect(
                            java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, e -> e.getValue().summary())))));
  }

  // Comparison/reporting may evolve after TEST. Verify the archived run and every other
  // fingerprint, while allowing only this postprocessing class to differ. Ordinary
  // --evaluate-run continues to require the original executable without this exception.
  private static SavedResearchRun archivedAdditional(Path run, Path datasetFile)
      throws IOException {
    var dataset = new ExcelDatasetLoader().load(datasetFile);
    JsonNode manifest = JSON.readTree(run.resolve("run.json").toFile());
    JsonNode protocol = ExperimentProtocol.read(run.resolve("protocol"));
    if (!manifest.path("split").asText().equals("test")
        || !manifest.path("executionState").asText().equals("COMPLETED")
        || !manifest.path("datasetSha256").asText().equals(dataset.sha256())
        || manifest.path("records").asInt() != dataset.references(Split.TEST).size()
        || manifest.path("splitSize").asInt() != dataset.references(Split.TEST).size()
        || manifest.path("methods").size() != 1
        || !manifest.path("methods").has("anystyle")
        || !manifest
            .path("protocolSha256")
            .asText()
            .equals(ExperimentSnapshot.sha(run.resolve("protocol/protocol.json")))
        || !manifest.path("methods").equals(protocol.path("methods"))
        || !manifest.path("implementation").equals(protocol.path("implementation")))
      throw new IllegalArgumentException("Некорректный архив дополнительного TEST");
    for (var e : ExperimentSnapshot.implementation().entrySet()) {
      if (!e.getKey().contains("/CompareRunsCommand")
          && !e.getValue().equals(manifest.path("implementation").path(e.getKey()).asText()))
        throw new IllegalArgumentException(
            "Изменена реализация дополнительного метода: " + e.getKey());
    }
    JsonNode audit = JSON.readTree(run.resolve("evaluation/comparison_manifest.json").toFile());
    var hashes = audit.path("sourceHashes").fields();
    while (hashes.hasNext()) {
      var e = hashes.next();
      if (!ExperimentSnapshot.sha(run.resolve(e.getKey())).equals(e.getValue().asText()))
        throw new IllegalArgumentException("Изменён дополнительный архив: " + e.getKey());
    }
    if (!ExperimentSnapshot.sha(run.resolve("evaluation/records.jsonl"))
        .equals(audit.path("recordsSha256").asText()))
      throw new IllegalArgumentException("Изменено дополнительное построчное сравнение");
    var refs = dataset.references(Split.TEST);
    var predictions = readPredictions(run.resolve("anystyle.jsonl"), "anystyle", refs);
    var result = new ExtractionEvaluator().evaluate(refs, predictions);
    JsonNode metrics = JSON.readTree(run.resolve("evaluation/metrics.json").toFile());
    if (!metrics.path("datasetSha256").asText().equals(dataset.sha256())
        || !metrics.path("normalizationVersion").asText().equals(FieldNormalization.VERSION)
        || !sameJson(result, metrics.path("results").path("anystyle")))
      throw new IllegalArgumentException(
          "Показатели дополнительного метода не совпадают с архивом");
    return new SavedResearchRun(
        dataset, Split.TEST, refs, Map.of("anystyle", predictions), manifest);
  }

  static boolean sameJson(Object result, JsonNode saved) throws IOException {
    // Normalize Jackson's in-memory LongNode/IntNode distinction through JSON, not numbers.
    return JSON.readTree(JSON.writeValueAsBytes(result)).equals(saved);
  }

  private static List<Prediction> readPredictions(
      Path file, String method, List<AnnotatedReference> refs) throws IOException {
    Map<String, String> originals = new HashMap<>();
    refs.forEach(r -> originals.put(r.input().id(), r.input().rawCitation()));
    Set<String> ids = new HashSet<>();
    List<Prediction> rows = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      JsonNode n = JSON.readTree(line);
      String id = n.path("id").asText(),
          raw = n.path("rawCitation").asText(),
          status = n.path("status").asText();
      if (!n.path("method").asText().equals(method)
          || !ids.add(id)
          || !Objects.equals(originals.get(id), raw)
          || !Set.of("SUCCESS", "EMPTY", "ERROR").contains(status)
          || !n.path("fields").isObject())
        throw new IllegalArgumentException("Повреждён архивный ответ: " + id);
      var fields = new EnumMap<ReferenceField, String>(ReferenceField.class);
      var values = n.path("fields").fields();
      while (values.hasNext()) {
        var e = values.next();
        if (!e.getValue().isTextual()) throw new IllegalArgumentException("Не строковое поле");
        fields.put(ReferenceField.valueOf(e.getKey()), e.getValue().asText());
      }
      if (!status.equals("SUCCESS") && !fields.isEmpty())
        throw new IllegalArgumentException("Поля при ошибке");
      rows.add(new Prediction(id, raw, status, fields));
    }
    if (!ids.equals(originals.keySet()))
      throw new IllegalArgumentException("Различаются ID сравнения");
    return rows;
  }

  private static String number(Double value) {
    return value == null ? "" : String.format(Locale.ROOT, "%.6f", value);
  }

  private static void csv(Path file, List<List<String>> rows) throws IOException {
    try (var w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (var row : rows) {
        w.write(
            row.stream()
                .map(s -> "\"" + s.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",")));
        w.newLine();
      }
    }
  }
}
