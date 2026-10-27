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
