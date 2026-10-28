package ru.ssau.bibliography;

import static ru.ssau.bibliography.research.ReferenceField.*;

import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;
import ru.ssau.bibliography.research.ReferenceField;

/**
 * One controlled short-reference policy for all extraction methods. No gold, raw text or lookups.
 */
@Component
public final class SharedCitationFormatter {
  public static final String VERSION = "shared-short-v1";
  private final Clock clock;

  public SharedCitationFormatter(Clock clock) {
    this.clock = clock;
  }

  public enum Profile {
    GOST705("gost-7-0-5-2008", "ГОСТ Р 7.0.5–2008"),
    GOST108("gost-7-0-108-2022", "ГОСТ Р 7.0.108–2022");
    private final String id, label;

    Profile(String id, String label) {
      this.id = id;
      this.label = label;
    }

    public String id() {
      return id;
    }

    public String label() {
      return label;
    }

    public static Profile parse(String value) {
      if (value != null)
        for (Profile p : values()) {
          String normalized = value.strip().replace('—', '-').replace('–', '-');
          if (p.id.equals(normalized)
              || p.name().equals(normalized)
              || p.label.replace('–', '-').equals(normalized)) return p;
        }
      throw new IllegalArgumentException(
          "Выберите профиль ГОСТ Р 7.0.5–2008 или ГОСТ Р 7.0.108–2022");
    }
  }

  public record Context(
      Profile profile,
      String url,
      LocalDate accessDate,
      String hostingResource,
      String republicationNote,
      Boolean paginationPreserved,
      String accessNote) {
    public Context {
      if (profile == null) throw new IllegalArgumentException("Не выбран профиль");
      url = clean(url);
      hostingResource = clean(hostingResource);
      republicationNote = clean(republicationNote);
      accessNote = clean(accessNote);
      if (java.util.stream.Stream.of(url, hostingResource, republicationNote, accessNote)
          .anyMatch(v -> v.length() > 4000))
        throw new IllegalArgumentException("Слишком длинное значение контекста");
    }

    public Context(Profile profile, String url, LocalDate date) {
      this(profile, url, date, "", "", null, "");
    }
  }

  public record Result(
      String formatterVersion,
      String profile,
      String profileLabel,
      String status,
      String formatted,
      Map<String, String> issues,
      List<String> warnings,
      Context context) {
    public Result {
      issues = Map.copyOf(issues);
      warnings = List.copyOf(warnings);
    }
  }

  private enum Kind {
    BOOK,
    JOURNAL,
    COLLECTION,
    WEBSITE,
    REGULATION,
    THESIS,
    ABSTRACT,
    UNKNOWN
  }

  public Result format(Map<ReferenceField, String> input, Context context) {
    Objects.requireNonNull(input, "Поля не переданы");
    Objects.requireNonNull(context, "Контекст не передан");
    EnumMap<ReferenceField, String> fields = new EnumMap<>(ReferenceField.class);
    Map<String, String> issues = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    input.forEach(
        (key, value) -> {
          String text = clean(value);
          if (text.length() > 4000 || text.chars().anyMatch(c -> Character.isISOControl(c)))
            issues.put(key.name(), "Недопустимое или слишком длинное значение");
          else if (!text.isEmpty()) fields.put(key, text);
        });
    Kind kind = kind(fields.get(DOCUMENT_TYPE));
    boolean network = context.profile() == Profile.GOST108;
    if (kind == Kind.UNKNOWN)
      issues.put(
          DOCUMENT_TYPE.name(), "Вид документа не определён или не поддержан кратким профилем");
    String title = get(fields, TITLE);
    if (title.isEmpty())
      issues.put(TITLE.name(), "Нет заглавия: оно не восстанавливается из эталона или URL");
    String year = get(fields, YEAR);
    if (!year.isEmpty()
        && (!year.matches("[0-9]{4}")
            || Integer.parseInt(year) < 1000
            || Integer.parseInt(year) > LocalDate.now(clock).getYear() + 5)) {
      issues.put(YEAR.name(), "Некорректный год");
      year = "";
    }
    if (!network
        && Set.of(Kind.BOOK, Kind.JOURNAL, Kind.COLLECTION).contains(kind)
        && year.isEmpty())
      issues.put(YEAR.name(), "Для принятого краткого профиля нужны выходные данные с годом");
    String container = get(fields, CONTAINER_TITLE);
    if (Set.of(Kind.JOURNAL, Kind.COLLECTION).contains(kind) && container.isEmpty())
      issues.put(CONTAINER_TITLE.name(), "Нет названия содержащего издания");
    String city = get(fields, CITY), publisher = get(fields, PUBLISHER);
    if (!network && kind == Kind.BOOK) {
      if (city.isEmpty())
        issues.put(CITY.name(), "Нет места издания в данных для принятого профиля книги");
      if (publisher.isEmpty())
        issues.put(PUBLISHER.name(), "Нет издателя в данных для принятого профиля книги");
    }
    if (network) city = expandCity(city, warnings);
    if (kind == Kind.THESIS || kind == Kind.ABSTRACT)
      issues.put(
          DOCUMENT_TYPE.name(),
          "В 17-полевой схеме нет степени и специальности: реквизиты диссертации требуют"
              + " уточнения");
    if (kind == Kind.WEBSITE && !network)
      issues.put(
          "profile",
          "Для сайта в принятом исследовательском профиле выберите 7.0.108 с явным URL и датой");
    if (network) {
      if (!validUrl(context.url()))
        issues.put("context.url", "Для выбранной сетевой версии нужен корректный HTTP(S) URL");
      if (context.accessDate() == null)
        issues.put(
            "context.accessDate", "Для принятого сетевого профиля нужна явная дата обращения");
      else if (context.accessDate().isAfter(LocalDate.now(clock)))
        issues.put("context.accessDate", "Дата обращения не может быть в будущем");
    } else if (!context.url().isEmpty() || context.accessDate() != null)
      warnings.add(
          "Сетевой контекст не выводится в выбранном кратком профиле 7.0.5; профиль автоматически"
              + " не менялся");
    if (!context.republicationNote().isEmpty() && !network)
      warnings.add("Примечание о сетевой републикации не применяется к выбранному профилю 7.0.5");
    List<String> authors = authors(get(fields, AUTHORS));
    boolean incomplete = authors.removeIf(s -> s.matches("(?iu)\\[?и\\s*др\\.?]?"));
    String head = title;
    if (!authors.isEmpty()) {
      if (authors.size() <= 3 && !incomplete)
        head =
            String.join(", ", authors.stream().map(SharedCitationFormatter::headingName).toList())
                + " "
                + title;
      else
        head =
            title
                + " / "
                + String.join(
                    ", ",
                    authors.stream().map(SharedCitationFormatter::responsibilityName).toList())
                + (incomplete ? " [и др.]" : "");
    }
    if (authors.isEmpty() && incomplete)
      issues.put(AUTHORS.name(), "Есть указание «и др.», но не указан ни один автор");
    if (kind == Kind.WEBSITE
        && !title.isEmpty()
        && !title.toLowerCase(Locale.ROOT).matches(".*(?:^|[ :])сайт[.!?]?$")) head += " : сайт";
    if (kind == Kind.THESIS && !title.toLowerCase(Locale.ROOT).contains("дис")) head += " : дис.";
    if (kind == Kind.ABSTRACT && !title.toLowerCase(Locale.ROOT).contains("автореф"))
      head += " : автореф. дис.";
    String edition = get(fields, EDITION);
    if (kind == Kind.REGULATION) {
      if (!edition.isEmpty() && !title.contains(edition)) head += " : " + edition;
      edition = "";
    }
    if (!container.isEmpty()) head += " // " + container;
    List<String> blocks = new ArrayList<>();
    if (!head.isBlank()) blocks.add(sentence(head));
    if (!edition.isEmpty()) blocks.add("— " + sentence(edition));
    if (kind == Kind.JOURNAL) {
      if (!year.isEmpty()) blocks.add("— " + year + ".");
    } else {
      String imprint =
          String.join(
              " : ",
              java.util.stream.Stream.of(city, publisher).filter(v -> !v.isEmpty()).toList());
      if (!year.isEmpty()) imprint += (imprint.isEmpty() ? "" : ", ") + year;
      if (!imprint.isEmpty()) blocks.add("— " + sentence(imprint));
    }
    if (!get(fields, VOLUME).isEmpty()) blocks.add("— Т. " + sentence(get(fields, VOLUME)));
    if (!get(fields, ISSUE).isEmpty())
      blocks.add("— " + (kind == Kind.COLLECTION ? "Вып. " : "№ ") + sentence(get(fields, ISSUE)));
    String pages = get(fields, PAGES), pageMeaning = get(fields, PAGE_MEANING);
    if (!pages.isEmpty()) {
      if (network && Boolean.FALSE.equals(context.paginationPreserved()))
        warnings.add("Пагинация в выбранной сетевой версии не сохранена; страницы не выведены");
      else if (!pageMeaning.equals("Указатель страниц") && !pageMeaning.equals("Общий объём"))
        issues.put(
            PAGE_MEANING.name(),
            "Роль страниц не установлена: число не выводится с угаданным обозначением");
      else {
        String normalized = pages.replaceAll("(?<=\\d)\\s*[-–—−]\\s*(?=\\d)", "–");
        if (pageMeaning.equals("Общий объём") && !normalized.matches("[1-9][0-9]*"))
          issues.put(PAGES.name(), "Общий объём должен быть положительным числом страниц");
        else
          blocks.add(
              pageMeaning.equals("Общий объём")
                  ? "— " + normalized + " с."
                  : "— С. " + sentence(normalized));
      }
    }
    if (network) {
      if (!context.republicationNote().isEmpty()) {
        blocks.add(sentence(context.republicationNote()));
        if (!pages.isEmpty() && context.paginationPreserved() == null)
          issues.put(
              "context.paginationPreserved",
              "Для републикации нужно подтвердить сохранение пагинации");
      }
      if (validUrl(context.url())) {
        String address = "— URL: " + context.url();
        if (context.accessDate() != null && !context.accessDate().isAfter(LocalDate.now(clock)))
          address +=
              " (дата обращения: "
                  + context.accessDate().format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
                  + ")";
        blocks.add(sentence(address));
      }
      if (!context.hostingResource().isEmpty())
        blocks.add("Доступно на: " + sentence(context.hostingResource()));
      if (!context.accessNote().isEmpty())
        blocks.add("Режим доступа: " + sentence(context.accessNote()));
      warnings.add(
          "Профиль не устанавливает вид сайта и факт републикации по URL; специальные сведения о"
              + " размещении проверяются отдельно");
    }
    String text = String.join(" ", blocks);
    return new Result(
        VERSION,
        context.profile().id(),
        context.profile().label(),
        issues.isEmpty() ? "FORMATTED" : "NEEDS_REVIEW",
        text,
        issues,
        warnings,
        context);
  }

  private static Kind kind(String value) {
    if (value == null) return Kind.UNKNOWN;
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "книга", "сетевая книга", "book" -> Kind.BOOK;
      case "журнальная статья",
              "сетевая журнальная статья",
              "статья из журнала",
              "journal-article" ->
          Kind.JOURNAL;
      case "материал сборника", "статья из сборника", "collection-article" -> Kind.COLLECTION;
      case "сайт", "электронный ресурс", "сетевой ресурс", "website" -> Kind.WEBSITE;
      case "нормативный документ", "нормативный акт", "закон", "regulation" -> Kind.REGULATION;
      case "диссертация", "thesis" -> Kind.THESIS;
      case "автореферат", "автореферат диссертации", "abstract" -> Kind.ABSTRACT;
      default -> Kind.UNKNOWN;
    };
  }

  private static String clean(String value) {
    return value == null ? "" : value.replaceAll("(?U)\\s+", " ").strip();
  }

  private static String get(Map<ReferenceField, String> fields, ReferenceField key) {
    return fields.getOrDefault(key, "");
  }

  private static String sentence(String value) {
    return value.matches("(?s).*[.!?]$") ? value : value + ".";
  }

  private static boolean validUrl(String value) {
    try {
      URI uri = URI.create(value);
      return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          && uri.getRawAuthority() != null
          && !uri.getRawAuthority().isBlank()
          && !uri.getRawAuthority().startsWith(":")
          && !uri.getRawAuthority().contains("@");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static List<String> authors(String value) {
    if (value.isEmpty()) return new ArrayList<>();
    Pattern marker = Pattern.compile("(?iu)(?:\\[и\\s+др\\.?\\]|\\bи\\s+др\\.)");
    boolean incomplete = marker.matcher(value).find();
    String normalized =
        CitationFormatter.normalizeAuthors(marker.matcher(value).replaceAll("").replace('|', ';'));
    List<String> names =
        normalized.isBlank()
            ? new ArrayList<>()
            : new ArrayList<>(
                Arrays.asList(normalized.split("\\s*;\\s*|,\\s*(?=[\\p{Lu}][\\p{L}'’\\-]+\\s)")));
    if (incomplete) names.add("[и др.]");
    return names;
  }

  private static String headingName(String value) {
    Matcher m = Pattern.compile("^(.+?)\\s+((?:\\p{L}\\.\\s*){1,3})$").matcher(value.strip());
    return m.matches() ? m.group(1).replaceAll(",+$", "") + ", " + m.group(2).strip() : value;
  }

  private static String responsibilityName(String value) {
    Matcher m = Pattern.compile("^(.+?)\\s+((?:\\p{L}\\.\\s*){1,3})$").matcher(value.strip());
    return m.matches() ? m.group(2).strip() + " " + m.group(1).replaceAll(",+$", "") : value;
  }

  private static String expandCity(String city, List<String> warnings) {
    String expanded =
        Map.of("М.", "Москва", "СПб.", "Санкт-Петербург", "Л.", "Ленинград").get(city);
    if (expanded != null) return expanded;
    if (city.contains("."))
      warnings.add(
          "Сокращённое место издания требует проверки для профиля 7.0.108; неизвестное сокращение"
              + " не раскрывается догадкой");
    return city;
  }
}
