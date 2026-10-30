package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluateRunCommandTest {
  @TempDir Path temporary;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void evaluatesSavedRunAndRetainsPredictionsAndDataset() throws Exception {
    Path run = temporary.resolve("run");
    ParseReferencesCommand.run(
        new String[] {
          "--parse-dataset=../Dataset.xlsx", "--method=rules", "--limit=3", "--output=" + run
        });
    byte[] predictions = Files.readAllBytes(run.resolve("rules.jsonl"));
    byte[] dataset = Files.readAllBytes(Path.of("../Dataset.xlsx"));
    EvaluateRunCommand.run(new String[] {"--evaluate-run=" + run, "--dataset=../Dataset.xlsx"});
    JsonNode report = json.readTree(run.resolve("evaluation/metrics.json").toFile());
    assertEquals(3, report.path("records").asInt());
    assertEquals("dev", report.path("split").asText());
    assertEquals(17, report.path("results").path("rules").path("fields").size());
    assertTrue(
        Files.readString(run.resolve("evaluation/report.md")).contains("не итоговая оценка"));
    assertTrue(Files.isRegularFile(run.resolve("evaluation/llm_additions_review.csv")));
    List<String> comparisons = Files.readAllLines(run.resolve("evaluation/records.jsonl"));
    assertEquals(3, comparisons.size());
    JsonNode first = json.readTree(comparisons.get(0));
    assertTrue(first.path("fullyCorrect").asBoolean());
    assertEquals(17, first.path("fieldResults").size());
    assertEquals("MATCH", first.path("fieldResults").path("TITLE").path("outcome").asText());
    assertEquals("BOTH_EMPTY", first.path("fieldResults").path("EDITION").path("outcome").asText());
    assertTrue(first.has("rawResponse"));
    assertTrue(first.has("error"));
    assertEquals(
        ExperimentSnapshot.sha(run.resolve("evaluation/records.jsonl")),
        json.readTree(run.resolve("evaluation/comparison_manifest.json").toFile())
            .path("recordsSha256")
            .asText());
    assertArrayEquals(predictions, Files.readAllBytes(run.resolve("rules.jsonl")));
    assertArrayEquals(dataset, Files.readAllBytes(Path.of("../Dataset.xlsx")));
    assertThrows(
        FileAlreadyExistsException.class,
        () ->
            EvaluateRunCommand.run(
                new String[] {"--evaluate-run=" + run, "--dataset=../Dataset.xlsx"}));
  }

  @Test
  void rejectsDatasetVersionMismatchAndPartialPredictionFile() throws Exception {
    Path run = temporary.resolve("run");
    ParseReferencesCommand.run(
        new String[] {
          "--parse-dataset=../Dataset.xlsx", "--method=rules", "--limit=2", "--output=" + run
        });
    var manifest =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(run.resolve("run.json").toFile());
    String sha = manifest.path("datasetSha256").asText();
    manifest.put("datasetSha256", "wrong");
    json.writeValue(run.resolve("run.json").toFile(), manifest);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EvaluateRunCommand.run(
                new String[] {"--evaluate-run=" + run, "--dataset=../Dataset.xlsx"}));
    manifest.put("datasetSha256", sha);
    json.writeValue(run.resolve("run.json").toFile(), manifest);
    var first = Files.readAllLines(run.resolve("rules.jsonl")).get(0);
    Files.writeString(run.resolve("rules.jsonl"), first + "\n");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EvaluateRunCommand.run(
                new String[] {"--evaluate-run=" + run, "--dataset=../Dataset.xlsx"}));
    assertFalse(Files.exists(run.resolve("evaluation")));
  }

  @Test
  void allRecordsRetainErrorsWrongValuesAndAmbiguityWithoutDroppingRows() throws Exception {
    Path run = temporary.resolve("full");
    ParseReferencesCommand.run(new String[] {"--parse-dataset=../Dataset.xlsx", "--output=" + run});
    var lines = Files.readAllLines(run.resolve("rules.jsonl"));
    ObjectNode first = (ObjectNode) json.readTree(lines.get(0));
    first.put("status", "ERROR");
    first.put("error", "synthetic test failure");
    first.set("fields", json.createObjectNode());
    lines.set(0, json.writeValueAsString(first));
    ObjectNode second = (ObjectNode) json.readTree(lines.get(1));
    ((ObjectNode) second.get("fields")).put("YEAR", "1901");
    lines.set(1, json.writeValueAsString(second));
    Files.write(run.resolve("rules.jsonl"), lines);
    EvaluateRunCommand.run(new String[] {"--evaluate-run=" + run, "--dataset=../Dataset.xlsx"});
    var rows = Files.readAllLines(run.resolve("evaluation/records.jsonl"));
    assertEquals(92, rows.size());
    JsonNode error = json.readTree(rows.get(0));
    assertEquals("synthetic test failure", error.path("error").asText());
    assertFalse(error.path("fullyCorrect").asBoolean());
    assertEquals("MISSING", error.path("fieldResults").path("TITLE").path("outcome").asText());
    assertEquals(
        "WRONG",
        json.readTree(rows.get(1)).path("fieldResults").path("YEAR").path("outcome").asText());
    JsonNode ambiguous =
        rows.stream()
            .map(
                row -> {
                  try {
                    return json.readTree(row);
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                })
            .filter(row -> row.path("id").asText().equals("R375"))
            .findFirst()
            .orElseThrow();
    assertTrue(ambiguous.path("fullyCorrect").isNull());
    assertEquals(
        "EXCLUDED_AMBIGUOUS",
        ambiguous.path("fieldResults").path("PAGE_MEANING").path("outcome").asText());
  }
}
