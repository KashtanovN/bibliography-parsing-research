package ru.ssau.bibliography;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.configure(registry);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired StandardService standards;
  @Autowired StandardRepository repository;
  @Autowired ApiRequestFilter filter;
  @MockitoBean MetadataClient client;

  @BeforeEach
  void resetState() {
    filter.clearLimits();
    standards.load(FormatterTest.SCHEMA.deepCopy(), false);
    reset(client);
  }

  org.springframework.test.web.servlet.ResultActions postJson(String path, Object body)
      throws Exception {
    return mvc.perform(
        post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)));
  }

  @Test
  void schemaAndHealth() throws Exception {
    mvc.perform(get("/api/schema/"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.types.length()").value(8));
    mvc.perform(get("/api/health/"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"));
  }

  @Test
  void publicFormatAndFieldErrors() throws Exception {
    postJson("/api/format/", Map.of("source_type", "book", "fields", FormatterTest.book()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.formatted")
                .value("Иванов И. И., Петров П. П. Пример книги. — Москва : Наука, 2024."));
    postJson("/api/format/", Map.of("source_type", "book", "fields", Map.of()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.title").exists());
  }

  @Test
  void invalidPayloads() throws Exception {
    for (Object body :
        new Object[] {
          Map.of(),
          Map.of("fields", new Object[] {}, "source_type", "book"),
          Map.of("identifier", ""),
          new Object[] {},
          "text"
        }) postJson("/api/format/", body).andExpect(status().isBadRequest());
    postJson("/api/format/", Map.of("source_type", "wrong", "fields", Map.of()))
        .andExpect(status().isBadRequest());
    postJson("/api/import/", Map.of("identifier", "x".repeat(513)))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/format/").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rulesUpdateWithoutRestart() throws Exception {
    var schema = FormatterTest.SCHEMA.deepCopy();
    ((ObjectNode) schema.path("types").path("book").path("parts").get(0)).put("prefix", "Тест: ");
    standards.load(schema, false);
    postJson("/api/format/", Map.of("source_type", "book", "fields", FormatterTest.book()))
        .andExpect(jsonPath("$.formatted").value(org.hamcrest.Matchers.startsWith("Тест: ")));
  }

  @Test
  void startupKeepsReviewedRules() {
    var schema = FormatterTest.SCHEMA.deepCopy();
    ((ObjectNode) schema).put("label", "Проверенный профиль");
    standards.load(schema, false);
    standards.load(FormatterTest.SCHEMA.deepCopy(), true);
    assertThat(standards.current().label).isEqualTo("Проверенный профиль");
  }

  @Test
  void invalidSchemaDoesNotOverwriteRules() {
    var schema = FormatterTest.SCHEMA.deepCopy();
    ((ObjectNode) schema).remove("types");
    assertThatThrownBy(() -> standards.load(schema, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(standards.current().schema.path("types").size()).isEqualTo(8);
  }

  @Test
  void missingRulesHealth() throws Exception {
    repository.deleteAll();
    mvc.perform(get("/api/health/"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("not_ready"));
  }

  @Test
  void completeDoi() throws Exception {
    when(client.get(any()))
        .thenReturn(
            mapper.readTree(
                """
{"message":{"type":"journal-article","title":["A <i>test</i>"],"author":[{"family":"Ivanov","given":"Ivan Ivanovich"}],"container-title":["Journal"],"published":{"date-parts":[[2024]]},"volume":"5","issue":"2","page":"S1–S4"}}
"""));
    postJson("/api/import/", Map.of("identifier", "10.1234/test"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.formatted").value(org.hamcrest.Matchers.startsWith("Ivanov I. I. A test")))
        .andExpect(jsonPath("$.errors").isEmpty())
        .andExpect(jsonPath("$.provider").value("Crossref"));
    verify(client).get(URI.create("https://api.crossref.org/works/10.1234%2Ftest"));
  }

  @Test
  void incompleteDoiDoesNotInvent() throws Exception {
    when(client.get(any()))
        .thenReturn(
            mapper.readTree("{\"message\":{\"type\":\"journal-article\",\"title\":[\"Title\"]}}"));
    postJson("/api/import/", Map.of("identifier", "10.1234/test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.formatted").isEmpty())
        .andExpect(jsonPath("$.errors.volume").exists())
        .andExpect(jsonPath("$.fields.year").value(""));
  }

  @Test
  void partialIsbn() throws Exception {
    when(client.get(any()))
        .thenReturn(
            mapper.readTree(
                """
{"ISBN:9780140328721":{"title":"Fantastic Mr. Fox","authors":[{"name":"Roald Dahl"}],"publish_date":"October 1, 1988","publishers":[{"name":"Puffin"}],"number_of_pages":96}}
"""));
    postJson("/api/import/", Map.of("identifier", "9780140328721"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors.city").exists())
        .andExpect(jsonPath("$.fields.authors").value("Roald Dahl"))
        .andExpect(jsonPath("$.fields.year").value("1988"));
  }

  @Test
  void isbnNotFound() throws Exception {
    when(client.get(any())).thenReturn(mapper.createObjectNode());
    postJson("/api/import/", Map.of("identifier", "9780140328721"))
        .andExpect(status().isNotFound());
  }

  @Test
  void unsupportedPublication() throws Exception {
    when(client.get(any())).thenReturn(mapper.readTree("{\"message\":{\"type\":\"dataset\"}}"));
    postJson("/api/import/", Map.of("identifier", "10.1234/test"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void malformedProvider() throws Exception {
    when(client.get(any())).thenReturn(mapper.readTree("{\"message\":[]}"));
    postJson("/api/import/", Map.of("identifier", "10.1234/test"))
        .andExpect(status().isBadGateway());
  }

  @Test
  void providerUnavailable() throws Exception {
    when(client.get(any())).thenThrow(new ApiProblem(503, "Источник данных недоступен."));
    postJson("/api/import/", Map.of("identifier", "10.1234/test"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").exists());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://127.0.0.1/secret",
        "file:///etc/passwd",
        "9780140328722",
        "10.1234/bad doi"
      })
  void invalidIdentifiersNeverFetch(String identifier) throws Exception {
    postJson("/api/import/", Map.of("identifier", identifier)).andExpect(status().isBadRequest());
    verifyNoInteractions(client);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"1234567", "ABCDEF", "EDN:ABCDEF", "https://elibrary.ru/item.asp?id=12345"})
  void elibraryExplicitlyUnavailable(String identifier) throws Exception {
    postJson("/api/import/", Map.of("identifier", identifier))
        .andExpect(status().isNotImplemented());
    verifyNoInteractions(client);
  }

  @Test
  void importRateLimit() throws Exception {
    for (int i = 0; i < 20; i++)
      postJson("/api/import/", Map.of("identifier", "bad")).andExpect(status().isBadRequest());
    postJson("/api/import/", Map.of("identifier", "bad"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"));
  }

  @Test
  void globalRateLimit() throws Exception {
    for (int i = 0; i < 120; i++) mvc.perform(get("/api/health/")).andExpect(status().isOk());
    mvc.perform(get("/api/health/")).andExpect(status().isTooManyRequests());
  }

  @Test
  void oversizedBodyAndMethods() throws Exception {
    mvc.perform(
            post("/api/format/").contentType(MediaType.APPLICATION_JSON).content("x".repeat(32769)))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(get("/api/format/")).andExpect(status().isMethodNotAllowed());
    mvc.perform(get("/api/missing/")).andExpect(status().isNotFound());
  }
}
