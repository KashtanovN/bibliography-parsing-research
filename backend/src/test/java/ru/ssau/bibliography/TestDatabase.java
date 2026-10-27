package ru.ssau.bibliography;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

final class TestDatabase {
  private static EmbeddedPostgres embedded;

  static synchronized void configure(DynamicPropertyRegistry registry) {
    String url = System.getenv("TEST_DATABASE_URL");
    if (url == null || url.isBlank()) {
      try {
        if (embedded == null) {
          embedded = EmbeddedPostgres.builder().start();
          Runtime.getRuntime()
              .addShutdownHook(
                  new Thread(
                      () -> {
                        try {
                          embedded.close();
                        } catch (Exception ignored) {
                        }
                      }));
        }
        String jdbcUrl = embedded.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
      } catch (Exception e) {
        throw new IllegalStateException("Cannot start isolated PostgreSQL for tests", e);
      }
    } else {
      registry.add("spring.datasource.url", () -> url);
      registry.add(
          "spring.datasource.username",
          () -> System.getenv().getOrDefault("TEST_DATABASE_USER", "bibliography"));
      registry.add(
          "spring.datasource.password",
          () -> System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
    }
  }
}
