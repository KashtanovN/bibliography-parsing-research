package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Complete per-reference audit, including correct answers, empty fields and masked pairs. */
final class RecordComparisonWriter {
  private static final ObjectMapper JSON = new ObjectMapper();

  private RecordComparisonWriter() {}

  static void write(SavedResearchRun saved, Path run, Path output) throws IOException {
    Map<String, ResearchDataset.AnnotatedReference> refs = new HashMap<>();
    saved.references().forEach(ref -> refs.put(ref.input().id(), ref));
    Map<String, String> artifacts = new TreeMap<>();
    artifacts.put("run.json", ExperimentSnapshot.sha(run.resolve("run.json")));
    int records = 0;
    try (var writer =
        Files.newBufferedWriter(
            output.resolve("records.jsonl"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW)) {
      for (var method : saved.predictions().entrySet()) {
        Path source = run.resolve(method.getKey() + ".jsonl");
        artifacts.put(method.getKey() + ".jsonl", ExperimentSnapshot.sha(source));
        Map<String, JsonNode> originals = new HashMap<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
          JsonNode row = JSON.readTree(line);
          originals.put(row.path("id").asText(), row);
        }
        for (var prediction : method.getValue()) {
          var ref = refs.get(prediction.id());
          var raw = originals.get(prediction.id());
          var comparison = new ExtractionEvaluator().evaluate(List.of(ref), List.of(prediction));
          Map<ReferenceField, Object> fields = new EnumMap<>(ReferenceField.class);
          for (ReferenceField field : ReferenceField.values()) {
            String expected = Objects.toString(ref.expectedFields().get(field), "");
            String predicted = Objects.toString(prediction.fields().get(field), "");
            String a = FieldNormalization.normalize(field, expected),
                b = FieldNormalization.normalize(field, predicted);
            String outcome =
                ref.ambiguousFields().contains(field)
                    ? "EXCLUDED_AMBIGUOUS"
                    : a.isEmpty() && b.isEmpty()
                        ? "BOTH_EMPTY"
                        : a.equals(b)
                            ? "MATCH"
                            : b.isEmpty() ? "MISSING" : a.isEmpty() ? "EXTRA" : "WRONG";
            fields.put(
                field,
                Map.of(
                    "expected",
                    expected,
                    "predicted",
                    predicted,
                    "normalizedExpected",
                    a,
                    "normalizedPredicted",
                    b,
                    "outcome",
                    outcome,
                    "metrics",
                    comparison.fields().get(field)));
          }
          Map<String, Object> record = new LinkedHashMap<>();
          record.put("method", method.getKey());
          record.put("id", prediction.id());
          record.put("split", saved.split().name());
          record.put("rawCitation", prediction.rawCitation());
          record.put("sourceGroup", ref.sourceGroup());
          record.put("sourceType", ref.sourceType());
          record.put("status", prediction.status());
          record.put("fields", prediction.fields());
          record.put("rawResponse", raw.get("rawResponse"));
          record.put("error", raw.get("error"));
          record.put("warnings", raw.has("warnings") ? raw.get("warnings") : List.of());
          record.put("durationMillis", raw.get("durationMillis"));
          record.put("startedAt", raw.get("startedAt"));
          record.put("finishedAt", raw.get("finishedAt"));
          record.put("normalizationVersion", FieldNormalization.VERSION);
          record.put("fieldResults", fields);
          record.put("summary", comparison.summary());
          record.put("fullyCorrectEligible", ref.ambiguousFields().isEmpty());
          record.put(
              "fullyCorrect",
              ref.ambiguousFields().isEmpty() ? comparison.summary().fullyCorrect() == 1 : null);
          writer.write(JSON.writeValueAsString(record));
          writer.newLine();
          records++;
        }
      }
    }
    var manifest = new LinkedHashMap<String, Object>();
    manifest.put("createdAt", Instant.now().toString());
    manifest.put("datasetSha256", saved.dataset().sha256());
    manifest.put("split", saved.split().name());
    manifest.put("referencesPerMethod", saved.references().size());
    manifest.put("comparisonRecords", records);
    manifest.put("normalizationVersion", FieldNormalization.VERSION);
    manifest.put("implementation", ExperimentSnapshot.implementation());
    manifest.put("sourceHashes", artifacts);
    manifest.put("recordsSha256", ExperimentSnapshot.sha(output.resolve("records.jsonl")));
    manifest.put(
        "legacyMissingMetadata",
        "Null timing/raw-response/error values mean not recorded or not applicable; they are never"
            + " reconstructed retrospectively.");
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("comparison_manifest.json").toFile(), manifest);
  }
}
