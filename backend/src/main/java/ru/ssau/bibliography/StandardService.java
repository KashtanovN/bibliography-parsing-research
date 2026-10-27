package ru.ssau.bibliography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StandardService implements ApplicationRunner {
  public static final String KEY = "gost-7-0-5-2008";
  private final StandardRepository repository;
  private final ObjectMapper mapper;

  public StandardService(StandardRepository repository, ObjectMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  public CitationStandard current() {
    return repository
        .findByKey(KEY)
        .orElseThrow(
            () ->
                new ApiProblem(
                    503, "Правила оформления ещё не загружены. Обратитесь к администратору."));
  }

  @Transactional
  public void load(JsonNode schema, boolean ifMissing) {
    CitationFormatter.validateSchema(schema);
    var existing = repository.findByKey(schema.path("id").asText());
    if (ifMissing && existing.isPresent()) return;
    var standard = existing.orElseGet(CitationStandard::new);
    standard.key = schema.get("id").asText();
    standard.label = schema.get("label").asText();
    standard.version = schema.get("version").asText();
    standard.schema = schema;
    standard.updatedAt = Instant.now();
    repository.saveAndFlush(standard);
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (args.containsOption("load-standard")) {
      var values = args.getOptionValues("load-standard");
      if (values == null || values.size() != 1 || values.get(0).isBlank())
        throw new IllegalArgumentException("Use --load-standard=/path/to/standard.json");
      try (var input = Files.newInputStream(Path.of(values.get(0)))) {
        load(mapper.readTree(input), false);
      }
    } else {
      try (var input = new ClassPathResource("gost705.json").getInputStream()) {
        load(mapper.readTree(input), true);
      }
    }
  }
}
