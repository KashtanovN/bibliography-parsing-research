package ru.ssau.bibliography;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MetadataImporter {
  private final MetadataClient client;
  private final String mailto;

  public MetadataImporter(
      MetadataClient client, @Value("${bibliography.crossref-mailto:}") String mailto) {
    this.client = client;
    this.mailto = mailto;
  }

  public record Imported(
      String sourceType,
      Map<String, String> fields,
      String provider,
      String sourceUrl,
      String identifier) {}

  public Imported importReference(String identifier) {
    String value = identifier.strip();
    if (Pattern.compile(
            "^(?:https?://(?:www\\.)?elibrary\\.ru|EDN\\s*:|[0-9]{1,9}$|[A-Z]{6}$)",
            Pattern.CASE_INSENSITIVE)
        .matcher(value)
        .find())
      throw new ApiProblem(
          501,
          "Автоматический импорт eLIBRARY ID / EDN пока недоступен: требуется согласованный канал"
              + " получения метаданных eLIBRARY. Используйте DOI или ручную форму.");
    try {
      if (Pattern.compile(
              "^(?:https?://(?:dx\\.)?doi\\.org/|doi\\s*:|10\\.)", Pattern.CASE_INSENSITIVE)
          .matcher(value)
          .find()) return crossref(CitationFormatter.normalizeDoi(value));
      return openLibrary(CitationFormatter.normalizeIsbn(value));
    } catch (IllegalArgumentException e) {
      throw new ApiProblem(400, e.getMessage());
    }
  }

  static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static String plain(JsonNode value) {
    if (value == null || value.isNull() || value.isMissingNode()) return "";
    return Parser.unescapeEntities(value.asText(), false).replaceAll("<[^>]*>", "").strip();
  }

  private String first(JsonNode values) {
    return values != null && values.isArray() && !values.isEmpty() ? plain(values.get(0)) : "";
  }

  private Imported crossref(String doi) {
    URI uri =
        URI.create(
            "https://api.crossref.org/works/"
                + encode(doi)
                + (mailto.isBlank() ? "" : "?mailto=" + encode(mailto)));
    JsonNode message = client.get(uri).path("message");
    if (!message.isObject()) throw malformed();
    String kind = message.path("type").asText();
    if (!Set.of(
            "journal-article",
            "book",
            "monograph",
            "edited-book",
            "reference-book",
            "book-chapter",
            "proceedings-article")
        .contains(kind))
      throw new ApiProblem(
          422,
          "Тип этой публикации пока не поддерживается автоматическим импортом. Используйте ручную"
              + " форму.");
    String sourceType =
        kind.equals("journal-article")
            ? "journal-article"
            : Set.of("book-chapter", "proceedings-article").contains(kind)
                ? "collection-article"
                : "book";
    List<String> authors = new ArrayList<>();
    for (JsonNode author : array(message.path("author"))) {
      if (!author.isObject()) continue;
      String family = plain(author.path("family")), given = plain(author.path("given"));
      List<String> initials = new ArrayList<>();
      var matcher = Pattern.compile("\\p{L}+").matcher(given);
      while (matcher.find())
        initials.add(new String(Character.toChars(matcher.group().codePointAt(0))) + ".");
      String name = (family + " " + String.join(" ", initials)).strip();
      if (name.isEmpty()) name = plain(author.path("name"));
      if (!name.isEmpty()) authors.add(name);
    }
    String year = "";
    for (String key : List.of("published-print", "published-online", "published", "issued")) {
      JsonNode parts = message.path(key).path("date-parts");
      if (parts.isArray()
          && !parts.isEmpty()
          && parts.get(0).isArray()
          && !parts.get(0).isEmpty()) {
        year = parts.get(0).get(0).asText();
        break;
      }
    }
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("title", first(message.path("title")));
    fields.put("authors", String.join("; ", authors));
    fields.put("year", year);
    if (sourceType.equals("journal-article")) {
      fields.put("journal", first(message.path("container-title")));
      fields.put("volume", plain(message.path("volume")));
      fields.put("issue", plain(message.path("issue")));
      String pages = plain(message.path("page"));
      fields.put("pages", pages.isEmpty() ? plain(message.path("article-number")) : pages);
      fields.put("doi", doi);
    } else {
      fields.put("city", plain(message.path("publisher-location")));
      fields.put("publisher", plain(message.path("publisher")));
      if (sourceType.equals("book")) {
        fields.put("pages", ""); // A page range is not a total page count.
        fields.put("isbn", first(message.path("ISBN")));
        fields.put("edition", plain(message.path("edition-number")));
      } else {
        fields.put("pages", plain(message.path("page")));
        fields.put("collection", first(message.path("container-title")));
        fields.put("doi", doi);
      }
    }
    return new Imported(
        sourceType, fields, "Crossref", "https://doi.org/" + encode(doi).replace("%2F", "/"), doi);
  }

  private Imported openLibrary(String isbn) {
    String key = "ISBN:" + isbn;
    JsonNode record =
        client
            .get(
                URI.create(
                    "https://openlibrary.org/api/books?bibkeys="
                        + encode(key)
                        + "&jscmd=data&format=json"))
            .path(key);
    if (record.isMissingNode() || record.isNull())
      throw new ApiProblem(404, "Не удалось найти источник. Проверьте идентификатор.");
    if (!record.isObject()) throw malformed();
    if (record.isEmpty())
      throw new ApiProblem(404, "Не удалось найти источник. Проверьте идентификатор.");
    var match =
        Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b").matcher(plain(record.path("publish_date")));
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("title", plain(record.path("title")));
    fields.put("authors", joinedNames(record.path("authors")));
    fields.put("year", match.find() ? match.group(1) : "");
    fields.put("city", joinedNames(record.path("publish_places")));
    fields.put("publisher", joinedNames(record.path("publishers")));
    fields.put("pages", plain(record.path("number_of_pages")));
    fields.put("isbn", isbn);
    return new Imported(
        "book", fields, "Open Library", "https://openlibrary.org/isbn/" + isbn, isbn);
  }

  private String joinedNames(JsonNode records) {
    List<String> names = new ArrayList<>();
    for (JsonNode record : array(records))
      if (record.isObject()) names.add(plain(record.path("name")));
    return String.join("; ", names);
  }

  private Iterable<JsonNode> array(JsonNode value) {
    return value.isArray() ? value : List.of();
  }

  private ApiProblem malformed() {
    return new ApiProblem(502, "Источник данных вернул некорректный ответ.");
  }
}
