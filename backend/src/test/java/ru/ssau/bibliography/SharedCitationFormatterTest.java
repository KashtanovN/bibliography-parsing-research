package ru.ssau.bibliography;

import static org.junit.jupiter.api.Assertions.*;
import static ru.ssau.bibliography.research.ReferenceField.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.ssau.bibliography.SharedCitationFormatter.*;
import ru.ssau.bibliography.research.ReferenceField;

class SharedCitationFormatterTest {
  private final SharedCitationFormatter formatter =
      new SharedCitationFormatter(
          Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneId.of("Europe/Samara")));

  private Context bookContext() {
    return new Context(Profile.GOST705, "", null);
  }

  private Context networkContext() {
    return new Context(Profile.GOST108, "https://example.test/book", LocalDate.of(2026, 10, 27));
  }

  private Map<ReferenceField, String> book() {
    return new EnumMap<>(
        Map.of(
            DOCUMENT_TYPE,
            "Книга",
            AUTHORS,
            "Иванов И.И. | Петров П.П.",
            TITLE,
            "Книга",
            CITY,
            "М.",
            PUBLISHER,
            "Наука",
            YEAR,
            "2024",
            PAGES,
            "200",
            PAGE_MEANING,
            "Общий объём"));
  }

  @Test
  void bookUsesOneDeterministicShortPolicy() {
    var result = formatter.format(book(), bookContext());
    assertEquals("FORMATTED", result.status());
    assertEquals(
        "Иванов, И. И., Петров, П. П. Книга. — М. : Наука, 2024. — 200 с.", result.formatted());
    assertEquals(SharedCitationFormatter.VERSION, result.formatterVersion());
  }

  @Test
  void networkProfileUsesOnlyExplicitSharedUrlAndDate() {
    var fields = book();
    fields.put(URL, "https://wrong.example/parse");
    fields.put(ACCESS_DATE, "2000-01-01");
    fields.put(DOI, "10.1000/test");
    fields.put(ISBN, "9785123456780");
    var result = formatter.format(fields, networkContext());
    assertEquals("FORMATTED", result.status());
    assertTrue(result.formatted().contains("Москва : Наука, 2024"));
    assertTrue(
        result
            .formatted()
            .endsWith("— URL: https://example.test/book (дата обращения: 27.10.2026)."));
    assertFalse(result.formatted().contains("wrong.example"));
    assertFalse(result.formatted().contains("2000"));
    assertFalse(result.formatted().contains("DOI"));
    assertFalse(result.formatted().contains("ISBN"));
  }

  @Test
  void journalAndCollectionHaveDifferentIssueLabels() {
    Map<ReferenceField, String> fields =
        new EnumMap<>(
            Map.of(
                DOCUMENT_TYPE,
                "Журнальная статья",
                TITLE,
                "Исследование",
                CONTAINER_TITLE,
                "Журнал",
                YEAR,
                "2024",
                VOLUME,
                "5",
                ISSUE,
                "2",
                PAGES,
                "10-20",
                PAGE_MEANING,
                "Указатель страниц"));
    var a = formatter.format(fields, bookContext());
    assertEquals("FORMATTED", a.status());
    assertEquals("Исследование // Журнал. — 2024. — Т. 5. — № 2. — С. 10–20.", a.formatted());
    fields.put(DOCUMENT_TYPE, "Материал сборника");
    fields.put(CONTAINER_TITLE, "Сборник");
    assertTrue(formatter.format(fields, bookContext()).formatted().contains("Вып. 2."));
  }

  @Test
  void fourAuthorsAndIncompleteListGoAfterSlash() {
    var fields = book();
    fields.put(AUTHORS, "Иванов И.И. | Петров П.П. | Сидоров С.С. | Орлов О.О.");
    assertTrue(
        formatter
            .format(fields, bookContext())
            .formatted()
            .startsWith("Книга / И. И. Иванов, П. П. Петров, С. С. Сидоров, О. О. Орлов."));
    fields.put(AUTHORS, "Иванов И.И. | [и др.]");
    assertTrue(
        formatter
            .format(fields, bookContext())
            .formatted()
            .startsWith("Книга / И. И. Иванов [и др.]."));
  }

  @Test
  void missingPageRoleDoesNotBecomeGuessedPageOrExtent() {
    var fields = book();
    fields.put(PAGES, "1");
    fields.remove(PAGE_MEANING);
    var result = formatter.format(fields, bookContext());
    assertEquals("NEEDS_REVIEW", result.status());
    assertTrue(result.issues().containsKey(PAGE_MEANING.name()));
    assertFalse(result.formatted().contains("С. 1"));
    assertFalse(result.formatted().contains("1 с."));
    fields.remove(PAGES);
    fields.put(PAGE_MEANING, "Общий объём");
    assertFalse(formatter.format(fields, bookContext()).formatted().contains("с."));
  }

  @Test
  void absentNetworkContextIsNotFilledFromPredictionOrToday() {
    var fields = book();
    fields.put(URL, "https://from-parser.example");
    fields.put(ACCESS_DATE, "2026-10-27");
    var result = formatter.format(fields, new Context(Profile.GOST108, "", null));
    assertEquals("NEEDS_REVIEW", result.status());
    assertTrue(result.issues().containsKey("context.url"));
    assertTrue(result.issues().containsKey("context.accessDate"));
    assertFalse(result.formatted().contains("from-parser"));
    assertFalse(result.formatted().contains("28.10.2026"));
  }

  @Test
  void doiNeverSelectsNetworkProfileAndUrlIsNotDerivedFromDoi() {
    var fields = book();
    fields.put(DOI, "10.1000/test");
    var result = formatter.format(fields, bookContext());
    assertEquals(Profile.GOST705.id(), result.profile());
    assertFalse(result.formatted().contains("URL"));
    result = formatter.format(fields, new Context(Profile.GOST108, "", LocalDate.of(2026, 10, 27)));
    assertTrue(result.issues().containsKey("context.url"));
  }

  @Test
  void websiteCanOmitUnknownPublisherPlaceAndYear() {
    var result = formatter.format(Map.of(DOCUMENT_TYPE, "Сайт", TITLE, "Пример"), networkContext());
    assertEquals("FORMATTED", result.status());
    assertEquals(
        "Пример : сайт. — URL: https://example.test/book (дата обращения: 27.10.2026).",
        result.formatted());
  }

  @Test
  void futureDateAndUnsupportedKindNeedReview() {
    var date =
        formatter.format(
            book(),
            new Context(Profile.GOST108, "https://example.test", LocalDate.of(2026, 10, 29)));
    assertEquals("NEEDS_REVIEW", date.status());
    assertFalse(date.formatted().contains("29.10.2026"));
    var unknown =
        formatter.format(Map.of(TITLE, "Заглавие", DOCUMENT_TYPE, "Неопределено"), bookContext());
    assertTrue(unknown.issues().containsKey(DOCUMENT_TYPE.name()));
    var missing = formatter.format(Map.of(DOCUMENT_TYPE, "Книга"), bookContext());
    assertTrue(missing.issues().containsKey(TITLE.name()));
  }

  @Test
  void republicationRequiresExplicitPaginationDecisionAndNeverInventsNotes() {
    var noPages =
        new Context(
            Profile.GOST108,
            "https://example.test/book",
            LocalDate.of(2026, 10, 27),
            "Библиотека",
            "Электронная копия печатного издания",
            false,
            "По подписке");
    var result = formatter.format(book(), noPages);
    assertFalse(result.formatted().contains("200 с."));
    assertEquals("FORMATTED", result.status());
    assertTrue(result.formatted().contains("Доступно на: Библиотека."));
    assertTrue(result.formatted().endsWith("Режим доступа: По подписке."));
    var uncertain =
        new Context(
            Profile.GOST108,
            noPages.url(),
            noPages.accessDate(),
            noPages.hostingResource(),
            noPages.republicationNote(),
            null,
            "");
    assertTrue(
        formatter.format(book(), uncertain).issues().containsKey("context.paginationPreserved"));
    assertFalse(
        formatter.format(book(), networkContext()).formatted().contains("Электронная копия"));
  }

  @Test
  void legalRevisionIsPreservedWithoutDuplication() {
    String revision = "в редакции от 01.01.2024";
    var fields =
        new EnumMap<>(
            Map.of(DOCUMENT_TYPE, "Нормативный документ", TITLE, "Закон № 1", EDITION, revision));
    assertEquals(
        "Закон № 1 : " + revision + ".", formatter.format(fields, bookContext()).formatted());
    fields.put(TITLE, "Закон № 1 : " + revision);
    assertEquals(
        "Закон № 1 : " + revision + ".", formatter.format(fields, bookContext()).formatted());
  }

  @Test
  void controlledProfileBoundsAndLabelsAreExplicit() {
    assertEquals(Profile.GOST108, Profile.parse("ГОСТ Р 7.0.108–2022"));
    assertEquals(Profile.GOST705, Profile.parse("gost-7-0-5-2008"));
    assertThrows(IllegalArgumentException.class, () -> Profile.parse("ГОСТ Р 7.0.100–2018"));
    var fields = book();
    fields.put(YEAR, "20??");
    assertTrue(formatter.format(fields, bookContext()).issues().containsKey(YEAR.name()));
    fields.put(PAGES, "10–20");
    assertTrue(formatter.format(fields, bookContext()).issues().containsKey(PAGES.name()));
  }

  @Test
  void validatesExplicitUrlWithoutFetchingOrInferringIt() {
    for (String value :
        List.of("", "file:///etc/passwd", "http://:80", "https://user:pass@example.test"))
      assertTrue(
          formatter
              .format(book(), new Context(Profile.GOST108, value, LocalDate.of(2026, 10, 27)))
              .issues()
              .containsKey("context.url"));
    assertFalse(
        formatter
            .format(
                book(),
                new Context(Profile.GOST108, "HTTPS://пример.рф/книга", LocalDate.of(2026, 10, 27)))
            .issues()
            .containsKey("context.url"));
  }
}
