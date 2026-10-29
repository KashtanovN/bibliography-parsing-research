package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import ru.ssau.bibliography.research.ResearchDataset.*;

/** Runs extraction and records predictions only. It does not calculate quality metrics. */
public final class ParseReferencesCommand {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> OPTIONS =
      Set.of(
          "parse-text",
          "parse-dataset",
          "method",
          "split",
          "limit",
          "output",
          "grobid-url",
          "ollama-url",
          "model",
          "timeout-seconds",
          "anystyle-command",
          "protocol");

  private ParseReferencesCommand() {}

  public static int run(String[] args) throws IOException, InterruptedException {
    Map<String, String> options = options(args);
    boolean datasetInput = options.containsKey("parse-dataset");
    if (datasetInput == options.containsKey("parse-text"))
      throw new IllegalArgumentException(
          "Выберите один вход: --parse-dataset=... или --parse-text=...");
    List<ParserInput> inputs;
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("createdAt", Instant.now().toString());
    metadata.put("purpose", "raw-extraction-predictions; not quality metrics");
    metadata.put("schemaVersion", "experiment-run-v2");
    metadata.put("executionState", "STARTED");
    boolean finalTest = false;
    if (datasetInput) {
      ResearchDataset data = new ExcelDatasetLoader().load(Path.of(options.get("parse-dataset")));
      Split split;
      try {
        split = Split.valueOf(options.getOrDefault("split", "dev").toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("--split должен быть dev или test");
      }
      inputs = data.parserInputs(split);
      metadata.put("datasetSha256", data.sha256());
      metadata.put("split", split.name().toLowerCase(Locale.ROOT));
      metadata.put("splitSize", inputs.size());
      finalTest = split == Split.TEST;
    } else {
      if (options.containsKey("split"))
        throw new IllegalArgumentException("--split применяется только к датасету");
      inputs = List.of(new ParserInput("manual-1", options.get("parse-text")));
      metadata.put("split", "manual");
    }
    if (finalTest
        && (!options.containsKey("protocol")
            || !Set.of("all", "anystyle").contains(options.getOrDefault("method", "rules"))
            || options.containsKey("limit")))
      throw new IllegalArgumentException(
          "Для TEST нужны --method=all или anystyle и --protocol=ПАПКА, без --limit; настройка"
              + " проводится на DEV");
    if (!finalTest && options.containsKey("protocol"))
      throw new IllegalArgumentException("--protocol применяется только к итоговому TEST");
    int limit = positive(options.getOrDefault("limit", Integer.toString(inputs.size())), "limit");
    inputs = inputs.stream().limit(limit).toList();
    Duration timeout =
        Duration.ofSeconds(
            positive(options.getOrDefault("timeout-seconds", "180"), "timeout-seconds"));
    List<CitationParser> methods = methods(options, timeout);
    try {
      Path destination =
          Path.of(
              options.getOrDefault(
                  "output", "../research-runs/" + Instant.now().toString().replace(':', '-')));
      Path parent = destination.toAbsolutePath().getParent();
      metadata.put("records", inputs.size());
      metadata.put("javaVersion", System.getProperty("java.version"));
      metadata.put("environment", ExperimentSnapshot.environment());
      metadata.put("implementation", ExperimentSnapshot.implementation());
      metadata.put("timeoutSeconds", timeout.toSeconds());
      metadata.put("arguments", List.of(args));
      Map<String, Object> configurations = new LinkedHashMap<>();
      List<String> setupWarnings = new ArrayList<>();
      for (CitationParser parser : methods) {
        Map<String, Object> config = new LinkedHashMap<>(parser.configuration());
        try {
          if (parser instanceof GrobidCitationParser grobid) config.putAll(grobid.serverMetadata());
          if (parser instanceof OllamaCitationParser ollama) config.putAll(ollama.serverMetadata());
          if (parser instanceof AnyStyleCitationParser anystyle)
            config.putAll(anystyle.serverMetadata());
        } catch (IOException e) {
          setupWarnings.add(parser.name() + ": метаданные сервера недоступны: " + errorMessage(e));
        }
        configurations.put(parser.name(), config);
      }
      metadata.put("methods", configurations);
      metadata.put("setupWarnings", setupWarnings);
      if (finalTest) {
        if (!setupWarnings.isEmpty())
          throw new IllegalArgumentException("Для TEST не удалось подтвердить версии сервисов");
        var protocol = ExperimentProtocol.read(Path.of(options.get("protocol")));
        ExperimentProtocol.validate(
            protocol,
            (String) metadata.get("datasetSha256"),
            JSON.valueToTree(configurations),
            inputs.size(),
            Math.toIntExact(timeout.toSeconds()));
      }
      Files.createDirectories(parent);
      Files.createDirectory(destination); // Never overwrite a previous run.
      if (finalTest)
        metadata.put(
            "protocolSha256",
            ExperimentProtocol.copy(
                Path.of(options.get("protocol")), destination.resolve("protocol")));
      JSON.writerWithDefaultPrettyPrinter()
          .writeValue(destination.resolve("run.json").toFile(), metadata);
      Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
      int failures = 0;
      for (CitationParser parser : methods) {
        Map<String, Integer> statuses = new LinkedHashMap<>();
        int sequence = 0;
        try (var writer =
            Files.newBufferedWriter(
                destination.resolve(parser.name() + ".jsonl"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW)) {
          for (ParserInput input : inputs) {
            long start = System.nanoTime();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", input.id());
            result.put("method", parser.name());
            result.put("rawCitation", input.rawCitation());
            result.put("sequence", ++sequence);
            result.put("startedAt", Instant.now().toString());
            String status;
            try {
              var parsed = parser.parse(input);
              status = parsed.fields().isEmpty() ? "EMPTY" : "SUCCESS";
              result.put("fields", parsed.fields());
              result.put("warnings", parsed.warnings());
              result.put("rawResponse", parsed.rawResponse());
            } catch (IOException e) {
              status = "ERROR";
              failures++;
              result.put("fields", Map.of());
              result.put("error", errorMessage(e));
              if (e instanceof CitationParser.ParseFailure failure)
                result.put("rawResponse", failure.rawResponse());
            }
            result.put("status", status);
            result.put("finishedAt", Instant.now().toString());
            result.put("durationMillis", (System.nanoTime() - start) / 1_000_000L);
            statuses.merge(status, 1, Integer::sum);
            writer.write(JSON.writeValueAsString(result));
            writer.newLine();
            writer.flush();
          }
        }
        counts.put(parser.name(), statuses);
      }
      metadata.put("executionState", "COMPLETED");
      metadata.put("finishedAt", Instant.now().toString());
      JSON.writerWithDefaultPrettyPrinter()
          .writeValue(destination.resolve("run.json").toFile(), metadata);
      Map<String, Object> summary =
          Map.of(
              "output",
              destination.toAbsolutePath().normalize().toString(),
              "records",
              inputs.size(),
              "statuses",
              counts);
      JSON.writerWithDefaultPrettyPrinter()
          .writeValue(destination.resolve("summary.json").toFile(), summary);
      System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
      return failures == 0 ? 0 : 3;
    } finally {
      for (CitationParser parser : methods)
        if (parser instanceof AnyStyleCitationParser anystyle) anystyle.close();
    }
  }

  private static int positive(String text, String name) {
    try {
      int value = Integer.parseInt(text);
      if (value > 0) return value;
    } catch (NumberFormatException ignored) {
    }
    throw new IllegalArgumentException("--" + name + " должен быть положительным целым числом");
  }

  private static String errorMessage(IOException error) {
    return error.getClass().getSimpleName()
        + (error.getMessage() == null ? "" : ": " + error.getMessage());
  }

  private static Map<String, String> options(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (String arg : args) {
      int equals = arg.indexOf('=');
      if (!arg.startsWith("--") || equals < 3 || equals == arg.length() - 1)
        throw new IllegalArgumentException("Ожидается --имя=значение: " + arg);
      String name = arg.substring(2, equals), value = arg.substring(equals + 1);
      if (!OPTIONS.contains(name))
        throw new IllegalArgumentException("Неизвестный параметр: --" + name);
      if (options.putIfAbsent(name, value) != null)
        throw new IllegalArgumentException("Повтор параметра: --" + name);
    }
    return options;
  }

  private static List<CitationParser> methods(Map<String, String> options, Duration timeout) {
    String method = options.getOrDefault("method", "rules");
    return switch (method) {
      case "rules" -> List.of(new RulesCitationParser());
      case "anystyle" ->
          List.of(
              new AnyStyleCitationParser(
                  Path.of(
                      options.getOrDefault("anystyle-command", "../scripts/anystyle-worker.sh")),
                  timeout));
      case "grobid" ->
          List.of(
              new GrobidCitationParser(
                  URI.create(options.getOrDefault("grobid-url", "http://127.0.0.1:8070")),
                  timeout));
      case "ollama" ->
          List.of(
              new OllamaCitationParser(
                  URI.create(options.getOrDefault("ollama-url", "http://127.0.0.1:11434")),
                  options.getOrDefault("model", "qwen3:8b"),
                  timeout));
      case "all" ->
          List.of(
              new RulesCitationParser(),
              new GrobidCitationParser(
                  URI.create(options.getOrDefault("grobid-url", "http://127.0.0.1:8070")), timeout),
              new OllamaCitationParser(
                  URI.create(options.getOrDefault("ollama-url", "http://127.0.0.1:11434")),
                  options.getOrDefault("model", "qwen3:8b"),
                  timeout),
              new AnyStyleCitationParser(
                  Path.of(options.getOrDefault("anystyle-command", "../scripts/anystyle-worker.sh")),
                  timeout));
      default ->
          throw new IllegalArgumentException(
              "--method должен быть rules, grobid, ollama, anystyle или all (все четыре метода)");
    };
  }
}
