package ru.ssau.bibliography.research;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.*;

/** Conservative, fixed heuristic baseline. No network lookups, ID rules or gold access. */
public final class RulesCitationParser implements CitationParser {
  public static final String VERSION = "rules-v1";
  private static final String SURNAME = "[\\p{Lu}][\\p{L}'’\\-]+";
  private static final String INITIALS = "(?:[\\p{Lu}]\\.\\s*){1,3}";
  private static final Pattern AUTHOR =
      Pattern.compile("(" + SURNAME + ")\\s*,?\\s*(" + INITIALS + ")");
  private static final Pattern REVERSED_AUTHOR =
      Pattern.compile("(" + INITIALS + ")(" + SURNAME + ")");
  private static final Pattern URL =
      Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern DOI =
      Pattern.compile("10\\.\\d{4,9}/[^\\s<>]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:1[5-9]\\d{2}|20\\d{2})(?!\\d)");
  private static final String CITY =
      "(?:М\\.|Москва|Л\\.|Ленинград|СПб\\.|Санкт-Петербург|Уфа|Казань|Красноярск|Ярославль|Новосибирск|Томск|Самара|Омск|Владивосток|Екатеринбург|Ростов-на-Дону)";
  private static final Pattern IMPRINT =
      Pattern.compile(
          "("
              + CITY
              + "(?:\\s*[-–—]\\s*"
              + CITY
              + ")?)\\s*:\\s*(.+?)(?=,?\\s*(?:1[5-9]\\d{2}|20\\d{2})\\b)");

  public String name() {
    return "rules";
  }

  public Map<String, Object> configuration() {
    return Map.of(
        "version",
        VERSION,
        "externalEnrichment",
        false,
        "unsupported",
        "Unrecognized fields remain absent");
  }

  public ParsedCitation parse(ResearchDataset.ParserInput input) {
    String raw = input.rawCitation();
    String text = raw.replaceAll("\\s+", " ").strip();
    Map<ReferenceField, String> fields = new EnumMap<>(ReferenceField.class);
    List<String> warnings = new ArrayList<>();
    Matcher url = URL.matcher(text);
    if (url.find()) put(fields, ReferenceField.URL, trimTerminal(url.group()));
    Matcher doi = DOI.matcher(text);
    if (doi.find()) put(fields, ReferenceField.DOI, trimTerminal(doi.group()));
    first(fields, ReferenceField.EDN, text, "(?i)\\bEDN\\s*:\\s*([A-Z]{6})\\b");
    first(
        fields,
        ReferenceField.ISBN,
        text,
        "(?i)\\bISBN(?:-1[03])?\\s*:?\\s*([0-9Xx][0-9Xx -]{8,22}[0-9Xx])");
    if (fields.containsKey(ReferenceField.ISBN))
      fields.put(
          ReferenceField.ISBN,
          fields.get(ReferenceField.ISBN).replaceAll("[ -]", "").toUpperCase(Locale.ROOT));
    Matcher date =
        Pattern.compile("(?iu)дата\\s+обращения\\s*:\\s*(\\d{2}\\.\\d{2}\\.\\d{4})").matcher(text);
    if (date.find()) {
      try {
        fields.put(
            ReferenceField.ACCESS_DATE,
            LocalDate.parse(
                    date.group(1),
                    DateTimeFormatter.ofPattern("dd.MM.uuuu")
                        .withResolverStyle(ResolverStyle.STRICT))
                .toString());
      } catch (java.time.DateTimeException e) {
        warnings.add("Некорректная дата обращения в исходной строке");
      }
    }
    String base =
        text
            .split(
                "(?iu)https?://|\\bDOI\\s*:|\\bEDN\\s*:|\\bISBN(?:-1[03])?\\s*:?[ ]|\\(?дата"
                    + " обращения",
                2)[0]
            .strip();
    Matcher year = YEAR.matcher(base);
    while (year.find()) fields.put(ReferenceField.YEAR, year.group());
    int divider = base.indexOf("//");
    String titlePart = divider < 0 ? base : base.substring(0, divider).strip();
    String publication = divider < 0 ? base : base.substring(divider + 2).strip();
    Matcher imprint = IMPRINT.matcher(base);
    int imprintStart = -1;
    if (imprint.find()) {
      imprintStart = imprint.start();
      put(fields, ReferenceField.CITY, imprint.group(1));
      put(fields, ReferenceField.PUBLISHER, clean(imprint.group(2)));
    }
    List<String> authors = new ArrayList<>();
    Matcher lead = AUTHOR.matcher(titlePart);
    int consumed = 0;
    while (lead.find(consumed) && titlePart.substring(consumed, lead.start()).matches("[\\s,;]*")) {
      authors.add(lead.group(1) + " " + initials(lead.group(2)));
      consumed = lead.end();
    }
    String title = titlePart.substring(consumed).replaceFirst("^[,; ]+", "");
    int slash = title.indexOf('/');
    if (slash >= 0) {
      String responsibility = title.substring(slash + 1);
      if (authors.isEmpty()
          && !responsibility.matches("(?isu).*(?:под\\s+(?:общ[а-я.]*\\s+)?ред|сост\\.).*")) {
        Matcher m = REVERSED_AUTHOR.matcher(responsibility.strip());
        if (m.lookingAt()) {
          m.reset();
          while (m.find()) authors.add(m.group(2) + " " + initials(m.group(1)));
        } else {
          m = AUTHOR.matcher(responsibility);
          while (m.find()) authors.add(m.group(1) + " " + initials(m.group(2)));
        }
      }
      title = title.substring(0, slash);
    }
    if (!authors.isEmpty()) {
      String names = String.join(" | ", new LinkedHashSet<>(authors));
      if (titlePart.matches("(?isu).*\\[и\\s+др\\.?].*")) names += " | [и др.]";
      fields.put(ReferenceField.AUTHORS, names);
    }
    if (divider < 0) {
      int start = consumed;
      if (imprintStart >= start && imprintStart - start < title.length())
        title = title.substring(0, imprintStart - start);
      Matcher boundary =
          Pattern.compile("\\s+[—–]\\s+|\\.\\s+[—–-]\\s+|[.,]\\s*(?=(?:1[5-9]\\d{2}|20\\d{2})\\b)")
              .matcher(title);
      if (boundary.find()) title = title.substring(0, boundary.start());
    }
    put(fields, ReferenceField.TITLE, clean(title));
    if (divider >= 0) {
      String container =
          publication
              .split(
                  "(?iu)\\s*/\\s*|[.,]\\s*(?=(?:1[5-9]\\d{2}|20\\d{2})\\b)|\\s*[.—–]\\s*(?=(?:Т\\.|T\\.|№|С\\.|C\\.|Вып\\.))",
                  2)[0];
      put(fields, ReferenceField.CONTAINER_TITLE, clean(container));
    }
    first(
        fields,
        ReferenceField.VOLUME,
        publication,
        "(?iu)(?:\\b[ТT]\\.|\\bтом\\b|\\bvol\\.)\\s*([0-9]+(?:[A-Za-zА-Яа-я])?)");
    String numberScope =
        divider >= 0 ? publication : (text.matches("(?isu).*(?:закон|указ).*") ? "" : base);
    first(
        fields,
        ReferenceField.ISSUE,
        numberScope,
        "(?iu)(?:№|\\bвып\\.|\\bno\\.)\\s*([0-9]+(?:\\s*\\([0-9]+\\))?(?:[-–][0-9]+)?)");
    first(
        fields, ReferenceField.EDITION, base, "(?iu)([0-9]+[-–][еe]\\s+изд\\.(?:,\\s*[^.]+\\.)?)");
    Matcher pages =
        Pattern.compile(
                "(?iu)(?:\\b[СC]\\.|\\bpp?\\.)\\s*([\\p{L}]?\\d+[\\p{L}]?(?:\\s*[-–—]\\s*[\\p{L}]?\\d+[\\p{L}]?)?)")
            .matcher(publication);
    if (pages.find()) {
      fields.put(ReferenceField.PAGES, pages.group(1).replaceAll("\\s*[-–—]\\s*", "–"));
      fields.put(ReferenceField.PAGE_MEANING, "Указатель страниц");
    } else {
      Matcher extent = Pattern.compile("(?iu)(\\d+)\\s*(?:с|стр|p)\\.(?:\\s|$)").matcher(base);
      if (extent.find()) {
        fields.put(ReferenceField.PAGES, extent.group(1));
        fields.put(ReferenceField.PAGE_MEANING, "Общий объём");
      }
    }
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.contains("автореф"))
      fields.put(ReferenceField.DOCUMENT_TYPE, "Автореферат диссертации");
    else if (lower.contains("диссертац") || lower.matches(".*\\bдис\\..*"))
      fields.put(ReferenceField.DOCUMENT_TYPE, "Диссертация");
    else if (lower.contains("федеральный закон")
        || lower.contains("закон красноярского")
        || lower.startsWith("указ "))
      fields.put(ReferenceField.DOCUMENT_TYPE, "Нормативный документ");
    else if (divider >= 0)
      fields.put(
          ReferenceField.DOCUMENT_TYPE,
          publication.matches("(?isu).*(?:сборник|материал|конференц|ежегодник).*")
              ? "Материал сборника"
              : "Журнальная статья");
    else if (imprintStart >= 0) fields.put(ReferenceField.DOCUMENT_TYPE, "Книга");
    if (!fields.containsKey(ReferenceField.TITLE)) warnings.add("Заглавие не распознано");
    warnings.add(
        "Эвристический разбор "
            + VERSION
            + "; неподдержанные и нераспознанные поля остаются пустыми");
    return new ParsedCitation(fields, warnings, null);
  }

  private static void first(
      Map<ReferenceField, String> fields, ReferenceField field, String text, String regex) {
    Matcher matcher = Pattern.compile(regex).matcher(text);
    if (matcher.find()) put(fields, field, matcher.group(1));
  }

  private static void put(Map<ReferenceField, String> fields, ReferenceField key, String value) {
    if (value != null && !value.isBlank()) fields.put(key, value.strip());
  }

  static String initials(String text) {
    return text.replaceAll("\\s+", "").replaceAll("\\.(?=\\p{Lu})", ". ").strip();
  }

  private static String clean(String text) {
    return text.strip().replaceAll("[\\s.,;—–-]+$", "");
  }

  static String trimTerminal(String text) {
    String result = text.replaceAll("[.,;]+$", "");
    while (result.endsWith(")")
        && result.chars().filter(c -> c == ')').count()
            > result.chars().filter(c -> c == '(').count())
      result = result.substring(0, result.length() - 1);
    return result;
  }
}
