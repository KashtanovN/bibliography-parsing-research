package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentProtocolTest {
  @TempDir Path temporary;
  private final ObjectMapper json = new ObjectMapper();

  private Path fixture() throws Exception {
    Path dev = temporary.resolve("dev");
    ParseReferencesCommand.run(new String[] {"--parse-dataset=../датасет.xlsx", "--output=" + dev});
    ObjectNode manifest = (ObjectNode) json.readTree(dev.resolve("run.json").toFile());
    var methods = (ObjectNode) manifest.get("methods");
    ObjectNode grobid =
        json.valueToTree(
            new GrobidCitationParser(URI.create("http://localhost:8070"), Duration.ofSeconds(180))
                .configuration());
    grobid.put("serverVersion", "test-grobid");
    methods.set("grobid", grobid);
    ObjectNode ollama =
        json.valueToTree(
            new OllamaCitationParser(
                    URI.create("http://localhost:11434"), "qwen3:8b", Duration.ofSeconds(180))
                .configuration());
    ollama.put("ollamaVersion", "test-ollama");
    ollama.put("modelDigest", "test-digest");
    methods.set("ollama", ollama);
    json.writeValue(dev.resolve("run.json").toFile(), manifest);
    List<String> original = Files.readAllLines(dev.resolve("rules.jsonl"));
    for (String method : List.of("grobid", "ollama")) {
      List<String> lines = new ArrayList<>();
      for (String line : original) {
        ObjectNode row = (ObjectNode) json.readTree(line);
        row.put("method", method);
        lines.add(json.writeValueAsString(row));
      }
      Files.write(dev.resolve(method + ".jsonl"), lines);
    }
    return dev;
  }

  private Path freeze(Path dev) throws Exception {
    Path protocol = temporary.resolve("protocol");
    ExperimentProtocol.run(
        new String[] {
          "--freeze-experiment=" + dev, "--dataset=../датасет.xlsx", "--protocol-output=" + protocol
        });
    return protocol;
  }

  @Test
  void freezeRetainsPoliciesVersionsAndAcceptsOnlyMatchingImplementation() throws Exception {
    Path dev = fixture(), directory = freeze(dev);
    JsonNode p = ExperimentProtocol.read(directory);
    assertEquals(92, p.path("devRecords").asInt());
    assertEquals(408, p.path("testRecords").asInt());
    assertFalse(p.path("implementation").isEmpty());
    assertTrue(p.path("methods").path("ollama").has("systemPrompt"));
    assertDoesNotThrow(
        () ->
            ExperimentProtocol.validate(
                p, p.path("datasetSha256").asText(), p.get("methods"), 408, 180));
    assertThrows(
        FileAlreadyExistsException.class,
        () ->
            ExperimentProtocol.run(
                new String[] {
                  "--freeze-experiment=" + dev,
                  "--dataset=../датасет.xlsx",
                  "--protocol-output=" + directory
                }));
    Path copied = temporary.resolve("copy");
    assertEquals(
        ExperimentSnapshot.sha(directory.resolve("protocol.json")),
        ExperimentProtocol.copy(directory, copied));
    assertEquals(p, ExperimentProtocol.read(copied));
  }

  @Test
  void changedPromptModelCodeDatasetLimitOrTimeoutCannotPassAsFrozenTest() throws Exception {
    JsonNode p = ExperimentProtocol.read(freeze(fixture()));
    for (String key : List.of("systemPrompt", "modelDigest")) {
      ObjectNode methods = ((ObjectNode) p.get("methods")).deepCopy();
      ((ObjectNode) methods.get("ollama")).put(key, "changed");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ExperimentProtocol.validate(p, p.path("datasetSha256").asText(), methods, 408, 180));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ExperimentProtocol.validate(p, "changed", p.get("methods"), 408, 180));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExperimentProtocol.validate(
                p, p.path("datasetSha256").asText(), p.get("methods"), 3, 180));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExperimentProtocol.validate(
                p, p.path("datasetSha256").asText(), p.get("methods"), 408, 60));
    ObjectNode changed = ((ObjectNode) p).deepCopy();
    ((ObjectNode) changed.get("implementation")).put("fake.class", "changed");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExperimentProtocol.validate(
                changed, p.path("datasetSha256").asText(), p.get("methods"), 408, 180));
  }

  @Test
  void modifiedPolicyOrProtocolIsDetectedAndIncompleteDevCannotFreeze() throws Exception {
    Path dev = fixture(), directory = freeze(dev);
    Files.writeString(directory.resolve("QUALITY_REVIEW.md"), "changed");
    assertThrows(IllegalArgumentException.class, () -> ExperimentProtocol.read(directory));
    Files.writeString(directory.resolve("protocol.json"), "{}");
    assertThrows(IllegalArgumentException.class, () -> ExperimentProtocol.read(directory));
    Files.delete(dev.resolve("summary.json"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ExperimentProtocol.run(
                new String[] {
                  "--freeze-experiment=" + dev,
                  "--dataset=../датасет.xlsx",
                  "--protocol-output=" + temporary.resolve("bad")
                }));
  }

  @Test
  void unfrozenOrLimitedTestIsRejectedBeforeAnyServiceRequests() {
    for (String[] args :
        List.of(
            new String[] {"--parse-dataset=../датасет.xlsx", "--split=test", "--method=all"},
            new String[] {
              "--parse-dataset=../датасет.xlsx",
              "--split=test",
              "--method=all",
              "--protocol=unused",
              "--limit=3"
            },
            new String[] {"--parse-text=Текст", "--protocol=unused"}))
      assertThrows(IllegalArgumentException.class, () -> ParseReferencesCommand.run(args));
  }
}
