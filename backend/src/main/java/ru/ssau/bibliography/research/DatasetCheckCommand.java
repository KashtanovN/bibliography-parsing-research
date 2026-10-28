package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import ru.ssau.bibliography.research.ResearchDataset.Split;

/** Offline command: no Spring context, database, network clients, or model calls. */
public final class DatasetCheckCommand {
  private DatasetCheckCommand() {}

  public static void run(Path path) throws java.io.IOException {
    var dataset = new ExcelDatasetLoader().load(path);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("file", path.toAbsolutePath().normalize().toString());
    result.put("sha256", dataset.sha256());
    result.put("records", dataset.references().size());
    result.put("dev", dataset.references(Split.DEV).size());
    result.put("test", dataset.references(Split.TEST).size());
    result.put(
        "sourceGroups", dataset.references().stream().map(r -> r.sourceGroup()).distinct().count());
    result.put(
        "ambiguousFieldPairs",
        dataset.references().stream().mapToInt(r -> r.ambiguousFields().size()).sum());
    result.put(
        "recordsWithAmbiguity",
        dataset.references().stream().filter(r -> !r.ambiguousFields().isEmpty()).count());
    Map<String, Long> types = new TreeMap<>();
    Map<String, Long> standards = new TreeMap<>();
    dataset
        .references()
        .forEach(
            r -> {
              types.merge(r.sourceType(), 1L, Long::sum);
              standards.merge(r.formatting().standard(), 1L, Long::sum);
            });
    result.put("sourceTypes", types);
    result.put("standards", standards);
    System.out.println(
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
  }
}
