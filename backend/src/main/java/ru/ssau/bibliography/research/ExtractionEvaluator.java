package ru.ssau.bibliography.research;

import java.text.Normalizer;
import java.util.*;
import ru.ssau.bibliography.research.ResearchDataset.AnnotatedReference;

/**
 * Scores extraction only. Formatting correctness and fabricated facts require separate assessment.
 */
public final class ExtractionEvaluator {
  public record Prediction(
      String id, String rawCitation, String status, Map<ReferenceField, String> fields) {
    public Prediction {
      fields = Map.copyOf(fields);
    }
  }

  public record FieldMetrics(
      long tp,
      long fp,
      long fn,
      long excludedAmbiguous,
      long comparedNonEmptyPairs,
      Double precision,
      Double recall,
      Double f1) {}

  public record Summary(
      int records,
      int success,
      int empty,
      int errors,
      long tp,
      long fp,
      long fn,
      Double precision,
      Double recall,
      Double microF1,
      Double macroF1,
      int macroFields,
      int fullyCorrect,
      int fullyCorrectEligible,
      int fullyCorrectExcludedAmbiguous,
      Double fullyCorrectRate,
      int extraFilledFields,
      int conflictingFields) {}

  public record Difference(
      String id,
      ReferenceField field,
      String kind,
      String expected,
      String predicted,
      String rawCitation,
      String lexicalPresence) {}

  public record Result(
      Summary summary,
      Map<ReferenceField, FieldMetrics> fields,
      Map<String, Summary> bySourceType,
      List<Difference> differences) {}

  public Result evaluate(List<AnnotatedReference> references, List<Prediction> predictions) {
    Map<String, Prediction> byId = new LinkedHashMap<>();
    for (Prediction p : predictions) {
      if (byId.putIfAbsent(p.id(), p) != null)
        throw new IllegalArgumentException("Повтор ID в ответах: " + p.id());
      if (!Set.of("SUCCESS", "EMPTY", "ERROR").contains(p.status()))
        throw new IllegalArgumentException("Неизвестный статус: " + p.status());
      if (!p.status().equals("SUCCESS") && !p.fields().isEmpty())
        throw new IllegalArgumentException("Поля у неуспешного ответа: " + p.id());
    }
    Set<String> ids = new HashSet<>();
    for (AnnotatedReference ref : references)
      if (!ids.add(ref.input().id())) throw new IllegalArgumentException("Повтор ID эталона");
    if (!ids.equals(byId.keySet()))
      throw new IllegalArgumentException("Состав ID ответов не совпадает с оцениваемой выборкой");
    Accumulator all = new Accumulator();
    Map<String, Accumulator> groups = new TreeMap<>();
    List<Difference> differences = new ArrayList<>();
    for (AnnotatedReference ref : references) {
      Prediction p = byId.get(ref.input().id());
      if (!ref.input().rawCitation().equals(p.rawCitation()))
        throw new IllegalArgumentException("Изменена исходная строка: " + p.id());
      all.add(ref, p, differences);
      groups.computeIfAbsent(ref.sourceType(), ignored -> new Accumulator()).add(ref, p, null);
    }
    Map<String, Summary> grouped = new TreeMap<>();
    groups.forEach((key, a) -> grouped.put(key, a.summary()));
    return new Result(all.summary(), all.fields(), Map.copyOf(grouped), List.copyOf(differences));
  }

  private static class Counts {
    long tp, fp, fn, excluded, compared;

    FieldMetrics metrics() {
      return new FieldMetrics(
          tp,
          fp,
          fn,
          excluded,
          compared,
          ratio(tp, tp + fp),
          ratio(tp, tp + fn),
          ratio(2 * tp, 2 * tp + fp + fn));
    }
  }

  private static class Accumulator {
    final EnumMap<ReferenceField, Counts> counts = new EnumMap<>(ReferenceField.class);
    int records, success, empty, errors, exact, eligible, excludedRecords, extra, conflicts;

    Accumulator() {
      for (ReferenceField f : ReferenceField.values()) counts.put(f, new Counts());
    }

    void add(AnnotatedReference ref, Prediction prediction, List<Difference> differences) {
      records++;
      switch (prediction.status()) {
        case "SUCCESS" -> success++;
        case "EMPTY" -> empty++;
        case "ERROR" -> errors++;
        default -> throw new IllegalArgumentException();
      }
      boolean equal = prediction.status().equals("SUCCESS");
      for (ReferenceField field : ReferenceField.values()) {
        Counts c = counts.get(field);
        if (ref.ambiguousFields().contains(field)) {
          c.excluded++;
          continue;
        }
        String expected = ref.expectedFields().get(field),
            predicted = prediction.fields().get(field);
        String a = FieldNormalization.normalize(field, expected),
            b = FieldNormalization.normalize(field, predicted);
        if (a.isEmpty() && b.isEmpty()) continue;
        c.compared++;
        if (a.equals(b)) {
          c.tp++;
          continue;
        }
        equal = false;
        if (!a.isEmpty()) c.fn++;
        if (!b.isEmpty()) c.fp++;
        String kind = b.isEmpty() ? "MISSING" : a.isEmpty() ? "EXTRA" : "WRONG";
        if (kind.equals("EXTRA")) extra++;
        if (kind.equals("WRONG")) conflicts++;
        if (differences != null)
          differences.add(
              new Difference(
                  ref.input().id(),
                  field,
                  kind,
                  expected,
                  predicted,
                  ref.input().rawCitation(),
                  lexicalPresence(field, predicted, ref.input().rawCitation())));
      }
      if (ref.ambiguousFields().isEmpty()) {
        eligible++;
        if (equal) exact++;
      } else excludedRecords++;
    }

    Map<ReferenceField, FieldMetrics> fields() {
      EnumMap<ReferenceField, FieldMetrics> result = new EnumMap<>(ReferenceField.class);
      counts.forEach((field, c) -> result.put(field, c.metrics()));
      return Collections.unmodifiableMap(result);
    }

    Summary summary() {
      long tp = 0, fp = 0, fn = 0;
      double macro = 0;
      int n = 0;
      for (Counts c : counts.values()) {
        tp += c.tp;
        fp += c.fp;
        fn += c.fn;
        Double f1 = c.metrics().f1();
        if (c.tp + c.fn > 0 && f1 != null) {
          macro += f1;
          n++;
        }
      }
      return new Summary(
          records,
          success,
          empty,
          errors,
          tp,
          fp,
          fn,
          ratio(tp, tp + fp),
          ratio(tp, tp + fn),
          ratio(2 * tp, 2 * tp + fp + fn),
          n == 0 ? null : macro / n,
          n,
          exact,
          eligible,
          excludedRecords,
          ratio(exact, eligible),
          extra,
          conflicts);
    }
  }

  private static Double ratio(long numerator, long denominator) {
    return denominator == 0 ? null : (double) numerator / denominator;
  }

  private static String lexicalPresence(ReferenceField field, String value, String raw) {
    if (value == null || value.isBlank()) return "NO_PREDICTION";
    if (field == ReferenceField.DOCUMENT_TYPE || field == ReferenceField.PAGE_MEANING)
      return "DERIVED_FIELD";
    String needle = lexical(value), haystack = lexical(raw);
    return !needle.isEmpty() && haystack.contains(needle)
        ? "LEXICALLY_PRESENT"
        : "NOT_LEXICALLY_FOUND";
  }

  private static String lexical(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]", "");
  }
}
