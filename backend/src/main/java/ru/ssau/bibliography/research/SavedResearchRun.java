package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import ru.ssau.bibliography.research.ExtractionEvaluator.Prediction;
import ru.ssau.bibliography.research.ResearchDataset.*;

/** Validates prediction provenance for both extraction evaluation and shared formatting. */
public record SavedResearchRun(
    ResearchDataset dataset,
    Split split,
    List<AnnotatedReference> references,
    Map<String, List<Prediction>> predictions,
    JsonNode manifest) {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  public static SavedResearchRun read(Path run, Path file) throws IOException {
    ResearchDataset dataset = new ExcelDatasetLoader().load(file);
    JsonNode manifest = JSON.readTree(run.resolve("run.json").toFile());
    if (manifest == null || !manifest.isObject())
      throw new IllegalArgumentException("Некорректный run.json");
    if (!dataset.sha256().equals(manifest.path("datasetSha256").asText()))
      throw new IllegalArgumentException("SHA-256 датасета не совпадает с версией прогона");
    Split split;
    try {
      split = Split.valueOf(manifest.path("split").asText().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Нужен прогон датасета dev или test");
    }
    int count = manifest.path("records").asInt(-1);
    List<AnnotatedReference> all = dataset.references(split);
    if (count < 1 || count > all.size() || manifest.path("splitSize").asInt() != all.size())
      throw new IllegalArgumentException("Некорректный размер выборки в run.json");
    List<AnnotatedReference> references = all.subList(0, count);
    Map<String, String> originals = new HashMap<>();
    references.forEach(r -> originals.put(r.input().id(), r.input().rawCitation()));
    JsonNode methods = manifest.path("methods");
    if (!methods.isObject() || methods.isEmpty())
      throw new IllegalArgumentException("Нет методов в run.json");
    if (manifest.has("schemaVersion")
        && (!"COMPLETED".equals(manifest.path("executionState").asText())
            || !Files.isRegularFile(run.resolve("summary.json"))))
      throw new IllegalArgumentException("Прогон не завершён");
    if (split == Split.TEST) {
      var protocol = ExperimentProtocol.read(run.resolve("protocol"));
      if (!ExperimentSnapshot.sha(run.resolve("protocol/protocol.json"))
          .equals(manifest.path("protocolSha256").asText()))
        throw new IllegalArgumentException("TEST не связан с сохранённым протоколом");
      ExperimentProtocol.validate(
          protocol, dataset.sha256(), methods, count, manifest.path("timeoutSeconds").asInt(-1));
      if (!JSON.valueToTree(ExperimentSnapshot.implementation())
          .equals(manifest.get("implementation")))
        throw new IllegalArgumentException("Реализация TEST отличается от оценщика");
    }
    Map<String, List<Prediction>> predictions = new LinkedHashMap<>();
    var names = methods.fieldNames();
    while (names.hasNext()) {
      String method = names.next();
      if (!Set.of("rules", "grobid", "ollama", "anystyle").contains(method))
        throw new IllegalArgumentException("Неизвестный метод: " + method);
      List<Prediction> rows = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      try (var lines =
          Files.newBufferedReader(run.resolve(method + ".jsonl"), StandardCharsets.UTF_8)) {
        String line;
        int number = 0;
        while ((line = lines.readLine()) != null) {
          number++;
          if (line.isBlank())
            throw new IllegalArgumentException("Пустая строка в ответах " + method + ":" + number);
          JsonNode value = JSON.readTree(line);
          if (value == null
              || !method.equals(value.path("method").asText())
              || !value.path("id").isTextual()
              || !value.path("rawCitation").isTextual()
              || !value.path("status").isTextual()
              || !value.path("fields").isObject())
            throw new IllegalArgumentException("Некорректная запись " + method + ":" + number);
          String id = value.get("id").asText(),
              raw = value.get("rawCitation").asText(),
              status = value.get("status").asText();
          if (!ids.add(id)) throw new IllegalArgumentException("Повтор ID в ответах: " + id);
          if (!raw.equals(originals.get(id)))
            throw new IllegalArgumentException("Подменена исходная строка или ID: " + id);
          if (!Set.of("SUCCESS", "EMPTY", "ERROR").contains(status))
            throw new IllegalArgumentException("Неизвестный статус: " + status);
          var fields = new EnumMap<ReferenceField, String>(ReferenceField.class);
          var entries = value.get("fields").fields();
          while (entries.hasNext()) {
            var entry = entries.next();
            ReferenceField field;
            try {
              field = ReferenceField.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
              throw new IllegalArgumentException("Неизвестное поле ответа: " + entry.getKey());
            }
            if (!entry.getValue().isTextual())
              throw new IllegalArgumentException("Поле ответа должно быть строкой: " + field);
            if (!entry.getValue().asText().isBlank()) fields.put(field, entry.getValue().asText());
          }
          if (!status.equals("SUCCESS") && !fields.isEmpty())
            throw new IllegalArgumentException("Поля у неуспешного ответа: " + id);
          rows.add(new Prediction(id, raw, status, fields));
        }
      }
      if (!ids.equals(originals.keySet()))
        throw new IllegalArgumentException("Состав ID ответов не совпадает с оцениваемой выборкой");
      predictions.put(method, List.copyOf(rows));
    }
    return new SavedResearchRun(
        dataset,
        split,
        List.copyOf(references),
        Collections.unmodifiableMap(predictions),
        manifest);
  }
}
