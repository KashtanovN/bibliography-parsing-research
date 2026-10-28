package ru.ssau.bibliography;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import ru.ssau.bibliography.SharedCitationFormatter.*;
import ru.ssau.bibliography.research.ReferenceField;

/** Shared research profiles, separate from the existing manually configurable form. */
@RestController
@RequestMapping("/api")
public class FormattingProfileController {
  private final SharedCitationFormatter formatter;

  public FormattingProfileController(SharedCitationFormatter formatter) {
    this.formatter = formatter;
  }

  @GetMapping({"/formatting-profiles", "/formatting-profiles/"})
  public Map<String, Object> profiles() {
    return Map.of(
        "formatterVersion",
        SharedCitationFormatter.VERSION,
        "profiles",
        Arrays.stream(Profile.values()).map(p -> Map.of("id", p.id(), "label", p.label())).toList(),
        "fields",
        Arrays.stream(ReferenceField.values()).map(Enum::name).toList());
  }

  @PostMapping({"/format-profile", "/format-profile/"})
  public Result format(@RequestBody JsonNode body) {
    if (body == null || !body.isObject())
      throw new ApiProblem(400, "Ожидается объект с profile, fields и context");
    for (var it = body.fieldNames(); it.hasNext(); )
      if (!Set.of("profile", "fields", "context").contains(it.next()))
        throw new ApiProblem(400, "Неизвестное поле запроса");
    if (!body.path("profile").isTextual()
        || !body.path("fields").isObject()
        || !body.path("context").isObject())
      throw new ApiProblem(400, "Нужны profile, объект fields и объект context");
    try {
      var context = body.path("context");
      for (var it = context.fieldNames(); it.hasNext(); )
        if (!Set.of(
                "url",
                "accessDate",
                "hostingResource",
                "republicationNote",
                "paginationPreserved",
                "accessNote")
            .contains(it.next())) throw new IllegalArgumentException("Неизвестное поле контекста");
      var fields = new EnumMap<ReferenceField, String>(ReferenceField.class);
      var it = body.path("fields").fields();
      while (it.hasNext()) {
        var entry = it.next();
        ReferenceField key = ReferenceField.valueOf(entry.getKey());
        JsonNode value = entry.getValue();
        if (!value.isTextual() && !value.isNull())
          throw new IllegalArgumentException("Реквизиты должны быть строками или null");
        if (value.isTextual()) fields.put(key, value.asText());
      }
      String date = text(context, "accessDate");
      JsonNode pagination = context.get("paginationPreserved");
      if (pagination != null && !pagination.isNull() && !pagination.isBoolean())
        throw new IllegalArgumentException("paginationPreserved должен быть boolean или null");
      Context input =
          new Context(
              Profile.parse(body.get("profile").asText()),
              text(context, "url"),
              date.isEmpty() ? null : LocalDate.parse(date),
              text(context, "hostingResource"),
              text(context, "republicationNote"),
              pagination == null || pagination.isNull() ? null : pagination.asBoolean(),
              text(context, "accessNote"));
      return formatter.format(fields, input);
    } catch (IllegalArgumentException | java.time.DateTimeException e) {
      throw new ApiProblem(
          400, e.getMessage() == null ? "Некорректный запрос оформления" : e.getMessage());
    }
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || value.isNull()) return "";
    if (!value.isTextual())
      throw new IllegalArgumentException("Контекст должен содержать текстовые значения: " + name);
    return value.asText();
  }
}
