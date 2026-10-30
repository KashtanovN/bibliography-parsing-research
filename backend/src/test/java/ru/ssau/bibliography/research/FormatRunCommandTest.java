package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatRunCommandTest {
  @TempDir Path directory;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void allMethodsReceiveIdenticalContextAndOnlyTheirPredictedFields() throws Exception {
    Path run = directory.resolve("run");
    ParseReferencesCommand.run(
        new String[] {
          "--parse-dataset=../Dataset.xlsx", "--method=rules", "--limit=3", "--output=" + run
        });
    var original = Files.readAllLines(run.resolve("rules.jsonl"));
    var manifest =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(run.resolve("run.json").toFile());
    var methods = (com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("methods");
    methods.putObject("grobid");
    methods.putObject("ollama");
    json.writeValue(run.resolve("run.json").toFile(), manifest);
    for (String method : List.of("rules", "grobid", "ollama")) {
      List<String> lines = new ArrayList<>();
      for (String row : original) {
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(row);
        data.put("method", method);
        var fields = (com.fasterxml.jackson.databind.node.ObjectNode) data.get("fields");
        fields.put("TITLE", "ПРЕДСКАЗАНИЕ " + method);
        fields.put("URL", "https://parser.example/" + method);
        lines.add(json.writeValueAsString(data));
      }
      Files.write(run.resolve(method + ".jsonl"), lines);
    }
    byte[] source = Files.readAllBytes(run.resolve("ollama.jsonl"));
    FormatRunCommand.run(new String[] {"--format-run=" + run, "--dataset=../Dataset.xlsx"});
    JsonNode contexts =
        json.readTree(run.resolve("formatting/formatting.json").toFile()).get("contexts");
    for (String method : List.of("rules", "grobid", "ollama")) {
      for (String row : Files.readAllLines(run.resolve("formatting/" + method + ".jsonl"))) {
        var data = json.readTree(row);
        assertEquals(contexts.get(data.get("id").asText()), data.get("context"));
        assertTrue(
            data.path("formatting").path("formatted").asText().contains("ПРЕДСКАЗАНИЕ " + method));
        assertFalse(data.path("formatting").path("formatted").asText().contains("parser.example"));
        assertFalse(data.get("context").has("expectedCitation"));
        assertFalse(data.get("context").has("expectedFields"));
      }
    }
    assertArrayEquals(source, Files.readAllBytes(run.resolve("ollama.jsonl")));
    assertThrows(
        FileAlreadyExistsException.class,
        () ->
            FormatRunCommand.run(
                new String[] {"--format-run=" + run, "--dataset=../Dataset.xlsx"}));
  }

  @Test
  void failedParserIsNotFormattedFromGoldAndPartialRunsFail() throws Exception {
    Path run = directory.resolve("run");
    ParseReferencesCommand.run(
        new String[] {
          "--parse-dataset=../Dataset.xlsx", "--method=rules", "--limit=1", "--output=" + run
        });
    var response =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(Files.readString(run.resolve("rules.jsonl")));
    response.put("status", "ERROR");
    response.set("fields", json.createObjectNode());
    Files.writeString(run.resolve("rules.jsonl"), json.writeValueAsString(response) + "\n");
    FormatRunCommand.run(new String[] {"--format-run=" + run, "--dataset=../Dataset.xlsx"});
    var result = json.readTree(Files.readString(run.resolve("formatting/rules.jsonl")));
    assertEquals("SKIPPED_SOURCE_ERROR", result.get("status").asText());
    assertFalse(result.has("formatting"));
    Files.writeString(run.resolve("rules.jsonl"), "");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FormatRunCommand.run(
                new String[] {
                  "--format-run=" + run,
                  "--dataset=../Dataset.xlsx",
                  "--format-output=" + directory.resolve("bad")
                }));
    assertFalse(Files.exists(directory.resolve("bad")));
  }
}
