package ru.ssau.bibliography;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class CitationFormatter {
  private final Clock clock;
  private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");

  public CitationFormatter(Clock clock) {
    this.clock = clock;
  }

  public static void validateSchema(JsonNode schema) {
    if (schema == null || !schema.isObject())
      throw new IllegalArgumentException("Standard must be an object");
    for (String key : List.of("id", "label", "version")) {
      int limit = key.equals("id") ? 80 : key.equals("version") ? 40 : 200;
      if (!schema.path(key).isTextual()
          || schema.get(key).asText().isBlank()
          || schema.get(key).asText().length() > limit)
        throw new IllegalArgumentException("Invalid " + key);
    }
    if (!schema.path("types").isObject() || schema.get("types").isEmpty())
      throw new IllegalArgumentException("No source types");
    for (JsonNode config : schema.get("types")) {
      Set<String> names = new HashSet<>();
      if (!config.path("fields").isArray() || config.get("fields").isEmpty())
        throw new IllegalArgumentException("No fields");
      for (JsonNode field : config.get("fields")) {
        if (!field.path("name").isTextual()
            || field.get("name").asText().isBlank()
            || !names.add(field.get("name").asText()))
          throw new IllegalArgumentException("Invalid or duplicate field");
        if (!Set.of("text", "number", "date", "url", "select")
            .contains(field.path("type").asText()))
          throw new IllegalArgumentException("Invalid field type");
        if (field.path("type").asText().equals("select") && !field.path("options").isArray())
          throw new IllegalArgumentException("Select must define options");
      }
      if (!config.path("parts").isArray() || config.get("parts").isEmpty())
        throw new IllegalArgumentException("No formatting blocks");
      for (JsonNode part : config.get("parts")) {
        if (!part.path("fields").isArray())
          throw new IllegalArgumentException("Invalid formatting fields");
        for (String key : List.of("fields", "require")) {
          if (part.has(key)) {
            if (!part.get(key).isArray()) throw new IllegalArgumentException("Invalid " + key);
            for (JsonNode name : part.get(key))
              if (!name.isTextual() || !names.contains(name.asText()))
                throw new IllegalArgumentException("Unknown formatting field");
          }
        }
        for (String key : List.of("prefix", "suffix", "join"))
          if (part.has(key) && !part.get(key).isTextual())
            throw new IllegalArgumentException("Invalid block text");
      }
    }
  }

  public static String normalizeDoi(String value) {
    value =
        value
            .strip()
            .replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/", "")
            .replaceFirst("(?i)^doi\\s*:\\s*", "");
    if (!value.matches("(?U)10\\.[0-9]{4,9}/[^\\s]+"))
      throw new IllegalArgumentException("Укажите DOI вида 10.1038/nphys1170");
    return value;
  }

  public static String normalizeIsbn(String value) {
    value =
        value
            .strip()
            .replaceFirst("(?i)^ISBN(?:-1[03])?\\s*:?\\s*", "")
            .replaceAll("(?U)[\\s-]", "")
            .toUpperCase(Locale.ROOT);
    boolean valid = false;
    if (value.matches("[0-9]{9}[0-9X]")) {
      int sum = 0;
      for (int i = 0; i < 10; i++)
        sum += (10 - i) * (value.charAt(i) == 'X' ? 10 : value.charAt(i) - '0');
      valid = sum % 11 == 0;
    } else if (value.matches("97[89][0-9]{10}")) {
      int sum = 0;
      for (int i = 0; i < 13; i++) sum += (i % 2 == 0 ? 1 : 3) * (value.charAt(i) - '0');
      valid = sum % 10 == 0;
    }
    if (!valid)
      throw new IllegalArgumentException(
          "Проверьте ISBN: нужны 10 или 13 символов и верная контрольная цифра");
    return value;
  }

  public static String normalizeAuthors(String value) {
    value =
        value
            .replaceAll("(?i)\\s+and\\s+", "; ")
            .replaceAll("([А-ЯЁA-Z])\\.(?=[А-ЯЁA-Z]\\.)", "$1. ")
            .replaceAll("(?<=[А-ЯЁA-Z])\\s*,\\s*(?=[А-ЯЁA-Z](?:\\.|,))", ". ")
            .replaceAll("(?<=[А-ЯЁA-Z])\\s*,(?=\\s*(?:;|$))", ".")
            .replaceAll(",\\s*(?=[А-ЯЁA-Z][а-яёa-z\\-']+\\s)", "; ")
            .replaceAll("(?<=\\.)\\s+(?=[А-ЯЁA-Z][а-яёa-z\\-']+\\s+[А-ЯЁA-Z]\\.)", "; ");
    var full =
        Pattern.compile(
            "([А-ЯЁA-Z][а-яёa-z'’-]+)\\s+([А-ЯЁA-Z][а-яёa-z]+)(?:\\s+([А-ЯЁA-Z][а-яёa-z]+))?");
    List<String> authors = new ArrayList<>();
    for (String item : value.split("\\s*;\\s*")) {
      String author = item.strip();
      var match = full.matcher(author);
      if (match.matches() && Pattern.compile("[А-Яа-яЁё]").matcher(author).find())
        author =
            match.group(1)
                + " "
                + match.group(2).charAt(0)
                + "."
                + (match.group(3) == null ? "" : " " + match.group(3).charAt(0) + ".");
      if (!author.isEmpty()) authors.add(author);
    }
    return String.join(", ", authors);
  }

  public Map<String, String> cleanValues(JsonNode config, JsonNode values) {
    if (values == null || !values.isObject())
      throw new ApiProblem(400, Map.of("fields", "Ожидается объект с полями источника"));
    Set<String> names = new HashSet<>();
    config.get("fields").forEach(f -> names.add(f.get("name").asText()));
    List<String> unknown = new ArrayList<>();
    values
        .fieldNames()
        .forEachRemaining(
            n -> {
              if (!names.contains(n)) unknown.add(n);
            });
    if (!unknown.isEmpty()) {
      Collections.sort(unknown);
      throw new ApiProblem(
          400, Map.of("fields", "Неизвестные поля: " + String.join(", ", unknown)));
    }
    Map<String, String> errors = new LinkedHashMap<>(), clean = new LinkedHashMap<>();
    for (JsonNode field : config.get("fields")) {
      String name = field.get("name").asText(), type = field.get("type").asText();
      JsonNode raw = values.get(name);
      if (raw != null && !raw.isTextual() && !raw.isIntegralNumber()) {
        errors.put(name, "Введите текстовое значение");
        continue;
      }
      String value = raw == null ? "" : raw.asText().replaceAll("(?U)\\s+", " ").strip();
      if (value.isEmpty()) {
        if (field.path("required").asBoolean()) errors.put(name, "Заполните обязательное поле");
        clean.put(name, "");
        continue;
      }
      if (value.codePointCount(0, value.length()) > 2000) {
        errors.put(name, "Не более 2000 символов");
        continue;
      }
      try {
        if (name.equals("year")) {
          if (!value.matches("[0-9]{4}")
              || Integer.parseInt(value) < 1000
              || Integer.parseInt(value) > LocalDate.now(clock).getYear() + 5)
            throw new IllegalArgumentException("Введите четырёхзначный год издания");
        } else if (type.equals("number")) {
          if (!value.matches("[0-9]+")
              || new BigInteger(value).signum() < 1
              || new BigInteger(value).compareTo(BigInteger.valueOf(100000)) > 0)
            throw new IllegalArgumentException("Введите положительное целое число");
        } else if (type.equals("date")) {
          if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
          LocalDate parsed = LocalDate.parse(value);
          if (name.equals("accessDate") && parsed.isAfter(LocalDate.now(clock)))
            throw new IllegalArgumentException();
          value = parsed.format(DISPLAY_DATE);
        } else if (type.equals("url")) {
          URI uri;
          try {
            uri = URI.create(value);
          } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Введите полный URL с https:// или http://");
          }
          // URI#getHost rejects Unicode domain names; the authority is also checked explicitly.
          if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
              || uri.getRawAuthority() == null
              || uri.getRawAuthority().isBlank()
              || uri.getRawAuthority().contains("@")
              || uri.getRawAuthority().startsWith(":"))
            throw new IllegalArgumentException("Введите полный URL с https:// или http://");
        } else if (type.equals("select")) {
          boolean found = false;
          for (JsonNode option : field.get("options"))
            if (option.asText().equals(value)) found = true;
          if (!found) throw new IllegalArgumentException("Выберите значение из списка");
        }
        if (name.equals("isbn")) normalizeIsbn(value);
        if (name.equals("doi")) value = normalizeDoi(value);
        if (name.equals("authors") || name.equals("author")) value = normalizeAuthors(value);
      } catch (RuntimeException e) {
        errors.put(
            name,
            type.equals("date")
                ? "Введите корректную дату (не позже сегодняшней для даты обращения)"
                : e.getMessage());
      }
      clean.put(name, value);
    }
    if (!errors.isEmpty()) throw ApiProblem.fields(errors);
    return clean;
  }

  public String format(JsonNode config, JsonNode values) {
    var clean = cleanValues(config, values);
    List<String> blocks = new ArrayList<>();
    for (JsonNode part : config.get("parts")) {
      boolean skip = false;
      for (JsonNode name : part.path("require"))
        if (clean.getOrDefault(name.asText(), "").isEmpty()) skip = true;
      if (skip) continue;
      List<String> items = new ArrayList<>();
      for (JsonNode name : part.get("fields")) {
        String value = clean.getOrDefault(name.asText(), "");
        if (!value.isEmpty()) items.add(value);
      }
      if (items.isEmpty()) continue;
      String text = String.join(part.path("join").asText(" "), items);
      String suffix = part.path("suffix").asText("");
      if (suffix.equals(".") && text.matches("(?s).*[.!?]$")) suffix = "";
      blocks.add(part.path("prefix").asText("") + text + suffix);
    }
    return String.join(" ", blocks);
  }
}
