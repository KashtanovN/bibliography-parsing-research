package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ssau.bibliography.research.ResearchDataset.*;

class ExcelDatasetLoaderTest {
  @TempDir Path directory;
  private final ExcelDatasetLoader loader = new ExcelDatasetLoader();

  @Test
  void readsActualReviewedDatasetWithoutChangingIt() throws Exception {
    Path path = Path.of("..", "Dataset.xlsx");
    byte[] original = Files.readAllBytes(path);
    ResearchDataset dataset = loader.load(path);
    assertEquals(500, dataset.references().size());
    assertEquals(92, dataset.references(Split.DEV).size());
    assertEquals(408, dataset.references(Split.TEST).size());
    assertEquals(
        203, dataset.references().stream().map(AnnotatedReference::sourceGroup).distinct().count());
    assertEquals(8, dataset.references().stream().mapToInt(r -> r.ambiguousFields().size()).sum());
    assertEquals(
        7, dataset.references().stream().filter(r -> !r.ambiguousFields().isEmpty()).count());
    assertEquals(
        60, dataset.references().stream().filter(r -> r.sourceType().equals("ВКР")).count());
    assertEquals(
        42,
        dataset.references().stream().filter(r -> r.sourceType().equals("Диссертация")).count());
    var sample =
        dataset.references().stream()
            .filter(r -> r.input().id().equals("R103"))
            .findFirst()
            .orElseThrow();
    assertEquals("2023", sample.expectedFields().get(ReferenceField.YEAR));
    assertEquals("2024-11-12", sample.expectedFields().get(ReferenceField.ACCESS_DATE));
    assertEquals(Set.of(ReferenceField.DOCUMENT_TYPE), sample.ambiguousFields());
    var yearConflict = dataset.references().stream()
        .filter(r -> r.input().id().equals("R412")).findFirst().orElseThrow();
    assertEquals(Set.of(ReferenceField.YEAR), yearConflict.ambiguousFields());
    assertEquals(402, dataset.references(Split.TEST).stream()
        .filter(r -> r.ambiguousFields().isEmpty()).count());
    assertArrayEquals(original, Files.readAllBytes(path));
  }

  @Test
  void parserInputsContainOnlyIdAndUnchangedRawText() throws Exception {
    try (var book = fixture()) {
      String raw = "  Иванов А.А.\nЗаглавие. — 2020.  ";
      cell(book, 1, "Исходная ссылка").setCellValue(raw);
      cell(book, 1, "Заглавие").setCellValue("СЕКРЕТНЫЙ ЭТАЛОН");
      cell(book, 1, "URL выбранной сетевой версии").setCellValue("https://example.org/context");
      var data = loader.load(save(book));
      ParserInput input = data.parserInputs(Split.DEV).get(0);
      assertEquals(raw, input.rawCitation());
      var json = new ObjectMapper().valueToTree(input);
      assertEquals(2, json.size());
      assertTrue(json.has("id"));
      assertTrue(json.has("rawCitation"));
      assertFalse(json.toString().contains("СЕКРЕТНЫЙ ЭТАЛОН"));
      assertFalse(json.toString().contains("example.org"));
      assertThrows(UnsupportedOperationException.class, () -> data.references().clear());
    }
  }

  @Test
  void keepsRawAndFormattingDatesSeparateAndReadsExcelDates() throws Exception {
    try (var book = fixture()) {
      cell(book, 1, "Дата обращения из строки").setCellValue("12.11.2024");
      Cell date = cell(book, 1, "Дата обращения для оформления");
      date.setCellValue(LocalDate.of(2026, 10, 27));
      var style = book.createCellStyle();
      style.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"));
      date.setCellStyle(style);
      var ref = loader.load(save(book)).references().get(0);
      assertEquals("2024-11-12", ref.expectedFields().get(ReferenceField.ACCESS_DATE));
      assertEquals(LocalDate.of(2026, 10, 27), ref.formatting().accessDate());
      assertFalse(ref.expectedFields().containsKey(ReferenceField.URL));
    }
  }

  @Test
  void rejectsDuplicateIds() throws Exception {
    try (var book = fixture()) {
      secondRow(book, "R1", "dev", "source-b");
      assertInvalid(book, "Повтор ID");
    }
  }

  @Test
  void rejectsSourceGroupCrossingSplits() throws Exception {
    try (var book = fixture()) {
      secondRow(book, "R2", "test", "source-a");
      assertInvalid(book, "dev и test");
    }
  }

  @Test
  void rejectsUnknownSplitAndMissingRequiredColumns() throws Exception {
    try (var book = fixture()) {
      cell(book, 1, "Часть набора").setCellValue("train");
      assertInvalid(book, "dev или test");
      cell(book, 1, "Часть набора").setCellValue("dev");
      var header = book.getSheet("Датасет").getRow(0);
      header.removeCell(header.getCell(column(book, "Год")));
      assertInvalid(book, "Нет обязательного столбца: Год");
    }
  }

  @Test
  void rejectsDuplicateHeadersAndUnknownStandard() throws Exception {
    try (var book = fixture()) {
      cell(book, 1, "Применяемый ГОСТ").setCellValue("APA");
      assertInvalid(book, "Неизвестный профиль ГОСТ");
      cell(book, 1, "Применяемый ГОСТ").setCellValue("ГОСТ Р 7.0.5–2008");
      var header = book.getSheet("Датасет").getRow(0);
      header.createCell(header.getLastCellNum()).setCellValue("ID");
      assertInvalid(book, "Повтор заголовка");
    }
  }

  @Test
  void doesNotExecuteFormulaOrReplaceItWithCachedValue() throws Exception {
    try (var book = fixture()) {
      cell(book, 1, "Год").setCellFormula("2000+26");
      assertInvalid(book, "Формула или ошибка");
    }
  }

  @Test
  void ignoresOtherSheetsAndStyledEmptyRows() throws Exception {
    try (var book = fixture()) {
      book.createSheet("Методика").createRow(0).createCell(0).setCellFormula("1/0");
      var empty = book.getSheet("Датасет").createRow(10).createCell(0);
      empty.setCellStyle(book.createCellStyle());
      assertEquals(1, loader.load(save(book)).references().size());
    }
  }

  @Test
  void rejectsBadDatesInsteadOfRepairingThem() throws Exception {
    try (var book = fixture()) {
      cell(book, 1, "Дата обращения из строки").setCellValue("31.02.2026");
      assertInvalid(book, "Некорректная дата");
    }
  }

  @Test
  void readsAmbiguityWithoutTreatingBlankAsUnknown() throws Exception {
    try (var book = fixture()) {
      cell(book, 1, "Примечания к разметке")
          .setCellValue("Проверено. Неоднозначные поля: Издатель; Вид документа. Комментарий.");
      var ref = loader.load(save(book)).references().get(0);
      assertEquals(
          Set.of(ReferenceField.PUBLISHER, ReferenceField.DOCUMENT_TYPE), ref.ambiguousFields());
      assertFalse(ref.expectedFields().containsKey(ReferenceField.PUBLISHER));
      assertFalse(ref.ambiguousFields().contains(ReferenceField.ISBN));
      cell(book, 1, "Примечания к разметке")
          .setCellValue("Неоднозначные поля: неизвестная колонка.");
      assertInvalid(book, "Неизвестное неоднозначное поле");
    }
  }

  @Test
  void explicitAmbiguityColumnOverridesCommentAndMissingMainSheetFails() throws Exception {
    try (var book = fixture()) {
      var header = book.getSheet("Датасет").getRow(0);
      header.createCell(header.getLastCellNum()).setCellValue("Неоднозначные поля");
      cell(book, 1, "Примечания к разметке").setCellValue("Неоднозначные поля: Издатель.");
      cell(book, 1, "Неоднозначные поля").setCellValue("нет");
      assertTrue(loader.load(save(book)).references().get(0).ambiguousFields().isEmpty());
      book.setSheetName(0, "Неверный лист");
      assertInvalid(book, "Нет листа");
    }
  }

  private void assertInvalid(XSSFWorkbook book, String message) throws Exception {
    var path = save(book);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> loader.load(path))
            .getMessage()
            .contains(message));
  }

  private Path save(XSSFWorkbook book) throws Exception {
    Path path = directory.resolve("fixture.xlsx");
    try (var out = Files.newOutputStream(path)) {
      book.write(out);
    }
    return path;
  }

  private XSSFWorkbook fixture() {
    var book = new XSSFWorkbook();
    var sheet = book.createSheet("Датасет");
    Set<String> columns = new LinkedHashSet<>();
    for (ReferenceField f : ReferenceField.values()) columns.add(f.header());
    columns.addAll(
        List.of(
            "ID",
            "Исходная ссылка",
            "Часть набора",
            "Группа разделения",
            "Тип исходного списка",
            "Применяемый ГОСТ",
            "URL выбранной сетевой версии",
            "Дата обращения для оформления",
            "Вариант оформления",
            "Примечания к разметке"));
    var header = sheet.createRow(0);
    int index = 0;
    for (String name : columns) header.createCell(index++).setCellValue(name);
    secondRow(book, "R1", "dev", "source-a", 1);
    return book;
  }

  private void secondRow(XSSFWorkbook book, String id, String split, String group) {
    secondRow(book, id, split, group, 2);
  }

  private void secondRow(XSSFWorkbook book, String id, String split, String group, int index) {
    book.getSheet("Датасет").createRow(index);
    for (var entry :
        Map.of(
                "ID",
                id,
                "Исходная ссылка",
                "Иванов А. А. Книга. 2020.",
                "Часть набора",
                split,
                "Группа разделения",
                group,
                "Тип исходного списка",
                "ВКР",
                "Применяемый ГОСТ",
                "ГОСТ Р 7.0.5–2008")
            .entrySet()) cell(book, index, entry.getKey()).setCellValue(entry.getValue());
  }

  private int column(XSSFWorkbook book, String header) {
    for (Cell c : book.getSheet("Датасет").getRow(0))
      if (c.getStringCellValue().equals(header)) return c.getColumnIndex();
    throw new IllegalArgumentException(header);
  }

  private Cell cell(XSSFWorkbook book, int row, String header) {
    var target = book.getSheet("Датасет").getRow(row);
    return target.getCell(column(book, header), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
  }
}
