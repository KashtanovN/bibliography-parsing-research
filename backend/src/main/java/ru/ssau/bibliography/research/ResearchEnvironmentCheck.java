package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.ssau.bibliography.research.ResearchDataset.Split;

/** Loads data and service metadata only. Never calls parse or model generation. */
public final class ResearchEnvironmentCheck {
  private ResearchEnvironmentCheck() {}

  public static void run(Path path) throws IOException, InterruptedException {
    var data = new ExcelDatasetLoader().load(path);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("datasetSha256", data.sha256());
    result.put("records", data.references().size());
    result.put("dev", data.references(Split.DEV).size());
    result.put("test", data.references(Split.TEST).size());
    result.put("rules", new RulesCitationParser().configuration());
    result.put("normalization", FieldNormalization.VERSION);
    var grobid = new GrobidCitationParser(URI.create("http://127.0.0.1:8070"), Duration.ofSeconds(10));
    result.put("grobid", grobid.serverMetadata());
    var ollama = new OllamaCitationParser(URI.create("http://127.0.0.1:11434"), "qwen3:8b", Duration.ofSeconds(10));
    result.put("ollama", ollama.serverMetadata());
    result.put("promptVersion", OllamaCitationParser.PROMPT_VERSION);
    try (var anystyle = new AnyStyleCitationParser(Path.of("../scripts/anystyle-worker.sh"), Duration.ofSeconds(30))) {
      result.put("anystyle", anystyle.serverMetadata());
    }
    result.put("status", "READY_FOR_DEV");
    result.put("inferencePerformed", false);
    System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
  }
}
