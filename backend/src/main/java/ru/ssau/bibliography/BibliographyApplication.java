package ru.ssau.bibliography;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BibliographyApplication {
  public static void main(String[] args) {
    if (args.length == 1 && args[0].startsWith("--check-research-environment=")) {
      try {
        String file = args[0].substring("--check-research-environment=".length());
        if (file.isBlank()) throw new IllegalArgumentException("Укажите путь к Excel");
        ru.ssau.bibliography.research.ResearchEnvironmentCheck.run(java.nio.file.Path.of(file));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.err.println("Проверка среды прервана");
        System.exit(2);
      } catch (java.io.IOException | IllegalArgumentException e) {
        System.err.println("Ошибка готовности среды: " + e.getMessage());
        System.exit(2);
      }
      return;
    }
    if (Arrays.stream(args).anyMatch(a -> a.startsWith("--compare-runs="))) {
      try {
        ru.ssau.bibliography.research.CompareRunsCommand.run(args);
      } catch (java.io.IOException | IllegalArgumentException e) {
        System.err.println("Ошибка объединения результатов: " + e.getMessage());
        System.exit(2);
      }
      return;
    }
    if (Arrays.stream(args).anyMatch(a -> a.startsWith("--freeze-experiment="))) {
      try {
        ru.ssau.bibliography.research.ExperimentProtocol.run(args);
      } catch (java.io.IOException | IllegalArgumentException e) {
        System.err.println("Ошибка фиксации эксперимента: " + e.getMessage());
        System.exit(2);
      }
      return;
    }
    if (Arrays.stream(args).anyMatch(a -> a.startsWith("--quality-run="))) {
      try {
        ru.ssau.bibliography.research.QualityReviewCommand.run(args);
      } catch (java.io.IOException | IllegalArgumentException e) {
        System.err.println("Ошибка проверки качества: " + e.getMessage());
        System.exit(2);
      }
      return;
    }
    if (Arrays.stream(args).anyMatch(a -> a.startsWith("--format-run="))) {
      try {
        ru.ssau.bibliography.research.FormatRunCommand.run(args);
      } catch (java.io.IOException | IllegalArgumentException e) {
        System.err.println("Ошибка оформления: " + e.getMessage());
        System.exit(2);
      }
      return;
    }
    if (Arrays.stream(args).anyMatch(a -> a.startsWith("--evaluate-run="))) {
      try {
        ru.ssau.bibliography.research.EvaluateRunCommand.run(args);
      } catch (java.io.IOException | IllegalArgumentException e) {
        System.err.println("Ошибка оценки: " + e.getMessage());
        System.exit(2);
      }
      return;
    }
    if (Arrays.stream(args)
        .anyMatch(a -> a.startsWith("--parse-dataset=") || a.startsWith("--parse-text="))) {
      try {
        int status = ru.ssau.bibliography.research.ParseReferencesCommand.run(args);
        if (status != 0) System.exit(status);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.err.println("Разбор прерван");
        System.exit(2);
      } catch (java.io.IOException | IllegalArgumentException e) {
        System.err.println("Ошибка запуска разбора: " + e.getMessage());
        System.exit(2);
      }
      return;
    }
    if (args.length == 1 && args[0].startsWith("--check-dataset=")) {
      try {
        String file = args[0].substring("--check-dataset=".length());
        if (file.isBlank())
          throw new IllegalArgumentException("Укажите путь к Excel после --check-dataset=");
        ru.ssau.bibliography.research.DatasetCheckCommand.run(java.nio.file.Path.of(file));
      } catch (java.io.IOException | IllegalArgumentException e) {
        System.err.println("Ошибка загрузки датасета: " + e.getMessage());
        System.exit(2);
      }
      return;
    }
    var app = new SpringApplication(BibliographyApplication.class);
    boolean command = Arrays.stream(args).anyMatch(a -> a.startsWith("--load-standard="));
    if (command) app.setWebApplicationType(WebApplicationType.NONE);
    var context = app.run(args);
    if (command) context.close();
  }

  @Bean
  Clock clock() {
    return Clock.system(ZoneId.of("Europe/Samara"));
  }
}
