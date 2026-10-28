package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import ru.ssau.bibliography.SharedCitationFormatter;
import ru.ssau.bibliography.SharedCitationFormatter.*;

public final class FormatRunCommand {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private FormatRunCommand() {}

  public static void run(String[] args) throws IOException {
    Map<String, String> options = new HashMap<>();
    for (String arg : args) {
      int p = arg.indexOf('=');
      if (!arg.startsWith("--") || p < 3 || p == arg.length() - 1)
        throw new IllegalArgumentException("Ожидается --имя=значение");
      String key = arg.substring(2, p);
      if (!Set.of("format-run", "dataset", "format-output").contains(key)
          || options.putIfAbsent(key, arg.substring(p + 1)) != null)
        throw new IllegalArgumentException("Неизвестный или повторный параметр: " + key);
    }
    if (!options.containsKey("format-run") || !options.containsKey("dataset"))
      throw new IllegalArgumentException("Нужны --format-run и --dataset");
    Path run = Path.of(options.get("format-run"));
    var loaded = SavedResearchRun.read(run, Path.of(options.get("dataset")));
    Map<String, Context> contexts = new LinkedHashMap<>();
    // Only the three declared context columns are projected. expectedFields/expectedCitation are
    // never used.
    for (var ref : loaded.references())
      contexts.put(
          ref.input().id(),
          new Context(
              Profile.parse(ref.formatting().standard()),
              ref.formatting().selectedUrl(),
              ref.formatting().accessDate()));
    String contextHash;
    try {
      contextHash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(contexts)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    Path output =
        Path.of(options.getOrDefault("format-output", run.resolve("formatting").toString()));
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.createDirectory(output);
    var formatter = new SharedCitationFormatter(Clock.system(ZoneId.of("Europe/Samara")));
    List<List<String>> csv = new ArrayList<>();
    csv.add(
        List.of(
            "method", "id", "source_status", "format_status", "profile", "formatted", "issues"));
    Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
    StringBuilder preview =
        new StringBuilder(
            "# Единое оформление ответов\n\nЧасть: "
                + loaded.split()
                + ", записей: "
                + contexts.size()
                + ". Версия: "
                + SharedCitationFormatter.VERSION
                + ".\n\n"
                + "Все методы получили одинаковый контекст для каждого ID. Статус FORMATTED"
                + " означает завершение принятого алгоритма, а не независимое подтверждение"
                + " ГОСТ-корректности. NEEDS_REVIEW отмечает неполные или неоднозначные данные."
                + " Нормативная доля корректных ссылок здесь не вычисляется.\n");
    for (var entry : loaded.predictions().entrySet()) {
      String method = entry.getKey();
      Map<String, Integer> statuses = new LinkedHashMap<>();
      int shown = 0;
      preview.append("\n## ").append(method).append('\n');
      try (var writer =
          Files.newBufferedWriter(
              output.resolve(method + ".jsonl"),
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE_NEW)) {
        for (var prediction : entry.getValue()) {
          Context context = contexts.get(prediction.id());
          var result = new LinkedHashMap<String, Object>();
          result.put("id", prediction.id());
          result.put("method", method);
          result.put("sourceStatus", prediction.status());
          result.put("context", context);
          result.put("predictedFields", prediction.fields());
          String status, text = "", issues = "";
          if (!prediction.status().equals("SUCCESS")) {
            status = "SKIPPED_SOURCE_" + prediction.status();
            result.put("status", status);
          } else {
            var formatted = formatter.format(prediction.fields(), context);
            status = formatted.status();
            text = formatted.formatted();
            issues = JSON.writeValueAsString(formatted.issues());
            result.put("status", status);
            result.put("formatting", formatted);
          }
          statuses.merge(status, 1, Integer::sum);
          writer.write(JSON.writeValueAsString(result));
          writer.newLine();
          csv.add(
              List.of(
                  method,
                  prediction.id(),
                  prediction.status(),
                  status,
                  context.profile().id(),
                  text,
                  issues));
          if (shown++ < 5)
            preview
                .append("\n**")
                .append(prediction.id())
                .append(" — ")
                .append(status)
                .append("**\n\n")
                .append(text)
                .append("\n");
        }
      }
      counts.put(method, statuses);
    }
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("createdAt", Instant.now().toString());
    metadata.put("formatterVersion", SharedCitationFormatter.VERSION);
    metadata.put("datasetSha256", loaded.dataset().sha256());
    metadata.put("split", loaded.split().name());
    metadata.put("records", contexts.size());
    metadata.put("contextSha256", contextHash);
    metadata.put("contexts", contexts);
    metadata.put("sourceRun", run.toAbsolutePath().normalize().toString());
    metadata.put("statuses", counts);
    metadata.put(
        "scope",
        "Shared formatting of predicted fields only; algorithm completion is not normative"
            + " correctness");
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("formatting.json").toFile(), metadata);
    try (var writer =
        Files.newBufferedWriter(output.resolve("references.csv"), StandardCharsets.UTF_8)) {
      for (var row : csv) {
        writer.write(
            row.stream()
                .map(s -> "\"" + s.replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",")));
        writer.newLine();
      }
    }
    Files.writeString(output.resolve("preview.md"), preview.toString(), StandardCharsets.UTF_8);
    System.out.println(
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "output",
                    output.toAbsolutePath().normalize().toString(),
                    "records",
                    contexts.size(),
                    "contextSha256",
                    contextHash,
                    "statuses",
                    counts)));
  }
}
