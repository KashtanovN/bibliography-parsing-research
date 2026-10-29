package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnyStyleCitationParserTest {
  @TempDir Path temp;

  @Test
  void mapsOnlyReturnedFieldsPreservesAuthorOrderAndLeavesUnsupportedEmpty() throws Exception {
    try (var p = new AnyStyleCitationParser(Path.of("unused"), Duration.ofSeconds(1))) {
      var fields =
          p.decode(
                  """
{"result":[{"author":[{"family":"Иванов","given":"Иван Иванович"},{"family":"Петров","given":"П.П."}],
"title":["Название"],"container-title":["Журнал"],"date":["2021-03-04"],"volume":["4"],"issue":["2"],
"pages":["10–20"],"doi":["10.1234/x"],"url":["https://example.org"],"type":"article-journal",
"editor":[{"family":"Редактор"}],"note":["EDN: OTHER"]}]}
""")
              .fields();
      assertEquals("Иванов И. И. | Петров П. П.", fields.get(ReferenceField.AUTHORS));
      assertEquals("2021", fields.get(ReferenceField.YEAR));
      assertEquals("Журнальная статья", fields.get(ReferenceField.DOCUMENT_TYPE));
      assertEquals("Указатель страниц", fields.get(ReferenceField.PAGE_MEANING));
      assertEquals("10.1234/x", fields.get(ReferenceField.DOI));
      assertFalse(fields.containsKey(ReferenceField.EDN));
      assertFalse(fields.containsKey(ReferenceField.ACCESS_DATE));
    }
  }

  @Test
  void mapsPageRoleFromModelTypeWithoutGuessingMissingBookType() throws Exception {
    try (var p = new AnyStyleCitationParser(Path.of("unused"), Duration.ofSeconds(1))) {
      assertEquals(
          "Общий объём",
          p.decode("{\"result\":[{\"pages\":[\"200\"],\"type\":\"book\"}]}")
              .fields()
              .get(ReferenceField.PAGE_MEANING));
      var fields =
          p.decode("{\"result\":[{\"title\":[\"Книга\"],\"publisher\":[\"Наука\"],\"type\":null}]}")
              .fields();
      assertFalse(fields.containsKey(ReferenceField.DOCUMENT_TYPE));
      assertFalse(fields.containsKey(ReferenceField.PAGE_MEANING));
      for (String bad :
          new String[] {
            "not-json",
            "{\"result\":[]}",
            "{\"result\":[{},{}]}",
            "{\"error\":\"failed\"}",
            "{\"result\":[{\"title\":123}]}"
          }) assertThrows(CitationParser.ParseFailure.class, () -> p.decode(bad));
    }
  }

  @Test
  void workerReceivesOnlyRawTextAndTimesOutWithoutHanging() throws Exception {
    Path worker = temp.resolve("worker.sh");
    Files.writeString(
        worker,
        """
#!/bin/sh
printf '%%s\\n' '{"protocol":"anystyle-jsonl-v1","version":"fake","modelSha256":"%s","workerSha256":"%s","assetsSha256":{"fake":"abc"}}'
read request
printf '%%s' "$request" > '%s'
printf '%%s\\n' '{"result":[{"title":["Название"]}]}'
"""
            .formatted("a".repeat(64), "b".repeat(64), temp.resolve("input.json")));
    assertTrue(worker.toFile().setExecutable(true));
    try (var p = new AnyStyleCitationParser(worker, Duration.ofSeconds(5))) {
      assertTrue(p.serverMetadata().containsKey("runtime"));
      assertEquals(
          "Название",
          p.parse(new ResearchDataset.ParserInput("ID_SECRET", "исходная строка"))
              .fields()
              .get(ReferenceField.TITLE));
      assertEquals(
          "{\"citation\":\"исходная строка\"}", Files.readString(temp.resolve("input.json")));
    }
    Files.writeString(worker, "#!/bin/sh\nexec sleep 10\n");
    try (var p = new AnyStyleCitationParser(worker, Duration.ofMillis(100))) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(3), () -> assertThrows(java.io.IOException.class, p::serverMetadata));
    }
  }
}
