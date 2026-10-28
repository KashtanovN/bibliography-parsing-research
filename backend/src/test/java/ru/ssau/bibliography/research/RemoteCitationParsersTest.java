package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class RemoteCitationParsersTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private HttpServer server;
  private URI url;
  private final AtomicReference<String> body = new AtomicReference<>();
  private final AtomicReference<String> contentType = new AtomicReference<>();
  private final AtomicReference<String> accept = new AtomicReference<>();
  private static final String TEI =
      """
<biblStruct xmlns="http://www.tei-c.org/ns/1.0">
  <analytic><title level="a">Тестовый анализ</title><author><persName>
  <forename type="first">Иван</forename><forename type="middle">Иванович</forename><surname>Иванов</surname>
  </persName></author></analytic>
  <monogr><title level="j">Научный журнал</title><imprint><date type="published" when="2020"/>
  <biblScope unit="volume">7</biblScope><biblScope unit="issue">2</biblScope>
  <biblScope unit="page" from="10" to="19"/></imprint></monogr><idno type="DOI">10.1000/test</idno>
</biblStruct>""";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void respond(String path, int code, String result) {
    server.createContext(
        path,
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          accept.set(exchange.getRequestHeaders().getFirst("Accept"));
          byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
          if (code != 204) exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private ResearchDataset.ParserInput input() {
    return new ResearchDataset.ParserInput("private-id", "Иванов И.И. Тест & проверка + строка");
  }

  @Test
  void grobidSendsOnlyCitationAndDisablesEnrichment() throws Exception {
    respond("/api/processCitation", 200, TEI);
    var parsed = new GrobidCitationParser(url, Duration.ofSeconds(2)).parse(input());
    assertEquals("application/xml", accept.get());
    assertTrue(contentType.get().startsWith("application/x-www-form-urlencoded"));
    String form = URLDecoder.decode(body.get(), StandardCharsets.UTF_8);
    assertTrue(form.contains(input().rawCitation()));
    assertTrue(form.contains("consolidateCitations=0"));
    assertFalse(form.contains("private-id"));
    assertFalse(form.contains("expected"));
    assertEquals("Иванов И. И.", parsed.fields().get(ReferenceField.AUTHORS));
    assertEquals("Тестовый анализ", parsed.fields().get(ReferenceField.TITLE));
    assertEquals("Научный журнал", parsed.fields().get(ReferenceField.CONTAINER_TITLE));
    assertEquals("10–19", parsed.fields().get(ReferenceField.PAGES));
    assertEquals("7", parsed.fields().get(ReferenceField.VOLUME));
    assertEquals("10.1000/test", parsed.fields().get(ReferenceField.DOI));
    assertEquals(TEI, parsed.rawResponse());
    assertFalse(parsed.fields().containsKey(ReferenceField.ACCESS_DATE));
  }

  @Test
  void handlesTeiWithoutNamespaceAndBooks() throws Exception {
    String tei =
        "<biblStruct><monogr><title>Книга</title><imprint><publisher>Издательство</publisher><pubPlace>Уфа</pubPlace><date"
            + " when='2019'/></imprint></monogr></biblStruct>";
    var f = new GrobidCitationParser(url, Duration.ofSeconds(2)).decode(tei).fields();
    assertEquals("Книга", f.get(ReferenceField.TITLE));
    assertEquals("Книга", f.get(ReferenceField.DOCUMENT_TYPE));
    assertEquals("Уфа", f.get(ReferenceField.CITY));
    assertFalse(f.containsKey(ReferenceField.PAGE_MEANING));
  }

  @Test
  void blocksXmlExternalEntitiesAndWrongDocuments() {
    var parser = new GrobidCitationParser(url, Duration.ofSeconds(2));
    assertThrows(
        CitationParser.ParseFailure.class,
        () ->
            parser.decode(
                "<!DOCTYPE x [<!ENTITY e SYSTEM"
                    + " 'file:///etc/passwd'>]><biblStruct>&e;</biblStruct>"));
    assertThrows(CitationParser.ParseFailure.class, () -> parser.decode("<html>Ошибка</html>"));
    assertThrows(
        CitationParser.ParseFailure.class,
        () -> parser.decode("<TEI><biblStruct/><biblStruct/></TEI>"));
  }

  @Test
  void emptyGrobidResponseIsNotAnErrorOrAFallback() throws Exception {
    respond("/api/processCitation", 204, "");
    assertTrue(
        new GrobidCitationParser(url, Duration.ofSeconds(2)).parse(input()).fields().isEmpty());
  }

  @Test
  void recordsHttpFailuresWithoutSilentlyUsingRules() {
    respond("/api/processCitation", 503, "busy");
    var e =
        assertThrows(
            CitationParser.ParseFailure.class,
            () -> new GrobidCitationParser(url, Duration.ofSeconds(2)).parse(input()));
    assertEquals("busy", e.rawResponse());
    assertTrue(e.getMessage().contains("503"));
  }

  private String ollamaResponse(JsonNode fields) throws Exception {
    return JSON.writeValueAsString(
        Map.of(
            "model",
            "qwen3:8b",
            "done",
            true,
            "done_reason",
            "stop",
            "message",
            Map.of("content", JSON.writeValueAsString(fields))));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode blankFields() {
    var node = JSON.createObjectNode();
    for (ReferenceField f : ReferenceField.values()) node.putNull(f.name());
    return node;
  }

  @Test
  void ollamaUsesSchemaNoThinkingAndNoGoldAndRetainsUnfoundedPrediction() throws Exception {
    var predicted = blankFields();
    predicted.put("TITLE", "Тест");
    predicted.put("PUBLISHER", "Выдуманный издатель");
    String response = ollamaResponse(predicted);
    respond("/api/chat", 200, response);
    var parsed = new OllamaCitationParser(url, "qwen3:8b", Duration.ofSeconds(2)).parse(input());
    JsonNode request = JSON.readTree(body.get());
    assertFalse(request.path("stream").asBoolean());
    assertFalse(request.path("think").asBoolean());
    assertEquals(0, request.path("options").path("temperature").asInt());
    assertEquals(42, request.path("options").path("seed").asInt());
    assertEquals(17, request.path("format").path("required").size());
    assertFalse(request.path("format").path("additionalProperties").asBoolean());
    JsonNode user = JSON.readTree(request.path("messages").get(1).path("content").asText());
    assertEquals(1, user.size());
    assertEquals(input().rawCitation(), user.path("rawCitation").asText());
    assertFalse(body.get().contains("private-id"));
    assertEquals("Выдуманный издатель", parsed.fields().get(ReferenceField.PUBLISHER));
    assertEquals(response, parsed.rawResponse());
  }

  @Test
  void rejectsMalformedOllamaJsonWithoutRegexRepair() throws Exception {
    respond("/api/chat", 200, "{\"done\":true,\"message\":{\"content\":\"```json {} ```\"}}");
    assertThrows(
        CitationParser.ParseFailure.class,
        () -> new OllamaCitationParser(url, "qwen3:8b", Duration.ofSeconds(2)).parse(input()));
  }

  @Test
  void rejectsUnknownAndNonStringOllamaFields() throws Exception {
    var parser = new OllamaCitationParser(url, "qwen3:8b", Duration.ofSeconds(2));
    var fields = blankFields();
    fields.put("YEAR", 2026);
    respond("/api/chat", 200, ollamaResponse(fields));
    assertThrows(CitationParser.ParseFailure.class, () -> parser.parse(input()));
    server.removeContext("/api/chat");
    fields.putNull("YEAR");
    fields.put("extra", "do not ignore");
    respond("/api/chat", 200, ollamaResponse(fields));
    assertThrows(CitationParser.ParseFailure.class, () -> parser.parse(input()));
    server.removeContext("/api/chat");
    fields.remove("extra");
    fields.remove("AUTHORS");
    respond("/api/chat", 200, ollamaResponse(fields));
    assertThrows(CitationParser.ParseFailure.class, () -> parser.parse(input()));
  }

  @Test
  void truncatedOrUnfinishedOllamaResponseFails() {
    respond("/api/chat", 200, "{\"done\":false,\"message\":{\"content\":\"{}\"}}");
    assertThrows(
        CitationParser.ParseFailure.class,
        () -> new OllamaCitationParser(url, "qwen3:8b", Duration.ofSeconds(2)).parse(input()));
  }

  @Test
  void capturesServerAndModelVersion() throws Exception {
    respond("/api/version", 200, "{\"version\":\"1.2.3\"}");
    respond(
        "/api/tags",
        200,
        "{\"models\":[{\"name\":\"qwen3:8b\",\"digest\":\"sha256:test\",\"details\":{\"quantization_level\":\"Q4_K_M\"}}]}");
    var metadata =
        new OllamaCitationParser(url, "qwen3:8b", Duration.ofSeconds(2)).serverMetadata();
    assertEquals("sha256:test", metadata.get("modelDigest"));
    assertEquals("1.2.3", metadata.get("ollamaVersion"));
  }
}
