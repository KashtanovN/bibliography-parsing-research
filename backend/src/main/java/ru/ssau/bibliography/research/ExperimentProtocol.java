package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import ru.ssau.bibliography.SharedCitationFormatter;

/** A DEV-derived frozen protocol, checked before TEST requests and again during evaluation. */
public final class ExperimentProtocol {
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  static final String VERSION = "experiment-protocol-v1";
  private static final Set<String> LEGACY = Set.of("rules", "grobid", "ollama");
  private static final Set<String> ALL = Set.of("rules", "grobid", "ollama", "anystyle");

  private ExperimentProtocol() {}

  public static void run(String[] args) throws IOException {
    Map<String, String> options = new HashMap<>();
    for (String arg : args) {
      int p = arg.indexOf('=');
      if (!arg.startsWith("--") || p < 3 || p == arg.length() - 1)
        throw new IllegalArgumentException("Ожидается --имя=значение");
      String key = arg.substring(2, p);
      if (!Set.of(
                  "freeze-experiment",
                  "dataset",
                  "protocol-output",
                  "policy-directory",
                  "timeout-seconds")
              .contains(key)
          || options.putIfAbsent(key, arg.substring(p + 1)) != null)
        throw new IllegalArgumentException("Неизвестный или повторный параметр: " + key);
    }
    if (!options.containsKey("freeze-experiment") || !options.containsKey("dataset"))
      throw new IllegalArgumentException("Нужны --freeze-experiment и --dataset");
    Path run = Path.of(options.get("freeze-experiment"));
    Path datasetPath = Path.of(options.get("dataset")).toAbsolutePath();
    var saved = SavedResearchRun.read(run, datasetPath);
    if (saved.split() != ResearchDataset.Split.DEV
        || saved.references().size() != saved.dataset().references(ResearchDataset.Split.DEV).size()
        || !(saved.predictions().keySet().equals(ALL)
            || saved.predictions().keySet().equals(LEGACY)
            || saved.predictions().keySet().equals(Set.of("anystyle"))))
      throw new IllegalArgumentException(
          "Нужен полный DEV-прогон четырёх методов (либо архивных трёх или одного AnyStyle)");
    if (!Files.isRegularFile(run.resolve("summary.json")))
      throw new IllegalArgumentException("DEV-прогон не завершён: нет summary.json");
    if (!saved.manifest().path("setupWarnings").isArray()
        || !saved.manifest().path("setupWarnings").isEmpty())
      throw new IllegalArgumentException("Не все метаданные DEV доступны");
    JsonNode methods = saved.manifest().get("methods");
    requireMetadata(methods);
    int timeout;
    try {
      timeout = Integer.parseInt(options.getOrDefault("timeout-seconds", "180"));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Неверный тайм-аут");
    }
    if (timeout < 1) throw new IllegalArgumentException("Тайм-аут должен быть положительным");
    Duration duration = Duration.ofSeconds(timeout);
    boolean supplemental = saved.predictions().keySet().equals(Set.of("anystyle"));
    List<CitationParser> current = new ArrayList<>(
        supplemental
            ? List.of(
                new AnyStyleCitationParser(
                    Path.of(methods.path("anystyle").path("command").asText()), duration))
            : List.of(
                new RulesCitationParser(),
                new GrobidCitationParser(
                    URI.create(methods.path("grobid").path("endpoint").asText()), duration),
                new OllamaCitationParser(
                    URI.create(methods.path("ollama").path("endpoint").asText()),
                    methods.path("ollama").path("model").asText(),
                    duration)));
    if (!supplemental && methods.has("anystyle"))
      current.add(new AnyStyleCitationParser(
          Path.of(methods.path("anystyle").path("command").asText()), duration));
    for (var parser : current) {
      if (parser instanceof AnyStyleCitationParser anystyle) {
        try (anystyle) {
          if (!JSON.valueToTree(anystyle.serverMetadata().get("runtime"))
              .equals(methods.path("anystyle").path("runtime")))
            throw new IllegalArgumentException("AnyStyle/модель изменены после DEV");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Проверка AnyStyle прервана", e);
        }
      }
      JsonNode config = JSON.valueToTree(parser.configuration());
      var names = config.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (!config.get(name).equals(methods.path(parser.name()).get(name)))
          throw new IllegalArgumentException(
              "Настройки изменены после DEV: " + parser.name() + "/" + name);
      }
    }
    Path policies =
        Path.of(
            options.getOrDefault(
                "policy-directory", datasetPath.getParent().resolve("docs").toString()));
    Map<String, String> policyHashes = new TreeMap<>();
    for (String name : List.of("QUALITY_REVIEW.md", "EVALUATION.md", "FORMATTING.md"))
      policyHashes.put(name, ExperimentSnapshot.sha(policies.resolve(name)));
    ObjectNode protocol = JSON.createObjectNode();
    protocol.put("version", VERSION);
    if (supplemental)
      protocol.put(
          "scope",
          "Supplemental AnyStyle comparison added after the initial three-method TEST; not a"
              + " preregistration of that earlier comparison");
    protocol.put("frozenAt", Instant.now().toString());
    protocol.put("datasetSha256", saved.dataset().sha256());
    protocol.put("devRecords", saved.references().size());
    protocol.put("testRecords", saved.dataset().references(ResearchDataset.Split.TEST).size());
    protocol.put("normalizationVersion", FieldNormalization.VERSION);
    protocol.put("formatterVersion", SharedCitationFormatter.VERSION);
    protocol.put("reviewVersion", QualityReviewCommand.VERSION);
    protocol.put("timeoutSeconds", timeout);
    protocol.put("devTimeoutRecorded", saved.manifest().has("timeoutSeconds"));
    if (saved.manifest().has("timeoutSeconds")
        && saved.manifest().path("timeoutSeconds").asInt() != timeout)
      throw new IllegalArgumentException("Тайм-аут отличается от DEV");
    protocol.set("methods", methods.deepCopy());
    protocol.set("implementation", JSON.valueToTree(ExperimentSnapshot.implementation()));
    protocol.set("freezeEnvironment", JSON.valueToTree(ExperimentSnapshot.environment()));
    protocol.set("policies", JSON.valueToTree(policyHashes));
    ObjectNode evidence = protocol.putObject("devArtifacts");
    evidence.put("run.json", ExperimentSnapshot.sha(run.resolve("run.json")));
    evidence.put("summary.json", ExperimentSnapshot.sha(run.resolve("summary.json")));
    for (String method : new TreeSet<>(saved.predictions().keySet()))
      evidence.put(method + ".jsonl", ExperimentSnapshot.sha(run.resolve(method + ".jsonl")));
    protocol.put("devImplementationRecorded", saved.manifest().has("implementation"));
    Path executable = executable();
    if (executable != null) protocol.put("executableSha256", ExperimentSnapshot.sha(executable));
    Path output =
        Path.of(options.getOrDefault("protocol-output", run.resolve("frozen-protocol").toString()));
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.createDirectory(output);
    for (String name : policyHashes.keySet())
      Files.copy(policies.resolve(name), output.resolve(name));
    if (executable != null) Files.copy(executable, output.resolve("application.jar"));
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("protocol.json").toFile(), protocol);
    String hash = ExperimentSnapshot.sha(output.resolve("protocol.json"));
    Files.writeString(output.resolve("protocol.sha256"), hash + "\n");
    System.out.println(
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "output",
                    output.toAbsolutePath().normalize().toString(),
                    "protocolSha256",
                    hash,
                    "testRecords",
                    protocol.path("testRecords").asInt())));
  }

  static JsonNode read(Path directory) throws IOException {
    String actual = ExperimentSnapshot.sha(directory.resolve("protocol.json"));
    if (!actual.equals(Files.readString(directory.resolve("protocol.sha256")).strip()))
      throw new IllegalArgumentException("Контрольная сумма протокола не совпадает");
    JsonNode protocol = JSON.readTree(directory.resolve("protocol.json").toFile());
    if (protocol == null
        || !VERSION.equals(protocol.path("version").asText())
        || !protocol.path("policies").isObject()
        || protocol.path("policies").size() != 3)
      throw new IllegalArgumentException("Неизвестный или неполный протокол");
    for (String name : List.of("QUALITY_REVIEW.md", "EVALUATION.md", "FORMATTING.md"))
      if (!ExperimentSnapshot.sha(directory.resolve(name))
          .equals(protocol.path("policies").path(name).asText()))
        throw new IllegalArgumentException("Изменены критерии: " + name);
    if (protocol.has("executableSha256")
        && !ExperimentSnapshot.sha(directory.resolve("application.jar"))
            .equals(protocol.path("executableSha256").asText()))
      throw new IllegalArgumentException("Изменён сохранённый JAR");
    return protocol;
  }

  static void validate(
      JsonNode protocol, String datasetSha, JsonNode methods, int records, int timeout)
      throws IOException {
    if (!datasetSha.equals(protocol.path("datasetSha256").asText())
        || records != protocol.path("testRecords").asInt(-1)
        || timeout != protocol.path("timeoutSeconds").asInt(-1))
      throw new IllegalArgumentException(
          "Датасет, размер TEST или тайм-аут не совпадают с протоколом");
    if (!FieldNormalization.VERSION.equals(protocol.path("normalizationVersion").asText())
        || !JSON.valueToTree(ExperimentSnapshot.implementation())
            .equals(protocol.get("implementation")))
      throw new IllegalArgumentException("Код/оценщик изменён после фиксации протокола");
    requireMetadata(methods);
    requireMetadata(protocol.path("methods"));
    if (!withoutEndpoints(methods).equals(withoutEndpoints(protocol.path("methods"))))
      throw new IllegalArgumentException(
          "Методы, модель, промпт, версия сервиса или настройки отличаются от протокола");
  }

  private static void requireMetadata(JsonNode methods) {
    Set<String> names = new HashSet<>();
    methods.fieldNames().forEachRemaining(names::add);
    if (methods.isObject() && names.contains("anystyle")) {
      JsonNode runtime = methods.path("anystyle").path("runtime");
      if (!runtime.path("modelSha256").asText().matches("[a-f0-9]{64}")
          || !runtime.path("workerSha256").asText().matches("[a-f0-9]{64}")
          || runtime.path("version").asText().isBlank()
          || !runtime.path("assetsSha256").isObject()
          || runtime.path("assetsSha256").isEmpty())
        throw new IllegalArgumentException("Нужны подтверждённые версии и модель AnyStyle");
      if (names.equals(Set.of("anystyle"))) return;
    }
    if (!methods.isObject()
        || !(names.equals(ALL) || names.equals(LEGACY))
        || methods.path("grobid").path("serverVersion").asText().isBlank()
        || methods.path("ollama").path("modelDigest").asText().isBlank()
        || methods.path("ollama").path("ollamaVersion").asText().isBlank())
      throw new IllegalArgumentException(
          "Нужен полный набор методов и подтверждённые версии GROBID/Ollama/модели");
  }

  private static ObjectNode withoutEndpoints(JsonNode methods) {
    ObjectNode copy = ((ObjectNode) methods).deepCopy();
    copy.elements()
        .forEachRemaining(
            node -> {
              if (node.isObject()) ((ObjectNode) node).remove("endpoint");
            });
    return copy;
  }

  static String copy(Path source, Path output) throws IOException {
    read(source);
    Files.createDirectory(output);
    for (String name :
        List.of(
            "protocol.json",
            "protocol.sha256",
            "QUALITY_REVIEW.md",
            "EVALUATION.md",
            "FORMATTING.md")) Files.copy(source.resolve(name), output.resolve(name));
    if (Files.isRegularFile(source.resolve("application.jar")))
      Files.copy(source.resolve("application.jar"), output.resolve("application.jar"));
    return ExperimentSnapshot.sha(output.resolve("protocol.json"));
  }

  private static Path executable() throws IOException {
    String[] args = ProcessHandle.current().info().arguments().orElse(new String[0]);
    for (int i = 0; i + 1 < args.length; i++) {
      if (!args[i].equals("-jar")) continue;
      Path path = Path.of(args[i + 1]).toAbsolutePath();
      if (!Files.isRegularFile(path)) continue;
      try (var jar = new java.util.jar.JarFile(path.toFile())) {
        if (jar.getEntry("BOOT-INF/classes/ru/ssau/bibliography/BibliographyApplication.class")
            != null) return path;
      }
    }
    return null; // IDE/tests are identified by loaded classes, not an unrelated launcher JAR.
  }
}
