package ru.ssau.bibliography.research;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.ssau.bibliography.research.ResearchDataset.*;

/** Reads only the main dataset sheet, without executing formulas or fetching external metadata. */
public final class ExcelDatasetLoader {
  private static final List<String> REQUIRED =
      List.of(
          "ID",
          "Исходная ссылка",
          "Часть набора",
          "Группа разделения",
          "Тип исходного списка",
          "Применяемый ГОСТ",
          "URL выбранной сетевой версии",
          "Дата обращения для оформления",
          "Вариант оформления");
  private static final Pattern AMBIGUITY = Pattern.compile("Неоднозначные поля:\\s*([^\\r\\n.]+)");

  public ResearchDataset load(Path path) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    byte[] content;
    try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
      content = in.readAllBytes();
    }
    try (Workbook book = new XSSFWorkbook(new java.io.ByteArrayInputStream(content))) {
      Sheet sheet = book.getSheet("Датасет");
      if (sheet == null) throw invalid(0, "Нет листа «Датасет»");
      DataFormatter formatter = new DataFormatter(Locale.ROOT);
      Map<String, Integer> headers = headers(sheet.getRow(0), formatter);
      for (String name : REQUIRED) require(headers, name);
      for (ReferenceField field : ReferenceField.values()) require(headers, field.header());
      List<AnnotatedReference> rows = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      Map<String, Split> groups = new HashMap<>();
      for (int index = 1; index <= sheet.getLastRowNum(); index++) {
        Row row = sheet.getRow(index);
        if (row == null || blankRow(row, formatter)) continue;
        int number = index + 1;
        String id = required(row, headers, "ID", formatter);
        if (!ids.add(id)) throw invalid(number, "Повтор ID: " + id);
        Cell raw = row.getCell(headers.get("Исходная ссылка"));
        if (raw == null
            || raw.getCellType() != CellType.STRING
            || raw.getStringCellValue().isBlank())
          throw invalid(number, "Исходная ссылка должна быть непустым текстом");
        String splitText = required(row, headers, "Часть набора", formatter);
        Split split =
            switch (splitText.toLowerCase(Locale.ROOT)) {
              case "dev" -> Split.DEV;
              case "test" -> Split.TEST;
              default ->
                  throw invalid(number, "Часть набора должна быть dev или test: " + splitText);
            };
        String group = required(row, headers, "Группа разделения", formatter);
        Split previous = groups.putIfAbsent(group, split);
        if (previous != null && previous != split)
          throw invalid(number, "Одна группа источника попала в dev и test: " + group);
        EnumMap<ReferenceField, String> expected = new EnumMap<>(ReferenceField.class);
        for (ReferenceField field : ReferenceField.values()) {
          String value = text(row, headers, field.header(), formatter);
          if (field == ReferenceField.ACCESS_DATE) {
            LocalDate date = date(row, headers, field.header(), formatter);
            value = date == null ? "" : date.toString();
          }
          if (!value.isEmpty()) expected.put(field, value);
        }
        String standard = required(row, headers, "Применяемый ГОСТ", formatter);
        if (!standard.matches("ГОСТ Р 7\\.0\\.(5[–—-]2008|108[–—-]2022)"))
          throw invalid(number, "Неизвестный профиль ГОСТ: " + standard);
        Set<ReferenceField> ambiguous = ambiguous(row, headers, formatter);
        FormattingContext context =
            new FormattingContext(
                standard,
                text(row, headers, "URL выбранной сетевой версии", formatter),
                date(row, headers, "Дата обращения для оформления", formatter),
                text(row, headers, "Вариант оформления", formatter));
        rows.add(
            new AnnotatedReference(
                new ParserInput(id, raw.getStringCellValue()),
                split,
                group,
                required(row, headers, "Тип исходного списка", formatter),
                expected,
                ambiguous,
                context));
      }
      if (rows.isEmpty()) throw invalid(0, "Нет записей на листе «Датасет»");
      return new ResearchDataset(HexFormat.of().formatHex(digest.digest()), rows);
    }
  }

  private static Map<String, Integer> headers(Row row, DataFormatter formatter) {
    if (row == null) throw invalid(1, "Нет строки заголовков");
    Map<String, Integer> result = new HashMap<>();
    for (Cell cell : row) {
      String name = value(cell, formatter).strip();
      if (!name.isEmpty() && result.putIfAbsent(name, cell.getColumnIndex()) != null)
        throw invalid(1, "Повтор заголовка: " + name);
    }
    return result;
  }

  private static void require(Map<String, Integer> headers, String name) {
    if (!headers.containsKey(name)) throw invalid(1, "Нет обязательного столбца: " + name);
  }

  private static boolean blankRow(Row row, DataFormatter formatter) {
    for (Cell cell : row) if (!value(cell, formatter).isBlank()) return false;
    return true;
  }

  private static String text(
      Row row, Map<String, Integer> headers, String name, DataFormatter formatter) {
    Integer column = headers.get(name);
    return column == null ? "" : value(row.getCell(column), formatter).strip();
  }

  private static String value(Cell cell, DataFormatter formatter) {
    if (cell == null) return "";
    if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.ERROR)
      throw invalid(cell.getRowIndex() + 1, "Формула или ошибка в данных: " + cell.getAddress());
    return cell.getCellType() == CellType.STRING
        ? cell.getStringCellValue()
        : formatter.formatCellValue(cell);
  }

  private static String required(
      Row row, Map<String, Integer> headers, String name, DataFormatter formatter) {
    String result = text(row, headers, name, formatter);
    if (result.isEmpty()) throw invalid(row.getRowNum() + 1, "Не заполнено поле: " + name);
    return result;
  }

  private static LocalDate date(
      Row row, Map<String, Integer> headers, String name, DataFormatter formatter) {
    Integer column = headers.get(name);
    Cell cell = column == null ? null : row.getCell(column);
    String raw = value(cell, formatter).strip();
    if (raw.isEmpty()) return null;
    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
      return cell.getLocalDateTimeCellValue().toLocalDate();
    for (DateTimeFormatter pattern :
        List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.uuuu")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT))) {
      try {
        return LocalDate.parse(raw, pattern);
      } catch (DateTimeParseException ignored) {
      }
    }
    throw invalid(row.getRowNum() + 1, "Некорректная дата в «" + name + "»: " + raw);
  }

  private static Set<ReferenceField> ambiguous(
      Row row, Map<String, Integer> headers, DataFormatter formatter) {
    String names;
    if (headers.containsKey("Неоднозначные поля")) {
      names = text(row, headers, "Неоднозначные поля", formatter);
    } else {
      var match = AMBIGUITY.matcher(text(row, headers, "Примечания к разметке", formatter));
      names = match.find() ? match.group(1).strip() : "";
    }
    Set<ReferenceField> fields = EnumSet.noneOf(ReferenceField.class);
    if (names.isBlank() || names.equalsIgnoreCase("нет")) return fields;
    for (String name : names.split(";")) {
      try {
        fields.add(ReferenceField.fromAnnotationName(name.strip()));
      } catch (IllegalArgumentException e) {
        throw invalid(row.getRowNum() + 1, e.getMessage());
      }
    }
    return fields;
  }

  private static IllegalArgumentException invalid(int row, String message) {
    return new IllegalArgumentException((row > 0 ? "Строка " + row + ": " : "") + message);
  }
}
