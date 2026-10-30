package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResearchScriptsTest {
  @TempDir Path temporary;
  private final Path root = Path.of("..").toAbsolutePath().normalize();
  private final ObjectMapper json = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"rules,grobid,ollama,anystyle", "rules,grobid,ollama", "anystyle"})
  void monitorUsesActualMethodsAndRecordCount(String selected) throws Exception {
    Path run = temporary.resolve("run with spaces");
    Files.createDirectories(run);
    Map<String, Object> methods = new LinkedHashMap<>();
    for (String method : selected.split(",")) {
      methods.put(method, Map.of());
      Files.writeString(run.resolve(method + ".jsonl"),
          "{\"status\":\"SUCCESS\",\"durationMillis\":1}\n".repeat(2));
    }
    json.writerWithDefaultPrettyPrinter().writeValue(run.resolve("run.json").toFile(),
        Map.of("records", 2, "methods", methods));
    Files.writeString(run.resolve("summary.json"), "{}");
    var p = new ProcessBuilder("bash", root.resolve("scripts/watch-test-progress.sh").toString(),
        run.toString(), "--once").redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, p.waitFor(), output);
    int count = methods.size() * 2;
    assertTrue(output.contains("Всего завершено вызовов: " + count + " / " + count), output);
    assertEquals(methods.containsKey("anystyle"), output.contains("AnyStyle"), output);
    if (!methods.containsKey("ollama")) assertFalse(output.contains("Ориентир для Qwen"), output);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3, 2})
  void pipelineUsesSelectedJarAndContinuesOnlyForCompletedExtraction(int parseExit) throws Exception {
    Path bin = temporary.resolve("bin");
    Files.createDirectories(bin);
    Path worker = bin.resolve("java");
    Files.writeString(worker, """
#!/bin/bash
printf 'cwd=%s args=%s\n' "$PWD" "$*" >> "$RESEARCH_TEST_LOG"
for task_arg in "$@"; do
  if [[ "$task_arg" == --output=* ]]; then
    task_out=${task_arg#--output=}
    if [[ "$RESEARCH_PARSE_EXIT" != 2 ]]; then
      mkdir -p "$task_out"
      printf '{}\n' > "$task_out/summary.json"
    fi
    exit "$RESEARCH_PARSE_EXIT"
  fi
done
exit 0
""");
    assertTrue(worker.toFile().setExecutable(true));
    Path protocol = temporary.resolve("frozen protocol");
    Files.createDirectories(protocol);
    Files.writeString(protocol.resolve("application.jar"), "test stub; never executed");
    Path log = temporary.resolve("calls.log");
    var pb = new ProcessBuilder("bash", root.resolve("scripts/run-final-test.sh").toString(),
        "test output", "frozen protocol").directory(temporary.toFile()).redirectErrorStream(true);
    pb.environment().put("PATH", bin + java.io.File.pathSeparator + System.getenv("PATH"));
    pb.environment().put("RESEARCH_TEST_LOG", log.toString());
    pb.environment().put("RESEARCH_PARSE_EXIT", String.valueOf(parseExit));
    var p = pb.start();
    String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(parseExit == 2 ? 2 : 0, p.waitFor(), output);
    List<String> calls = Files.readAllLines(log);
    assertEquals(parseExit == 2 ? 1 : 4, calls.size(), output);
    assertTrue(calls.get(0).contains("cwd=" + root.resolve("backend")), calls.toString());
    assertTrue(calls.get(0).contains("-jar " + protocol.toRealPath().resolve("application.jar")), calls.toString());
    assertTrue(calls.get(0).contains("--method=all"), calls.toString());
    assertEquals(parseExit == 2 ? "FAILED" : "COMPLETED",
        Files.readString(temporary.resolve("test output.pipeline-status")).strip());
  }

  @Test
  void devPipelineUsesAllMethodsWithoutTestLimitOrAutomaticFreeze() throws Exception {
    Path project = temporary.resolve("project with spaces");
    Files.createDirectories(project.resolve("scripts"));
    Files.createDirectories(project.resolve("backend/target"));
    Files.copy(root.resolve("scripts/run-dev.sh"), project.resolve("scripts/run-dev.sh"));
    Files.writeString(project.resolve("backend/target/bibliography-1.0.0.jar"), "stub");
    Path bin = temporary.resolve("dev-bin");
    Files.createDirectories(bin);
    Path worker = bin.resolve("java");
    Files.writeString(worker, """
#!/bin/bash
printf '%s\n' "$*" >> "$RESEARCH_TEST_LOG"
for arg in "$@"; do
  if [[ "$arg" == --output=* ]]; then
    mkdir -p "${arg#--output=}"
    printf '{}\n' > "${arg#--output=}/summary.json"
  fi
done
""");
    assertTrue(worker.toFile().setExecutable(true));
    Path log = temporary.resolve("dev.log");
    Path run = project.resolve("research-runs/dev-four-methods");
    var pb = new ProcessBuilder("bash", project.resolve("scripts/run-dev.sh").toString())
        .redirectErrorStream(true);
    pb.environment().put("PATH", bin + java.io.File.pathSeparator + System.getenv("PATH"));
    pb.environment().put("RESEARCH_TEST_LOG", log.toString());
    var process = pb.start();
    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    List<String> calls = Files.readAllLines(log);
    assertEquals(3, calls.size());
    assertTrue(calls.get(0).contains("--method=all --split=dev"), calls.toString());
    assertTrue(calls.get(0).contains("--parse-dataset=" + project.resolve("Dataset.xlsx")));
    assertTrue(calls.get(1).contains("--evaluate-run="));
    assertTrue(calls.get(2).contains("--format-run="));
    assertFalse(String.join("\n", calls).contains("--limit"));
    assertFalse(String.join("\n", calls).contains("--freeze-experiment"));
    assertFalse(Files.exists(run.resolve("frozen-protocol")));
    assertEquals("COMPLETED", Files.readString(Path.of(run + ".pipeline-status")).strip());
    var again = pb.start();
    String repeated = new String(again.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(2, again.waitFor(), repeated);
    assertEquals(calls, Files.readAllLines(log));
  }

  @Test
  void missingProtocolIsRejectedBeforeCreatingRun() throws Exception {
    Path run = temporary.resolve("absent-run");
    var p = new ProcessBuilder("bash", root.resolve("scripts/run-final-test.sh").toString(),
        run.toString(), temporary.resolve("absent-protocol").toString())
        .redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(2, p.waitFor(), output);
    assertFalse(Files.exists(run));
    assertFalse(Files.exists(temporary.resolve("absent-run.pipeline-status")));
  }
}
