package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParseReferencesCommandTest {
  @TempDir Path directory;
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void runsRulesOnDevSubsetAndWritesPredictionsWithoutGold() throws Exception {
    Path output = directory.resolve("run");
    assertEquals(
        0,
        ParseReferencesCommand.run(
            new String[] {
              "--parse-dataset=../датасет.xlsx", "--method=rules", "--limit=3", "--output=" + output
            }));
    var meta = JSON.readTree(output.resolve("run.json").toFile());
    assertEquals("dev", meta.path("split").asText());
    assertEquals(92, meta.path("splitSize").asInt());
    assertEquals(64, meta.path("datasetSha256").asText().length());
    assertEquals("COMPLETED", meta.path("executionState").asText());
    assertEquals(180, meta.path("timeoutSeconds").asInt());
    assertTrue(meta.has("environment"));
    assertFalse(meta.path("implementation").isEmpty());
    List<String> lines = Files.readAllLines(output.resolve("rules.jsonl"));
    assertEquals(3, lines.size());
    for (String line : lines) {
      JsonNode result = JSON.readTree(line);
      assertEquals("rules", result.path("method").asText());
      assertEquals("SUCCESS", result.path("status").asText());
      assertFalse(result.has("expectedFields"));
      assertFalse(result.has("formatting"));
      assertFalse(result.has("ambiguousFields"));
      assertTrue(result.has("rawCitation"));
      assertTrue(result.has("startedAt"));
      assertTrue(result.has("finishedAt"));
    }
    assertThrows(
        FileAlreadyExistsException.class,
        () ->
            ParseReferencesCommand.run(new String[] {"--parse-text=Текст", "--output=" + output}));
  }

  @Test
  void runsAllFourAgainstTestHttpServices() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      serve(server, "/api/version", 200, "{\"version\":\"test-server\"}");
      serve(
          server,
          "/api/tags",
          200,
          "{\"models\":[{\"name\":\"qwen3:8b\",\"digest\":\"mock-model\",\"details\":{}}]}");
      serve(
          server,
          "/api/processCitation",
          200,
          "<biblStruct><monogr><title>Книга</title></monogr></biblStruct>");
      var fields = JSON.createObjectNode();
      for (ReferenceField field : ReferenceField.values()) fields.putNull(field.name());
      fields.put("TITLE", "Книга");
      serve(
          server,
          "/api/chat",
          200,
          JSON.writeValueAsString(
              Map.of("done", true, "message", Map.of("content", JSON.writeValueAsString(fields)))));
      server.start();
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      Path worker = directory.resolve("any-worker.sh");
      Files.writeString(worker, """
#!/bin/sh
printf '%s\\n' '{"protocol":"anystyle-jsonl-v1","version":"fake","modelSha256":"%s","workerSha256":"%s","assetsSha256":{"test":"abc"}}'
while IFS= read -r request; do
  printf '%%s\\n' '{"result":[{"title":["Книга"],"type":"book"}]}'
done
""".formatted("%s", "a".repeat(64), "b".repeat(64)));
      assertTrue(worker.toFile().setExecutable(true));
      Path output = directory.resolve("all");
      assertEquals(
          0,
          ParseReferencesCommand.run(
              new String[] {
                "--parse-text=Иванов И.И. Книга. — М.: Издательство, 2020.",
                "--method=all",
                "--anystyle-command=" + worker,
                "--grobid-url=" + url,
                "--ollama-url=" + url,
                "--output=" + output
              }));
      for (String name : List.of("rules", "grobid", "ollama", "anystyle")) {
        var result = JSON.readTree(Files.readString(output.resolve(name + ".jsonl")));
        assertEquals("SUCCESS", result.path("status").asText());
      }
      var run = JSON.readTree(output.resolve("run.json").toFile());
      assertEquals("mock-model", run.path("methods").path("ollama").path("modelDigest").asText());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void failedServiceIsRecordedAndMakesRunUnsuccessful() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      serve(server, "/api/version", 200, "test");
      serve(server, "/api/processCitation", 503, "busy");
      server.start();
      Path output = directory.resolve("failed");
      assertEquals(
          3,
          ParseReferencesCommand.run(
              new String[] {
                "--parse-text=Текст",
                "--method=grobid",
                "--grobid-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "--output=" + output
              }));
      var result = JSON.readTree(Files.readString(output.resolve("grobid.jsonl")));
      assertEquals("ERROR", result.path("status").asText());
      assertEquals("busy", result.path("rawResponse").asText());
      assertEquals(0, result.path("fields").size());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsAmbiguousAndUnknownCommandOptions() {
    for (String[] args :
        List.of(
            new String[] {"--parse-text=x", "--parse-dataset=y"},
            new String[] {"--parse-text=x", "--method=other"},
            new String[] {"--parse-text=x", "--limit=0"},
            new String[] {"--parse-text=x", "--method=rules", "--method=ollama"},
            new String[] {"--parse-text=x", "--secret=x"}))
      assertThrows(IllegalArgumentException.class, () -> ParseReferencesCommand.run(args));
  }

  private void serve(HttpServer server, String path, int status, String response) {
    server.createContext(
        path,
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }
}
