package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QualityReviewCommandTest {
  @TempDir Path directory;
  private final ObjectMapper json = new ObjectMapper();

  private ObjectNode fixture() {
    ObjectNode review = json.createObjectNode();
    review.put("llmIncluded", true);
    ArrayNode rows = review.putArray("entries");
    row(rows, "formatting", true, "");
    row(rows, "formatting", true, "").put("automaticFailure", true);
    row(rows, "formatting", false, "");
    row(rows, "llm_field", true, "");
    row(rows, "llm_field", true, "").put("derivedCategory", true);
    return review;
  }

  private ObjectNode row(ArrayNode rows, String kind, boolean eligible, String decision) {
    return rows.addObject()
        .put("kind", kind)
        .put("method", "ollama")
        .put("id", "R" + rows.size())
        .put("eligible", eligible)
        .put("decision", decision)
        .put("reviewer", "")
        .put("evidence", "");
  }

  @Test
  void pendingIsNotZeroHallucinationsOrCorrectFormatting() {
    var summary = QualityReviewCommand.summarize(fixture());
    var format = summary.path("formatting").path("ollama");
    assertEquals(2, format.path("eligible").asInt());
    assertEquals(1, format.path("incorrect").asInt());
    assertEquals(1, format.path("pending").asInt());
    assertEquals(1, format.path("excludedAmbiguous").asInt());
    assertTrue(format.path("fullyCorrectFormattingRate").isNull());
    assertEquals(0.5, format.path("possibleUpperBound").asDouble());
    assertTrue(summary.path("llmUnsupportedFacts").path("totalUnsupported").isNull());
  }

  @Test
  void completedReviewUsesFixedDenominatorAndSeparatesDerivedCategories() {
    var review = fixture();
    var entries = (ArrayNode) review.get("entries");
    ((ObjectNode) entries.get(0)).put("decision", "correct");
    ((ObjectNode) entries.get(3)).put("decision", "unsupported");
    ((ObjectNode) entries.get(4)).put("decision", "unsupported");
    var summary = QualityReviewCommand.summarize(review);
    assertEquals(
        0.5,
        summary.path("formatting").path("ollama").path("fullyCorrectFormattingRate").asDouble());
    var llm = summary.path("llmUnsupportedFacts");
    assertEquals(1, llm.path("totalUnsupported").asInt());
    assertEquals(1, llm.path("derivedCategoryErrors").asInt());
    assertEquals(1, llm.path("recordsWithConfirmedUnsupported").asInt());
  }

  @Test
  void unclearStaysUnresolvedAndAbsentLlmIsNotZero() {
    var review = fixture();
    ((ObjectNode) review.path("entries").get(3)).put("decision", "unclear");
    assertTrue(
        QualityReviewCommand.summarize(review)
            .path("llmUnsupportedFacts")
            .path("totalUnsupported")
            .isNull());
    review.put("llmIncluded", false);
    ((ArrayNode) review.get("entries")).removeAll();
    assertTrue(
        QualityReviewCommand.summarize(review)
            .path("llmUnsupportedFacts")
            .path("totalUnsupported")
            .isNull());
  }

  @Test
  void decisionsNeedIdentityEvidenceAndUnchangedArtifacts() {
    var template = fixture();
    var edited = template.deepCopy();
    ObjectNode row = (ObjectNode) edited.path("entries").get(0);
    row.put("decision", "correct");
    assertThrows(
        IllegalArgumentException.class, () -> QualityReviewCommand.validate(template, edited));
    row.put("reviewer", "Проверяющий / human")
        .put("evidence", "Сверены реквизиты и требования профиля");
    assertDoesNotThrow(() -> QualityReviewCommand.validate(template, edited));
    row.put("eligible", false);
    assertThrows(
        IllegalArgumentException.class, () -> QualityReviewCommand.validate(template, edited));
    edited.put("llmIncluded", false);
    assertThrows(
        IllegalArgumentException.class, () -> QualityReviewCommand.validate(template, edited));
  }

  @Test
  void cannotApproveFailedOrExcludedRecordsOrInventDecisionCodes() {
    var template = fixture();
    for (int index : List.of(1, 2)) {
      var edited = template.deepCopy();
      ((ObjectNode) edited.path("entries").get(index))
          .put("decision", "correct")
          .put("reviewer", "reviewer")
          .put("evidence", "evidence");
      assertThrows(
          IllegalArgumentException.class, () -> QualityReviewCommand.validate(template, edited));
    }
    var edited = template.deepCopy();
    ((ObjectNode) edited.path("entries").get(3)).put("decision", "correct");
    assertThrows(
        IllegalArgumentException.class, () -> QualityReviewCommand.validate(template, edited));
  }

  @Test
  void commandBindsReviewsToRealInputsAndRejectsStaleFiles() throws Exception {
    Path run = directory.resolve("run");
    ParseReferencesCommand.run(
        new String[] {"--parse-dataset=../Dataset.xlsx", "--limit=1", "--output=" + run});
    FormatRunCommand.run(new String[] {"--format-run=" + run, "--dataset=../Dataset.xlsx"});
    QualityReviewCommand.run(new String[] {"--quality-run=" + run, "--dataset=../Dataset.xlsx"});
    var summary = json.readTree(run.resolve("quality/quality_metrics.json").toFile());
    assertTrue(
        summary.path("formatting").path("rules").path("fullyCorrectFormattingRate").isNull());
    assertFalse(summary.path("llmUnsupportedFacts").path("available").asBoolean());
    Path review = run.resolve("quality/review.json");
    var edited = (ObjectNode) json.readTree(review.toFile());
    ((ObjectNode) edited.path("entries").get(0))
        .put("decision", "correct")
        .put("reviewer", "test fixture, not real review")
        .put("evidence", "fixture assertion");
    json.writeValue(review.toFile(), edited);
    QualityReviewCommand.run(
        new String[] {
          "--quality-run=" + run,
          "--dataset=../Dataset.xlsx",
          "--review-file=" + review,
          "--quality-output=" + directory.resolve("checked")
        });
    assertEquals(
        1.0,
        json.readTree(directory.resolve("checked/quality_metrics.json").toFile())
            .path("formatting")
            .path("rules")
            .path("fullyCorrectFormattingRate")
            .asDouble());
    Files.writeString(
        run.resolve("rules.jsonl"), Files.readString(run.resolve("rules.jsonl")) + " ");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            QualityReviewCommand.run(
                new String[] {
                  "--quality-run=" + run,
                  "--dataset=../Dataset.xlsx",
                  "--review-file=" + review,
                  "--quality-output=" + directory.resolve("stale")
                }));
    assertFalse(Files.exists(directory.resolve("stale")));
  }

  @Test
  void reviewIncludesMatchingConflictingAndExtraLlmFields() throws Exception {
    Path run = directory.resolve("llm-fixture");
    ParseReferencesCommand.run(
        new String[] {"--parse-dataset=../Dataset.xlsx", "--limit=1", "--output=" + run});
    ObjectNode manifest = (ObjectNode) json.readTree(run.resolve("run.json").toFile());
    ObjectNode methods = json.createObjectNode();
    methods.putObject("ollama");
    manifest.set("methods", methods);
    json.writeValue(run.resolve("run.json").toFile(), manifest);
    ObjectNode prediction =
        (ObjectNode) json.readTree(Files.readString(run.resolve("rules.jsonl")));
    prediction.put("method", "ollama");
    ((ObjectNode) prediction.get("fields")).put("YEAR", "1901").put("ISBN", "111");
    Files.writeString(run.resolve("ollama.jsonl"), json.writeValueAsString(prediction) + "\n");
    FormatRunCommand.run(new String[] {"--format-run=" + run, "--dataset=../Dataset.xlsx"});
    QualityReviewCommand.run(new String[] {"--quality-run=" + run, "--dataset=../Dataset.xlsx"});
    JsonNode review = json.readTree(run.resolve("quality/review.json").toFile());
    Set<String> relations = new HashSet<>();
    int fields = 0;
    for (JsonNode row : review.path("entries")) {
      if (!row.path("kind").asText().equals("llm_field")) continue;
      fields++;
      relations.add(row.path("goldRelation").asText());
    }
    assertEquals(prediction.path("fields").size(), fields);
    assertEquals(Set.of("MATCH", "EXTRA", "WRONG"), relations);
  }

  @Test
  void misassignedAndGoldErrorDoNotBecomeFabricatedFacts() {
    for (String decision : List.of("misassigned", "gold_error", "supported")) {
      var review = fixture();
      ((ObjectNode) review.path("entries").get(3)).put("decision", decision);
      var llm = QualityReviewCommand.summarize(review).path("llmUnsupportedFacts");
      assertEquals(1, llm.path("reviewed").asInt());
      assertEquals(0, llm.path("totalUnsupported").asInt());
      assertTrue(llm.path("complete").asBoolean());
      ((ObjectNode) review.path("entries").get(4)).put("decision", "misassigned");
      assertEquals(
          1,
          QualityReviewCommand.summarize(review)
              .path("llmUnsupportedFacts")
              .path("derivedCategoryErrors")
              .asInt());
    }
  }

  @Test
  void failedSourceReviewRoundTripsWithoutMissingNodeDifferences() throws Exception {
    Path run = directory.resolve("failed-source");
    ParseReferencesCommand.run(
        new String[] {"--parse-dataset=../Dataset.xlsx", "--limit=1", "--output=" + run});
    ObjectNode prediction =
        (ObjectNode) json.readTree(Files.readString(run.resolve("rules.jsonl")));
    prediction.put("status", "ERROR");
    prediction.set("fields", json.createObjectNode());
    Files.writeString(run.resolve("rules.jsonl"), json.writeValueAsString(prediction) + "\n");
    FormatRunCommand.run(new String[] {"--format-run=" + run, "--dataset=../Dataset.xlsx"});
    QualityReviewCommand.run(new String[] {"--quality-run=" + run, "--dataset=../Dataset.xlsx"});
    QualityReviewCommand.run(
        new String[] {
          "--quality-run=" + run,
          "--dataset=../Dataset.xlsx",
          "--review-file=" + run.resolve("quality/review.json"),
          "--quality-output=" + directory.resolve("failed-reviewed")
        });
    JsonNode summary =
        json.readTree(directory.resolve("failed-reviewed/quality_metrics.json").toFile());
    assertEquals(
        0.0,
        summary.path("formatting").path("rules").path("fullyCorrectFormattingRate").asDouble());
    assertEquals(1, summary.path("formatting").path("rules").path("incorrect").asInt());
  }
}
