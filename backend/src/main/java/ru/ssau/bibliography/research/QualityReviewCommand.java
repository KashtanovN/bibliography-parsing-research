package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import ru.ssau.bibliography.SharedCitationFormatter;
import ru.ssau.bibliography.research.ResearchDataset.AnnotatedReference;

/** Aggregates explicit review decisions; never treats formatter completion as correctness. */
public final class QualityReviewCommand {
  public static final String VERSION = "quality-review-v1";
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final Set<String> EDITABLE = Set.of("decision", "evidence", "reviewer");

  private QualityReviewCommand() {}

  public static void run(String[] args) throws IOException {
    Map<String, String> options = new HashMap<>();
    for (String arg : args) {
      int p = arg.indexOf('=');
      if (!arg.startsWith("--") || p < 3 || p == arg.length() - 1)
        throw new IllegalArgumentException("Ожидается --имя=значение");
      String key = arg.substring(2, p);
      if (!Set.of("quality-run", "dataset", "quality-output", "review-file", "formatting")
              .contains(key)
          || options.putIfAbsent(key, arg.substring(p + 1)) != null)
        throw new IllegalArgumentException("Неизвестный или повторный параметр: " + key);
    }
    if (!options.containsKey("quality-run") || !options.containsKey("dataset"))
      throw new IllegalArgumentException("Нужны --quality-run и --dataset");
    Path run = Path.of(options.get("quality-run"));
    var saved = SavedResearchRun.read(run, Path.of(options.get("dataset")));
    Path formatted =
        Path.of(options.getOrDefault("formatting", run.resolve("formatting").toString()));
    ObjectNode template = prepare(saved, run, formatted);
    ObjectNode reviewed = template;
    if (options.containsKey("review-file"))
      reviewed = validate(template, JSON.readTree(Path.of(options.get("review-file")).toFile()));
    ObjectNode summary = summarize(reviewed);
    Map<String, ExtractionEvaluator.Result> extraction = new LinkedHashMap<>();
    saved
        .predictions()
        .forEach(
            (method, predictions) ->
                extraction.put(
                    method, new ExtractionEvaluator().evaluate(saved.references(), predictions)));
    summary.set("extraction", JSON.valueToTree(extraction));
    Path output =
        Path.of(options.getOrDefault("quality-output", run.resolve("quality").toString()));
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.createDirectory(output);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("review.json").toFile(), reviewed);
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("quality_metrics.json").toFile(), summary);
    Files.writeString(
        output.resolve("report.md"),
        EvaluateRunCommand.markdown(extraction, saved.split(), saved.references().size())
            + "\n"
            + markdown(summary),
        StandardCharsets.UTF_8);
    System.out.println(
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "output", output.toAbsolutePath().normalize().toString(),
                    "records", saved.references().size(),
                    "formatting", summary.get("formatting"),
                    "llmUnsupportedFacts", summary.get("llmUnsupportedFacts"))));
  }

  private static ObjectNode prepare(SavedResearchRun saved, Path run, Path formatted)
      throws IOException {
    JsonNode manifest = JSON.readTree(formatted.resolve("formatting.json").toFile());
    if (!saved.dataset().sha256().equals(manifest.path("datasetSha256").asText())
        || !saved.split().name().equals(manifest.path("split").asText())
        || saved.references().size() != manifest.path("records").asInt(-1)
        || !SharedCitationFormatter.VERSION.equals(manifest.path("formatterVersion").asText()))
      throw new IllegalArgumentException("Оформление относится к другому набору/профилю/прогону");
    ObjectNode result = JSON.createObjectNode();
    result.put("protocolVersion", VERSION);
    result.put("normalizationVersion", FieldNormalization.VERSION);
    result.put("datasetSha256", saved.dataset().sha256());
    result.put("split", saved.split().name());
    result.put("records", saved.references().size());
    result.put("llmIncluded", saved.predictions().containsKey("ollama"));
    ObjectNode fingerprints = result.putObject("artifacts");
    fingerprints.put("run", sha(run.resolve("run.json")));
    fingerprints.put("formatting", sha(formatted.resolve("formatting.json")));
    ArrayNode entries = result.putArray("entries");
    for (var method : saved.predictions().entrySet()) {
      String name = method.getKey();
      fingerprints.put(name + "Predictions", sha(run.resolve(name + ".jsonl")));
      fingerprints.put(name + "Formatting", sha(formatted.resolve(name + ".jsonl")));
      Map<String, JsonNode> formattedById = new HashMap<>();
      for (String line : Files.readAllLines(formatted.resolve(name + ".jsonl"))) {
        JsonNode row = JSON.readTree(line);
        if (row == null
            || !row.isObject()
            || formattedById.putIfAbsent(row.path("id").asText(), row) != null)
          throw new IllegalArgumentException("Повтор/неверная запись оформления");
      }
      if (formattedById.size() != saved.references().size())
        throw new IllegalArgumentException("Неполное оформление: " + name);
      Map<String, AnnotatedReference> byId = new HashMap<>();
      saved.references().forEach(r -> byId.put(r.input().id(), r));
      for (var prediction : method.getValue()) {
        var ref = byId.get(prediction.id());
        var row = formattedById.get(prediction.id());
        if (row == null
            || !name.equals(row.path("method").asText())
            || !prediction.status().equals(row.path("sourceStatus").asText())
            || !JSON.valueToTree(prediction.fields()).equals(row.get("predictedFields")))
          throw new IllegalArgumentException(
              "Оформление не совпадает с ответом: " + prediction.id());
        var ctx =
            new SharedCitationFormatter.Context(
                SharedCitationFormatter.Profile.parse(ref.formatting().standard()),
                ref.formatting().selectedUrl(),
                ref.formatting().accessDate());
        JsonNode expectedContext = JSON.valueToTree(ctx);
        if (!expectedContext.equals(row.get("context"))
            || !expectedContext.equals(manifest.path("contexts").get(prediction.id())))
          throw new IllegalArgumentException("Изменён общий контекст: " + prediction.id());
        boolean success = prediction.status().equals("SUCCESS");
        String status = row.path("status").asText();
        if (success
            && (!Set.of("FORMATTED", "NEEDS_REVIEW").contains(status)
                || !status.equals(row.path("formatting").path("status").asText())
                || !row.path("formatting").path("formatted").isTextual()))
          throw new IllegalArgumentException("Неверный результат оформления");
        if (!success
            && (!status.equals("SKIPPED_SOURCE_" + prediction.status()) || row.has("formatting")))
          throw new IllegalArgumentException("Неуспешный ответ получил оформление");
        ObjectNode entry = entry(entries, "formatting", name, ref, "");
        entry.put("eligible", ref.ambiguousFields().isEmpty());
        entry.put("sourceStatus", prediction.status());
        entry.put("formatStatus", status);
        entry.set("context", expectedContext);
        entry.put("formatted", row.path("formatting").path("formatted").asText());
        JsonNode issues = row.path("formatting").path("issues");
        entry.set("issues", issues.isArray() ? issues : JSON.createArrayNode());
        if (!success) entry.put("automaticFailure", true);
        if (!name.equals("ollama")) continue;
        for (ReferenceField field : ReferenceField.values()) {
          String value = prediction.fields().get(field);
          if (value == null || value.isBlank()) continue;
          ObjectNode fact = entry(entries, "llm_field", name, ref, field.name());
          fact.put("eligible", !ref.ambiguousFields().contains(field));
          fact.put("predicted", value);
          String gold = ref.expectedFields().get(field);
          fact.put("expected", Objects.toString(gold, ""));
          fact.put(
              "goldRelation",
              FieldNormalization.normalize(field, value)
                      .equals(FieldNormalization.normalize(field, gold))
                  ? "MATCH"
                  : gold == null || gold.isBlank() ? "EXTRA" : "WRONG");
          fact.put(
              "derivedCategory",
              field == ReferenceField.DOCUMENT_TYPE || field == ReferenceField.PAGE_MEANING);
        }
      }
    }
    return result;
  }

  private static ObjectNode entry(
      ArrayNode entries, String kind, String method, AnnotatedReference ref, String field) {
    ObjectNode row = entries.addObject();
    row.put("kind", kind);
    row.put("method", method);
    row.put("id", ref.input().id());
    row.put("field", field);
    row.put("rawCitation", ref.input().rawCitation());
    row.put("decision", "");
    row.put("evidence", "");
    row.put("reviewer", "");
    return row;
  }

  static ObjectNode validate(ObjectNode template, JsonNode input) {
    if (input == null || !input.isObject())
      throw new IllegalArgumentException("Ожидается объект проверки");
    ObjectNode a = template.deepCopy(), b = ((ObjectNode) input).deepCopy();
    JsonNode supplied = b.remove("entries"), expected = a.remove("entries");
    if (!a.equals(b)
        || supplied == null
        || !supplied.isArray()
        || supplied.size() != expected.size())
      throw new IllegalArgumentException(
          "Проверка относится к другим артефактам или составу записей");
    for (int i = 0; i < expected.size(); i++) {
      if (!supplied.get(i).isObject())
        throw new IllegalArgumentException("Неверная запись проверки");
      ObjectNode original = ((ObjectNode) expected.get(i)).deepCopy();
      ObjectNode edited = ((ObjectNode) supplied.get(i)).deepCopy();
      for (String field : EDITABLE) {
        if (!edited.path(field).isTextual())
          throw new IllegalArgumentException("Требуется строка: " + field);
        original.remove(field);
        edited.remove(field);
      }
      if (!original.equals(edited))
        throw new IllegalArgumentException("Изменены исходные данные проверки, строка " + (i + 1));
      var row = supplied.get(i);
      String decision = row.path("decision").asText();
      Set<String> allowed =
          row.path("kind").asText().equals("formatting")
              ? Set.of("", "correct", "incorrect", "unclear")
              : Set.of("", "supported", "unsupported", "misassigned", "unclear", "gold_error");
      if (!allowed.contains(decision))
        throw new IllegalArgumentException("Неизвестное решение: " + decision);
      if (!decision.isEmpty()
          && (row.path("reviewer").asText().isBlank() || row.path("evidence").asText().isBlank()))
        throw new IllegalArgumentException("Решению нужны reviewer и evidence");
      if (row.path("automaticFailure").asBoolean() && !decision.isEmpty())
        throw new IllegalArgumentException(
            "Ошибка метода уже учитывается как некорректное оформление");
      if (!row.path("eligible").asBoolean() && !decision.isEmpty())
        throw new IllegalArgumentException("Неоднозначная запись исключена из этой оценки");
    }
    return (ObjectNode) input;
  }

  static ObjectNode summarize(ObjectNode reviewed) {
    ObjectNode result = JSON.createObjectNode();
    result.setAll(reviewed.deepCopy());
    result.remove("entries");
    ObjectNode formatting = result.putObject("formatting");
    Map<String, long[]> counts = new TreeMap<>();
    long facts = 0,
        checked = 0,
        unsupported = 0,
        pending = 0,
        unclear = 0,
        excluded = 0,
        derivedErrors = 0,
        derived = 0,
        derivedPending = 0;
    Set<String> unsupportedIds = new HashSet<>();
    for (JsonNode row : reviewed.path("entries")) {
      String decision = row.path("decision").asText();
      if (row.path("kind").asText().equals("formatting")) {
        long[] c = counts.computeIfAbsent(row.path("method").asText(), k -> new long[6]);
        if (!row.path("eligible").asBoolean()) {
          c[5]++;
          continue;
        }
        c[0]++;
        if (row.path("automaticFailure").asBoolean() || decision.equals("incorrect")) c[2]++;
        else if (decision.equals("correct")) c[1]++;
        else if (decision.equals("unclear")) c[4]++;
        else c[3]++;
      } else {
        if (!row.path("eligible").asBoolean()) {
          excluded++;
          continue;
        }
        if (row.path("derivedCategory").asBoolean()) {
          derived++;
          if (decision.equals("unsupported") || decision.equals("misassigned")) derivedErrors++;
          if (decision.isEmpty() || decision.equals("unclear")) derivedPending++;
          continue;
        }
        facts++;
        if (decision.isEmpty()) pending++;
        else if (decision.equals("unclear")) unclear++;
        else {
          checked++;
          if (decision.equals("unsupported")) {
            unsupported++;
            unsupportedIds.add(row.path("id").asText());
          }
        }
      }
    }
    counts.forEach(
        (method, c) -> {
          ObjectNode m = formatting.putObject(method);
          m.put("eligible", c[0]);
          m.put("correct", c[1]);
          m.put("incorrect", c[2]);
          m.put("pending", c[3]);
          m.put("unclear", c[4]);
          m.put("excludedAmbiguous", c[5]);
          boolean complete = c[3] + c[4] == 0;
          m.put("complete", complete);
          rate(m, "fullyCorrectFormattingRate", c[1], c[0], complete);
          rate(m, "confirmedLowerBound", c[1], c[0], true);
          rate(m, "possibleUpperBound", c[1] + c[3] + c[4], c[0], true);
        });
    ObjectNode llm = result.putObject("llmUnsupportedFacts");
    llm.put("predictedFactFields", facts);
    llm.put("reviewed", checked);
    llm.put("pending", pending);
    llm.put("unclear", unclear);
    llm.put("excludedAmbiguousFields", excluded);
    llm.put("confirmedUnsupported", unsupported);
    llm.put("recordsWithConfirmedUnsupported", unsupportedIds.size());
    boolean complete = reviewed.path("llmIncluded").asBoolean() && pending + unclear == 0;
    llm.put("available", reviewed.path("llmIncluded").asBoolean());
    llm.put("complete", complete);
    if (complete) llm.put("totalUnsupported", unsupported);
    else llm.putNull("totalUnsupported");
    rate(llm, "unsupportedRate", unsupported, facts, complete);
    llm.put("derivedCategories", derived);
    llm.put("derivedCategoryErrors", derivedErrors);
    llm.put("derivedCategoryPendingOrUnclear", derivedPending);
    result.put(
        "scope",
        "Explicit review decisions; blanks/unclear are not zero errors. Derived categories are"
            + " separate from added factual requisites. Reviewer identity is recorded, independence"
            + " is not inferred.");
    return result;
  }

  private static void rate(ObjectNode node, String key, long n, long d, boolean available) {
    if (available && d > 0) node.put(key, (double) n / d);
    else node.putNull(key);
  }

  private static String markdown(ObjectNode result) {
    StringBuilder text =
        new StringBuilder(
            "# Проверка оформления и добавлений LLM\n\nЧасть: "
                + result.path("split").asText()
                + ". Протокол: "
                + VERSION
                + ".\n\n"
                + "| Метод | Корректных | Знаменатель | Не проверено | Неясно | Итоговая доля |\n"
                + "|---|---:|---:|---:|---:|---:|\n");
    result
        .path("formatting")
        .fields()
        .forEachRemaining(
            e -> {
              JsonNode m = e.getValue();
              text.append("| ")
                  .append(e.getKey())
                  .append(" | ")
                  .append(m.path("correct"))
                  .append(" | ")
                  .append(m.path("eligible"))
                  .append(" | ")
                  .append(m.path("pending"))
                  .append(" | ")
                  .append(m.path("unclear"))
                  .append(" | ")
                  .append(
                      m.path("fullyCorrectFormattingRate").isNull()
                          ? "не определена"
                          : m.path("fullyCorrectFormattingRate"))
                  .append(" |\n");
            });
    JsonNode llm = result.path("llmUnsupportedFacts");
    text.append("\nПодтверждённые необоснованные реквизиты LLM: ")
        .append(llm.path("confirmedUnsupported"))
        .append(". Всего предсказанных оцениваемых фактических полей: ")
        .append(llm.path("predictedFactFields"))
        .append(". Не проверено: ")
        .append(llm.path("pending"))
        .append("; неясно: ")
        .append(llm.path("unclear"))
        .append(". Итоговое число: ")
        .append(
            llm.path("totalUnsupported").isNull() ? "не определено" : llm.path("totalUnsupported"))
        .append(
            ".\n\n"
                + "Пустые решения не означают правильность или отсутствие добавлений. Ошибки"
                + " категории документа/роли страниц считаются отдельно. Записи с неоднозначными"
                + " полями исключены из доли полностью корректного оформления. Статус FORMATTED не"
                + " является решением проверяющего. ")
        .append(
            result.path("split").asText().equals("DEV")
                ? "DEV — диагностическая часть, не итоговая оценка статьи.\n"
                : "TEST можно считать итоговым только после фиксации методов и протокола.\n");
    return text.toString();
  }

  private static String sha(Path path) throws IOException {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
