package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ssau.bibliography.research.ExtractionEvaluator.*;
import ru.ssau.bibliography.research.ResearchDataset.*;

class ExtractionEvaluatorTest {
  @Test
  void authorListSeparatorsDoNotMaskChangedInitialsOrderOrAddedAuthors() {
    String canonical = "Иванов И. И. | Петров П. П.";
    assertEquals(
        FieldNormalization.normalize(ReferenceField.AUTHORS, canonical),
        FieldNormalization.normalize(ReferenceField.AUTHORS, "Иванов, И.И., Петров П.П."));
    for (String wrong :
        List.of(
            "Иванов И.А., Петров П.П.",
            "Петров П.П., Иванов И.И.",
            "Иванов И.И., Петров П.П., Сидоров С.С.",
            "Иванов И.И."))
      assertNotEquals(
          FieldNormalization.normalize(ReferenceField.AUTHORS, canonical),
          FieldNormalization.normalize(ReferenceField.AUTHORS, wrong));
    assertEquals(
        FieldNormalization.normalize(ReferenceField.AUTHORS, "Душкин А. В. | [и др.]"),
        FieldNormalization.normalize(ReferenceField.AUTHORS, "Душкин А.В., [и др.]"));
  }

  private AnnotatedReference reference(
      String id, Map<ReferenceField, String> gold, Set<ReferenceField> ambiguous) {
    return new AnnotatedReference(
        new ParserInput(id, "Исходная строка " + id),
        Split.DEV,
        "source-" + id,
        "ВКР",
        gold,
        ambiguous,
        new FormattingContext("ГОСТ Р 7.0.5–2008", "", null, ""));
  }

  private Prediction prediction(
      AnnotatedReference ref, String status, Map<ReferenceField, String> fields) {
    return new Prediction(ref.input().id(), ref.input().rawCitation(), status, fields);
  }

  @Test
  void wrongNonemptyValueCountsBothFalsePositiveAndFalseNegative() {
    var a =
        reference(
            "a", Map.of(ReferenceField.TITLE, "Правильно", ReferenceField.YEAR, "2020"), Set.of());
    var b = reference("b", Map.of(ReferenceField.TITLE, "Название"), Set.of());
    var result =
        new ExtractionEvaluator()
            .evaluate(
                List.of(a, b),
                List.of(
                    prediction(
                        a,
                        "SUCCESS",
                        Map.of(
                            ReferenceField.TITLE,
                            "Правильно",
                            ReferenceField.YEAR,
                            "2019",
                            ReferenceField.PUBLISHER,
                            "Новый издатель")),
                    prediction(b, "ERROR", Map.of())));
    var s = result.summary();
    assertEquals(1, s.tp());
    assertEquals(2, s.fp());
    assertEquals(2, s.fn());
    assertEquals(1.0 / 3, s.microF1(), 1e-12);
    assertEquals(1.0 / 3, s.precision(), 1e-12);
    assertEquals(1.0 / 3, s.recall(), 1e-12);
    assertEquals(1, s.errors());
    assertEquals(1, s.extraFilledFields());
    assertEquals(1, s.conflictingFields());
    assertEquals(0, s.fullyCorrect());
    assertEquals(2, s.fullyCorrectEligible());
    assertEquals(2, s.macroFields()); // Gold-supported fields, not the method-specific union.
    assertEquals(1, result.fields().get(ReferenceField.YEAR).fp());
    assertEquals(1, result.fields().get(ReferenceField.YEAR).fn());
  }

  @Test
  void ambiguousFieldsAreMaskedAndFullRecordDenominatorIsExplicit() {
    var ref =
        reference(
            "a",
            Map.of(ReferenceField.TITLE, "Статья", ReferenceField.PUBLISHER, "Кандидат"),
            Set.of(ReferenceField.PUBLISHER));
    var result =
        new ExtractionEvaluator()
            .evaluate(
                List.of(ref),
                List.of(
                    prediction(
                        ref,
                        "SUCCESS",
                        Map.of(
                            ReferenceField.TITLE, "Статья", ReferenceField.PUBLISHER, "Другой"))));
    assertEquals(1, result.summary().tp());
    assertEquals(0, result.summary().fp());
    assertEquals(0, result.summary().fn());
    assertEquals(1, result.fields().get(ReferenceField.PUBLISHER).excludedAmbiguous());
    assertEquals(0, result.summary().fullyCorrectEligible());
    assertEquals(1, result.summary().fullyCorrectExcludedAmbiguous());
    assertNull(result.summary().fullyCorrectRate());
    assertTrue(result.differences().isEmpty());
  }

  @Test
  void bothBlankIsNotTruePositiveAndEmptyPredictionStillMissesGold() {
    var ref = reference("a", Map.of(ReferenceField.TITLE, "Название"), Set.of());
    var result =
        new ExtractionEvaluator()
            .evaluate(List.of(ref), List.of(prediction(ref, "EMPTY", Map.of())));
    assertEquals(0, result.summary().tp());
    assertEquals(1, result.summary().fn());
    assertEquals(1, result.summary().empty());
    assertEquals(0, result.fields().get(ReferenceField.ISBN).comparedNonEmptyPairs());
    assertNull(result.fields().get(ReferenceField.ISBN).f1());
    assertEquals(0, result.summary().microF1());
  }

  @Test
  void normalizationAppliesTheSamePolicyToEveryMethod() {
    var ref =
        reference(
            "a",
            Map.of(
                ReferenceField.AUTHORS,
                "Иванов И. И. | Петров П. П.",
                ReferenceField.PAGES,
                "10–20",
                ReferenceField.DOI,
                "10.1000/abc",
                ReferenceField.DOCUMENT_TYPE,
                "Сетевая журнальная статья",
                ReferenceField.ACCESS_DATE,
                "2026-10-27"),
            Set.of());
    var result =
        new ExtractionEvaluator()
            .evaluate(
                List.of(ref),
                List.of(
                    prediction(
                        ref,
                        "SUCCESS",
                        Map.of(
                            ReferenceField.AUTHORS,
                            "Иванов И.И.; Петров П.П.",
                            ReferenceField.PAGES,
                            "10 - 20",
                            ReferenceField.DOI,
                            "https://doi.org/10.1000/ABC",
                            ReferenceField.DOCUMENT_TYPE,
                            "Журнальная статья",
                            ReferenceField.ACCESS_DATE,
                            "27.10.2026"))));
    assertEquals(1.0, result.summary().microF1());
    assertEquals(1, result.summary().fullyCorrect());
    assertEquals(1.0, result.bySourceType().get("ВКР").fullyCorrectRate());
    assertNotEquals(
        FieldNormalization.normalize(ReferenceField.TITLE, "Тест"),
        FieldNormalization.normalize(ReferenceField.TITLE, "тест"));
  }

  @Test
  void extraPredictionsAreAuditCandidatesNotAutomaticallyFabricatedFacts() {
    var ref = reference("a", Map.of(ReferenceField.TITLE, "Название"), Set.of());
    var result =
        new ExtractionEvaluator()
            .evaluate(
                List.of(ref),
                List.of(
                    prediction(
                        ref,
                        "SUCCESS",
                        Map.of(
                            ReferenceField.TITLE,
                            "Название",
                            ReferenceField.PUBLISHER,
                            "Выдумка",
                            ReferenceField.DOCUMENT_TYPE,
                            "Книга"))));
    assertEquals(2, result.summary().extraFilledFields());
    assertTrue(
        result.differences().stream()
            .anyMatch(
                d ->
                    d.field() == ReferenceField.PUBLISHER
                        && d.lexicalPresence().equals("NOT_LEXICALLY_FOUND")));
    assertTrue(
        result.differences().stream()
            .anyMatch(
                d ->
                    d.field() == ReferenceField.DOCUMENT_TYPE
                        && d.lexicalPresence().equals("DERIVED_FIELD")));
  }

  @Test
  void rejectsMissingDuplicateForeignIdsAndChangedInput() {
    var ref = reference("a", Map.of(ReferenceField.TITLE, "Название"), Set.of());
    var p = prediction(ref, "SUCCESS", Map.of(ReferenceField.TITLE, "Название"));
    var evaluator = new ExtractionEvaluator();
    assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(List.of(ref), List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> evaluator.evaluate(List.of(ref), List.of(p, p)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluator.evaluate(
                List.of(ref),
                List.of(new Prediction("b", p.rawCitation(), p.status(), p.fields()))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            evaluator.evaluate(
                List.of(ref),
                List.of(new Prediction("a", "Подменённый текст", p.status(), p.fields()))));
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluator.evaluate(List.of(ref), List.of(prediction(ref, "ERROR", p.fields()))));
  }
}
