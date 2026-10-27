package ru.ssau.bibliography;

import static org.assertj.core.api.Assertions.*;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest
class DatabaseInitializationTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    TestDatabase.configure(registry);
  }

  @Autowired DataSource dataSource;

  @Test
  void emptySchemaIsInitializedAndRepeatedStartupKeepsRules() {
    var jdbc = new JdbcTemplate(dataSource);
    String schema = "initialization_test";
    jdbc.execute("CREATE SCHEMA " + schema);
    try {
      var flyway =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(schema)
              .defaultSchema(schema)
              .load();
      assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
      jdbc.update(
          "INSERT INTO "
              + schema
              + ".bibliography_citationstandard(key,label,version,schema,updated_at) VALUES"
              + " (?,?,?,?::jsonb,now())",
          StandardService.KEY,
          "Проверено библиотекой",
          "2.0",
          FormatterTest.SCHEMA.toString());
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT label FROM " + schema + ".bibliography_citationstandard", String.class))
          .isEqualTo("Проверено библиотекой");
    } finally {
      jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
