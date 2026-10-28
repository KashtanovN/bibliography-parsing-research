package ru.ssau.bibliography;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FormattingProfileControllerTest {
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var formatter =
        new SharedCitationFormatter(
            Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneId.of("Europe/Samara")));
    mvc =
        MockMvcBuilders.standaloneSetup(new FormattingProfileController(formatter))
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .build();
  }

  @Test
  void exposesTwoProfilesAndSharedFieldContract() throws Exception {
    mvc.perform(get("/api/formatting-profiles/"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profiles.length()").value(2))
        .andExpect(jsonPath("$.fields.length()").value(17));
  }

  @Test
  void formatsNetworkResourceAndReturnsExplicitContext() throws Exception {
    var body =
        Map.of(
            "profile",
            "gost-7-0-108-2022",
            "fields",
            Map.of("DOCUMENT_TYPE", "Сайт", "TITLE", "Пример"),
            "context",
            Map.of("url", "https://example.test", "accessDate", "2026-10-27"));
    mvc.perform(
            post("/api/format-profile/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FORMATTED"))
        .andExpect(
            jsonPath("$.formatted")
                .value("Пример : сайт. — URL: https://example.test (дата обращения: 27.10.2026)."))
        .andExpect(jsonPath("$.context.accessDate").value("2026-10-27"));
  }

  @Test
  void missingContextDoesNotUseParserUrlOrReturnNormativeSuccess() throws Exception {
    var body =
        Map.of(
            "profile",
            "gost-7-0-108-2022",
            "fields",
            Map.of("DOCUMENT_TYPE", "Сайт", "TITLE", "Пример", "URL", "https://parser.example"),
            "context",
            Map.of());
    mvc.perform(
            post("/api/format-profile/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$['issues']['context.url']").exists())
        .andExpect(jsonPath("$['issues']['context.accessDate']").exists());
  }

  @Test
  void rejectsUnknownProfilesGoldContextAndInvalidFieldTypes() throws Exception {
    for (Object body :
        List.of(
            Map.of("profile", "APA", "fields", Map.of(), "context", Map.of()),
            Map.of("profile", "GOST108", "fields", Map.of("YEAR", 2026), "context", Map.of()),
            Map.of(
                "profile",
                "GOST108",
                "fields",
                Map.of(),
                "context",
                Map.of("expectedCitation", "Эталон")),
            Map.of(
                "profile", "GOST108", "fields", Map.of("hidden", "значение"), "context", Map.of()),
            Map.of(
                "profile",
                "GOST108",
                "fields",
                Map.of(),
                "context",
                Map.of("accessDate", "31.02.2026")),
            Map.of(
                "profile",
                "GOST108",
                "fields",
                Map.of(),
                "context",
                Map.of("paginationPreserved", "yes"))))
      mvc.perform(
              post("/api/format-profile/")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsBytes(body)))
          .andExpect(status().isBadRequest());
  }
}
