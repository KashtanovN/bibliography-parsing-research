package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Maps stock AnyStyle output only; no fallback rules over the original citation. */
public final class AnyStyleCitationParser implements CitationParser, AutoCloseable {
  static final String VERSION = "anystyle-json-v1";
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private final Path command;
  private final Duration timeout;
  private Process process;
  private BufferedWriter writer;
  private BufferedReader reader;
  private ExecutorService reads;
  private JsonNode initialMetadata;
  private Path stderr;

  public AnyStyleCitationParser(Path command, Duration timeout) {
    this.command = command.toAbsolutePath().normalize();
    this.timeout = timeout;
  }

  public String name() {
    return "anystyle";
  }

  public Map<String, Object> configuration() {
    try {
      return Map.of(
          "adapterVersion",
          VERSION,
          "command",
          command.toString(),
          "commandSha256",
          ExperimentSnapshot.sha(command),
          "input",
          "raw citation only",
          "fallbackExtraction",
          false);
    } catch (IOException e) {
      throw new IllegalArgumentException("Нет команды AnyStyle: " + command, e);
    }
  }

  public Map<String, Object> serverMetadata() throws IOException, InterruptedException {
    start();
    return Map.of("runtime", initialMetadata);
  }

  private void start() throws IOException, InterruptedException {
    if (process != null && process.isAlive()) return;
    close();
    stderr = Files.createTempFile("bibliography-anystyle-", ".log");
    reads =
        Executors.newSingleThreadExecutor(
            r -> {
              var t = new Thread(r, "anystyle-output");
              t.setDaemon(true);
              return t;
            });
    process = new ProcessBuilder(command.toString()).redirectError(stderr.toFile()).start();
    writer = process.outputWriter(StandardCharsets.UTF_8);
    reader = process.inputReader(StandardCharsets.UTF_8);
    String first = line();
    JsonNode metadata = JSON.readTree(first);
    if (metadata == null
        || !"anystyle-jsonl-v1".equals(metadata.path("protocol").asText())
        || metadata.path("version").asText().isBlank()
        || !metadata.path("modelSha256").asText().matches("[a-f0-9]{64}")
        || !metadata.path("workerSha256").asText().matches("[a-f0-9]{64}")
        || !metadata.path("assetsSha256").isObject()
        || metadata.path("assetsSha256").isEmpty()) {
      close();
      throw new IOException("Неполные метаданные AnyStyle");
    }
    if (initialMetadata != null && !initialMetadata.equals(metadata)) {
      close();
      throw new IOException("AnyStyle изменён во время прогона");
    }
    initialMetadata = metadata;
  }

  private String line() throws IOException, InterruptedException {
    Future<String> future = reads.submit(() -> reader.readLine());
    try {
      String text = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (text == null) {
        String error = Files.readString(stderr);
        throw new IOException(
            "AnyStyle завершился без ответа: "
                + error.substring(0, Math.min(2000, error.length())));
      }
      return text;
    } catch (TimeoutException e) {
      future.cancel(true);
      close();
      throw new IOException("Тайм-аут AnyStyle", e);
    } catch (ExecutionException e) {
      throw new IOException("Ошибка чтения AnyStyle", e.getCause());
    }
  }

  public ParsedCitation parse(ResearchDataset.ParserInput input)
      throws IOException, InterruptedException {
    start();
    writer.write(JSON.writeValueAsString(Map.of("citation", input.rawCitation())));
    writer.newLine();
    writer.flush();
    return decode(line());
  }

  ParsedCitation decode(String body) throws ParseFailure {
    try {
      JsonNode response = JSON.readTree(body);
      if (response == null || response.has("error"))
        throw new IllegalArgumentException(
            response == null ? "Пустой JSON" : response.path("error").asText());
      JsonNode values = response.path("result");
      if (!values.isArray() || values.size() != 1 || !values.get(0).isObject())
        throw new IllegalArgumentException("Ожидается одна запись AnyStyle");
      JsonNode value = values.get(0);
      var fields = new EnumMap<ReferenceField, String>(ReferenceField.class);
      for (var mapping :
          Map.ofEntries(
                  Map.entry("title", ReferenceField.TITLE),
                  Map.entry("container-title", ReferenceField.CONTAINER_TITLE),
                  Map.entry("volume", ReferenceField.VOLUME),
                  Map.entry("issue", ReferenceField.ISSUE),
                  Map.entry("pages", ReferenceField.PAGES),
                  Map.entry("publisher", ReferenceField.PUBLISHER),
                  Map.entry("location", ReferenceField.CITY),
                  Map.entry("edition", ReferenceField.EDITION),
                  Map.entry("isbn", ReferenceField.ISBN),
                  Map.entry("url", ReferenceField.URL),
                  Map.entry("accessed", ReferenceField.ACCESS_DATE),
                  Map.entry("doi", ReferenceField.DOI),
                  Map.entry("edn", ReferenceField.EDN))
              .entrySet()) put(fields, mapping.getValue(), text(value.get(mapping.getKey())));
      String date = text(value.get("date"));
      Matcher year = Pattern.compile("(?<!\\d)(?:1[5-9]\\d{2}|20\\d{2})(?!\\d)").matcher(date);
      if (year.find()) put(fields, ReferenceField.YEAR, year.group());
      List<String> authors = new ArrayList<>();
      if (value.hasNonNull("author")) {
        if (!value.get("author").isArray())
          throw new IllegalArgumentException("author должен быть массивом");
        for (JsonNode author : value.get("author")) {
          if (!author.isObject()) throw new IllegalArgumentException("Неверный автор AnyStyle");
          String literal = text(author.get("literal"));
          if (!literal.isBlank()) {
            authors.add(literal);
            continue;
          }
          String family = text(author.get("family")), given = text(author.get("given"));
          Matcher words = Pattern.compile("\\p{L}+").matcher(given);
          List<String> initials = new ArrayList<>();
          while (words.find())
            initials.add(new String(Character.toChars(words.group().codePointAt(0))) + ".");
          String name = (family + " " + String.join(" ", initials)).strip();
          if (!name.isBlank()) authors.add(name);
        }
      }
      put(fields, ReferenceField.AUTHORS, String.join(" | ", authors));
      String type =
          switch (text(value.get("type"))) {
            case "article-journal", "article-magazine", "article-newspaper" -> "Журнальная статья";
            case "chapter", "paper-conference" -> "Материал сборника";
            case "book" -> "Книга";
            case "thesis" -> "Диссертация";
            case "webpage", "post-weblog" -> "Сайт";
            case "legislation", "legal_case", "regulation", "standard" -> "Нормативный документ";
            default -> "";
          };
      put(fields, ReferenceField.DOCUMENT_TYPE, type);
      if (fields.containsKey(ReferenceField.PAGES))
        put(
            fields,
            ReferenceField.PAGE_MEANING,
            type.equals("Книга") && fields.get(ReferenceField.PAGES).matches("\\d+")
                ? "Общий объём"
                : "Указатель страниц");
      return new ParsedCitation(
          fields,
          List.of(
              "Тип и роль страниц выведены из ответа AnyStyle; note/editor не превращаются в"
                  + " авторов или EDN; поля не восстанавливаются из входа"),
          body);
    } catch (IOException | IllegalArgumentException e) {
      throw new ParseFailure("Некорректный ответ AnyStyle: " + e.getMessage(), body);
    }
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull()) return "";
    if (node.isTextual()) return node.asText().strip();
    if (node.isArray()) {
      List<String> values = new ArrayList<>();
      for (var part : node) {
        if (!part.isTextual())
          throw new IllegalArgumentException("Значение AnyStyle должно быть строкой");
        if (!part.asText().isBlank()) values.add(part.asText().strip());
      }
      return String.join(" ", values);
    }
    throw new IllegalArgumentException("Неподдерживаемый тип значения AnyStyle");
  }

  private static void put(Map<ReferenceField, String> fields, ReferenceField key, String value) {
    if (!value.isBlank()) fields.put(key, value);
  }

  public void close() {
    if (process != null) process.destroyForcibly();
    if (reads != null) reads.shutdownNow();
    if (stderr != null)
      try {
        Files.deleteIfExists(stderr);
      } catch (IOException ignored) {
      }
    process = null;
  }
}
