package ru.ssau.bibliography.research;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.*;

/** Fixed before final test evaluation; independent of parser and gold values. */
public final class FieldNormalization {
  public static final String VERSION = "field-evaluation-v2";

  private FieldNormalization() {}

  public static String normalize(ReferenceField field, String value) {
    if (value == null) return "";
    String text = Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("\\s+", " ").strip();
    return switch (field) {
      case AUTHORS -> normalizeAuthors(text);
      case DOI ->
          text.replaceFirst("(?i)^(?:https?://(?:dx\\.)?doi\\.org/|doi:\\s*)", "")
              .toLowerCase(java.util.Locale.ROOT);
      case ISBN -> text.replaceAll("[\\s-]", "");
      case PAGES -> text.replaceAll("(?<=\\d)\\s*[-–—−]\\s*(?=\\d)", "–");
      case ACCESS_DATE -> normalizeDate(text);
      case DOCUMENT_TYPE ->
          switch (text) {
            case "Сетевая журнальная статья" -> "Журнальная статья";
            case "Сетевая книга" -> "Книга";
            default -> text;
          };
      default -> text;
    };
  }

  private static String normalizeAuthors(String text) {
    // A comma after completed initials, before the next surname + initials, is a list
    // separator. A comma between a surname and its initials is intra-name punctuation.
    // Preserve names, initials, author count/order and unknown structures verbatim.
    return text.replaceAll("(?<=[.\\]])\\s*,\\s*(?=\\p{Lu}[\\p{L}'’\\-]*\\s+\\p{Lu}\\.)", "|")
        .replaceAll("(?<=\\p{L}),\\s*(?=\\p{Lu}\\.)", "")
        .replaceAll(",\\s*(?=\\[(?i:и\\s+др)\\.\\])", "|")
        .replaceAll("\\s+", "")
        .replace(';', '|');
  }

  private static String normalizeDate(String value) {
    for (DateTimeFormatter format :
        java.util.List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT))) {
      try {
        return LocalDate.parse(value, format).toString();
      } catch (DateTimeParseException ignored) {
      }
    }
    return value; // Invalid predictions remain incorrect, not repaired or discarded.
  }
}
