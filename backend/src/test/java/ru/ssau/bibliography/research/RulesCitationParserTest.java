package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RulesCitationParserTest {
  private final RulesCitationParser parser = new RulesCitationParser();

  private CitationParser.ParsedCitation parse(String text) {
    return parser.parse(new ResearchDataset.ParserInput("not-a-dataset-id", text));
  }

  @Test
  void extractsJournalFieldsAndPreservesAuthors() {
    var f =
        parse(
                "Иванов И.И., Петров П.П. Анализ данных // Научный журнал. 2021. Т. 5. № 2. С."
                    + " 10–20. DOI: 10.1234/test.1")
            .fields();
    assertEquals("Иванов И. И. | Петров П. П.", f.get(ReferenceField.AUTHORS));
    assertEquals("Анализ данных", f.get(ReferenceField.TITLE));
    assertEquals("Научный журнал", f.get(ReferenceField.CONTAINER_TITLE));
    assertEquals("2021", f.get(ReferenceField.YEAR));
    assertEquals("5", f.get(ReferenceField.VOLUME));
    assertEquals("2", f.get(ReferenceField.ISSUE));
    assertEquals("10–20", f.get(ReferenceField.PAGES));
    assertEquals("10.1234/test.1", f.get(ReferenceField.DOI));
  }

  @Test
  void distinguishesBookExtentFromArticlePages() {
    var f =
        parse("Петров П.П. Основы анализа. — М.: Наука, 2019. — 320 с. ISBN 978-5-1234-5678-0")
            .fields();
    assertEquals("Основы анализа", f.get(ReferenceField.TITLE));
    assertEquals("М.", f.get(ReferenceField.CITY));
    assertEquals("Наука", f.get(ReferenceField.PUBLISHER));
    assertEquals("2019", f.get(ReferenceField.YEAR));
    assertEquals("320", f.get(ReferenceField.PAGES));
    assertEquals("Общий объём", f.get(ReferenceField.PAGE_MEANING));
    assertEquals("9785123456780", f.get(ReferenceField.ISBN));
    assertEquals("Книга", f.get(ReferenceField.DOCUMENT_TYPE));
  }

  @Test
  void parsesResponsibilityNamesAndDoesNotConvertEditorToAuthor() {
    var f =
        parse("Методы анализа / И. И. Иванов, П. П. Петров. — Уфа: Издательство, 2020. — 80 с.")
            .fields();
    assertEquals("Иванов И. И. | Петров П. П.", f.get(ReferenceField.AUTHORS));
    assertEquals("Методы анализа", f.get(ReferenceField.TITLE));
    assertFalse(
        parse("Методы анализа / под ред. И. И. Иванова. — Уфа: Издательство, 2020.")
            .fields()
            .containsKey(ReferenceField.AUTHORS));
  }

  @Test
  void doesNotUseAccessDateOrIdentifierDigitsAsPublicationYear() {
    var f =
        parse(
                "Иванов И.И. Статья // Журнал. 2018. С. 1–9. URL: https://example.org/2025 (дата"
                    + " обращения: 15.10.2026)")
            .fields();
    assertEquals("2018", f.get(ReferenceField.YEAR));
    assertEquals("2026-10-15", f.get(ReferenceField.ACCESS_DATE));
    assertEquals("https://example.org/2025", f.get(ReferenceField.URL));
    assertFalse(
        parse("Статья. URL: https://example.org/2025").fields().containsKey(ReferenceField.YEAR));
  }

  @Test
  void leavesMissingFieldsAbsentAndDoesNotAssignPageRoleWithoutPages() {
    var f = parse("Иванов И.И. Книга. — М.: Издательство, 2020.").fields();
    assertFalse(f.containsKey(ReferenceField.PAGES));
    assertFalse(f.containsKey(ReferenceField.PAGE_MEANING));
    assertFalse(f.containsKey(ReferenceField.DOI));
    assertFalse(f.containsKey(ReferenceField.ACCESS_DATE));
  }

  @Test
  void keepsBalancedParenthesesInDoiAndDoesNotInventTypeFromId() {
    var a = parser.parse(new ResearchDataset.ParserInput("fixture-citation", "Статья DOI: 10.1000/a(2020)."));
    var b = parser.parse(new ResearchDataset.ParserInput("other", "Статья DOI: 10.1000/a(2020)."));
    assertEquals(a.fields(), b.fields());
    assertEquals("10.1000/a(2020)", a.fields().get(ReferenceField.DOI));
    assertFalse(a.fields().containsKey(ReferenceField.YEAR));
  }
}
