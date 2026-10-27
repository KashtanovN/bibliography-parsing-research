package ru.ssau.bibliography;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class FormatterTest {
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneId.of("Europe/Samara"));
  static final CitationFormatter FORMATTER = new CitationFormatter(CLOCK);
  static final JsonNode SCHEMA = schema();

  static JsonNode schema() {
    try (var input = FormatterTest.class.getResourceAsStream("/gost705.json")) {
      return MAPPER.readTree(input);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static ObjectNode book() {
    return MAPPER.valueToTree(
        Map.of(
            "authors",
            "Иванов Иван Иванович; Петров П. П.",
            "title",
            "Пример книги",
            "city",
            "Москва",
            "publisher",
            "Наука",
            "year",
            "2024"));
  }

  String format(String kind, JsonNode fields) {
    return FORMATTER.format(SCHEMA.path("types").path(kind), fields);
  }

  @Test
  void bookWithoutOptionalFields() {
    assertThat(format("book", book()))
        .isEqualTo("Иванов И. И., Петров П. П. Пример книги. — Москва : Наука, 2024.");
  }

  @Test
  void articleKeepsPageSymbols() {
    assertThat(
            format(
                "journal-article",
                MAPPER.valueToTree(
                    Map.of(
                        "authors",
                        "Иванов И.И.",
                        "title",
                        "Пример статьи",
                        "journal",
                        "Вестник",
                        "year",
                        "2024",
                        "volume",
                        "12",
                        "issue",
                        "3",
                        "pages",
                        "S12–S18"))))
        .isEqualTo("Иванов И. И. Пример статьи // Вестник. — 2024. — Т. 12, № 3. — С. S12–S18.");
  }

  @Test
  void missingVolumeRejected() {
    assertThatThrownBy(() -> format("journal-article", MAPPER.createObjectNode()))
        .isInstanceOfSatisfying(
            ApiProblem.class,
            e -> assertThat(((Map<?, ?>) e.body().get("fields")).containsKey("volume")).isTrue());
  }

  @Test
  void websitePreservesUrl() {
    assertThat(
            format(
                "internet-resource",
                MAPPER.valueToTree(
                    Map.of(
                        "title",
                        "Сайт",
                        "url",
                        "https://example.org/a.b?q=x:y",
                        "accessDate",
                        "2024-03-02"))))
        .isEqualTo(
            "Сайт [Электронный ресурс]. — URL: https://example.org/a.b?q=x:y (дата обращения:"
                + " 02.03.2024).");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)",
        "https://user:pass@example.org",
        "ftp://example.org",
        "garbage",
        "http://:80"
      })
  void unsafeUrls(String url) {
    assertThatThrownBy(
            () ->
                format(
                    "internet-resource",
                    MAPPER.valueToTree(
                        Map.of("title", "Сайт", "url", url, "accessDate", "2024-01-01"))))
        .isInstanceOf(ApiProblem.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2024-02-30", "9999-01-01", "02.01.2024", "2024-2-1"})
  void invalidDates(String date) {
    assertThatThrownBy(
            () ->
                format(
                    "internet-resource",
                    MAPPER.valueToTree(
                        Map.of("title", "Сайт", "url", "https://example.org", "accessDate", date))))
        .isInstanceOf(ApiProblem.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-5", "1", "2024.5", "hello", "9999"})
  void invalidYears(String year) {
    assertThatThrownBy(() -> format("book", book().put("year", year)))
        .isInstanceOf(ApiProblem.class);
  }

  @Test
  void nonTextAndUnknownFields() {
    for (JsonNode value :
        List.of(
            MAPPER.getNodeFactory().booleanNode(true),
            MAPPER.createObjectNode(),
            MAPPER.createArrayNode(),
            MAPPER.nullNode())) {
      var fields = book();
      fields.set("year", value);
      assertThatThrownBy(() -> format("book", fields)).isInstanceOf(ApiProblem.class);
    }
    assertThatThrownBy(() -> format("book", book().put("unknown", "x")))
        .isInstanceOf(ApiProblem.class);
    assertThatThrownBy(() -> format("book", null)).isInstanceOf(ApiProblem.class);
    assertThatThrownBy(() -> format("book", book().put("title", " ")))
        .isInstanceOf(ApiProblem.class);
    assertThatThrownBy(() -> format("book", book().put("title", "x".repeat(2001))))
        .isInstanceOf(ApiProblem.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"thesis", "abstract"})
  void thesisAndAbstract(String kind) {
    String text =
        format(
            kind,
            MAPPER.valueToTree(
                Map.of(
                    "author",
                    "Иванов И.И.",
                    "authorFull",
                    "Иванов Иван Иванович",
                    "title",
                    "Исследование",
                    "degree",
                    "канд. техн.",
                    "specialtyCode",
                    "2.3.1",
                    "city",
                    "Самара",
                    "year",
                    "2024",
                    "pages",
                    "120")));
    assertThat(text).contains("канд. техн. наук : 2.3.1", "/ Иванов Иван Иванович.");
    if (kind.equals("abstract")) assertThat(text).contains("автореф. дис.");
  }

  @Test
  void collection() {
    assertThat(
            format(
                "collection-article",
                MAPPER.valueToTree(
                    Map.of(
                        "authors",
                        "Иванов И. И.",
                        "title",
                        "Статья",
                        "collection",
                        "Труды",
                        "city",
                        "Самара",
                        "publisher",
                        "Издательство",
                        "year",
                        "2024",
                        "pages",
                        "12–14"))))
        .contains("// Труды. — Самара : Издательство, 2024. — С. 12–14.");
  }

  @Test
  void patent() {
    assertThat(
            format(
                "patent",
                MAPPER.valueToTree(
                    Map.of(
                        "country",
                        "РФ",
                        "patentNumber",
                        "123",
                        "title",
                        "Устройство",
                        "authors",
                        "Иванов И.И.",
                        "publishDate",
                        "2024-01-02"))))
        .isEqualTo("Патент РФ 123. Устройство / Иванов И. И. ; опубл. 02.01.2024.");
  }

  @Test
  void regulationWithoutPublisher() {
    assertThat(
            format(
                "regulation",
                MAPPER.valueToTree(
                    Map.of(
                        "title",
                        "О данных",
                        "documentType",
                        "Приказ",
                        "approvalDate",
                        "2024-01-02",
                        "number",
                        "12",
                        "city",
                        "Самара",
                        "year",
                        "2024"))))
        .contains("Самара, 2024.")
        .doesNotContain(" : ,");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Иванов И.И., Петров П.П.",
        "Иванов И.И. and Петров П.П.",
        "Иванов И.И. Петров П.П.",
        "Иванов И,И,; Петров П.П."
      })
  void authorSeparators(String authors) {
    assertThat(CitationFormatter.normalizeAuthors(authors)).isEqualTo("Иванов И. И., Петров П. П.");
  }

  @Test
  void preservesForeignAndCompoundNames() {
    assertThat(CitationFormatter.normalizeAuthors("Roald Dahl")).isEqualTo("Roald Dahl");
    assertThat(CitationFormatter.normalizeAuthors("O’Connor J.; Иванов-Петров И.И."))
        .isEqualTo("O’Connor J., Иванов-Петров И. И.");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"9780140328721", "0-14-032872-6", "ISBN: 978-0-14-032872-1", "080442957X"})
  void validIsbn(String value) {
    assertThat(CitationFormatter.normalizeIsbn(value)).isNotBlank();
  }

  @ParameterizedTest
  @ValueSource(strings = {"9780140328722", "123", "978014032872X", "0000000000000"})
  void invalidIsbn(String value) {
    assertThatThrownBy(() -> CitationFormatter.normalizeIsbn(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doi() {
    assertThat(CitationFormatter.normalizeDoi("https://doi.org/10.1234/a.b"))
        .isEqualTo("10.1234/a.b");
    assertThat(CitationFormatter.normalizeDoi("DOI: 10.1234/test")).isEqualTo("10.1234/test");
    assertThatThrownBy(() -> CitationFormatter.normalizeDoi("10.1234/bad doi"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void schemaValidation() {
    CitationFormatter.validateSchema(SCHEMA);
    var bad = SCHEMA.deepCopy();
    ((ObjectNode) bad.path("types").path("book").path("parts").get(0))
        .putArray("fields")
        .add("unknown");
    assertThatThrownBy(() -> CitationFormatter.validateSchema(bad))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void conditionalBlocksAndTerminalPunctuation() {
    var config = SCHEMA.path("types").path("book").deepCopy();
    ((ObjectNode) config.path("parts").get(0)).putArray("require").add("isbn");
    assertThat(FORMATTER.format(config, book())).doesNotContain("Пример книги");
    assertThat(format("book", book().put("title", "Название!")))
        .contains("Название! —")
        .doesNotContain("!.");
  }
}
