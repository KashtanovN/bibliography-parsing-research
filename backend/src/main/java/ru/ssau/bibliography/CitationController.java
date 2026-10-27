package ru.ssau.bibliography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CitationController {
  private final StandardService standards;
  private final CitationFormatter formatter;
  private final MetadataImporter importer;
  private final ObjectMapper mapper;

  public CitationController(
      StandardService standards,
      CitationFormatter formatter,
      MetadataImporter importer,
      ObjectMapper mapper) {
    this.standards = standards;
    this.formatter = formatter;
    this.importer = importer;
    this.mapper = mapper;
  }

  @GetMapping({"/health/", "/health"})
  public ResponseEntity<?> health() {
    try {
      standards.current();
      return ResponseEntity.ok(Map.of("status", "ok"));
    } catch (ApiProblem e) {
      return ResponseEntity.status(503).body(Map.of("status", "not_ready"));
    } catch (Exception e) {
      return ResponseEntity.status(503).body(Map.of("status", "unavailable"));
    }
  }

  @GetMapping({"/schema/", "/schema"})
  public JsonNode schema() {
    return standards.current().schema;
  }

  @PostMapping({"/format/", "/format"})
  public Map<String, Object> format(@RequestBody JsonNode body) {
    String kind = requiredText(body, "source_type", 80);
    JsonNode fields = body.get("fields");
    if (fields == null || !fields.isObject())
      throw new ApiProblem(400, "Ожидается объект с полями источника");
    var standard = standards.current();
    JsonNode config = standard.schema.path("types").get(kind);
    if (config == null)
      throw new ApiProblem(400, Map.of("source_type", "Выберите существующий тип источника"));
    return Map.of(
        "formatted",
        formatter.format(config, fields),
        "standard",
        standard.key,
        "version",
        standard.version);
  }

  @PostMapping({"/import/", "/import"})
  public Map<String, Object> importReference(@RequestBody JsonNode body) {
    String identifier = requiredText(body, "identifier", 512);
    var imported = importer.importReference(identifier);
    var standard = standards.current();
    JsonNode config = standard.schema.path("types").get(imported.sourceType());
    Map<String, String> fields = new LinkedHashMap<>();
    for (JsonNode field : config.get("fields")) {
      String name = field.get("name").asText();
      if (imported.fields().containsKey(name)) fields.put(name, imported.fields().get(name));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("source_type", imported.sourceType());
    result.put("fields", fields);
    result.put("provider", imported.provider());
    result.put("source_url", imported.sourceUrl());
    result.put("identifier", imported.identifier());
    result.put("standard", standard.key);
    result.put("version", standard.version);
    Object errors = Map.of();
    String formatted = null;
    try {
      formatted = formatter.format(config, mapper.valueToTree(fields));
    } catch (ApiProblem e) {
      if (e.status() != 400) throw e;
      errors = e.body().get("fields");
    }
    result.put("formatted", formatted);
    result.put("errors", errors);
    List<String> warnings =
        new ArrayList<>(List.of("Проверьте импортированные сведения по первоисточнику."));
    if (formatted == null)
      warnings.add(
          "Источник найден, но для оформления нужно заполнить или исправить отмеченные поля.");
    result.put("warnings", warnings);
    return result;
  }

  private String requiredText(JsonNode body, String name, int max) {
    if (body == null || !body.isObject() || !body.path(name).isTextual())
      throw new ApiProblem(400, "Проверьте поле " + name);
    String value = body.get(name).asText().strip();
    if (value.isEmpty() || value.length() > max)
      throw new ApiProblem(400, "Проверьте поле " + name);
    return value;
  }
}
