package ru.ssau.bibliography.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import ru.ssau.bibliography.CitationFormatter;
import ru.ssau.bibliography.SharedCitationFormatter;

/** Fingerprints the actual loaded implementation, including nested and synthetic switch classes. */
final class ExperimentSnapshot {
  private ExperimentSnapshot() {}

  static Map<String, String> implementation() throws IOException {
    Map<String, String> hashes = new TreeMap<>();
    for (Class<?> type :
        List.of(
            ExperimentSnapshot.class,
            ExperimentProtocol.class,
            RecordComparisonWriter.class,
            ParseReferencesCommand.class,
            EvaluateRunCommand.class,
            SavedResearchRun.class,
            ExcelDatasetLoader.class,
            ResearchDataset.class,
            ReferenceField.class,
            CitationParser.class,
            RulesCitationParser.class,
            GrobidCitationParser.class,
            OllamaCitationParser.class,
            AnyStyleCitationParser.class,
            CompareRunsCommand.class,
            ParserHttpClient.class,
            FieldNormalization.class,
            ExtractionEvaluator.class,
            SharedCitationFormatter.class,
            CitationFormatter.class,
            FormatRunCommand.class,
            QualityReviewCommand.class)) capture(type, hashes);
    hashes.put("dependency:jackson", version(ObjectMapper.class));
    hashes.put("dependency:poi", org.apache.poi.Version.getVersion());
    return hashes;
  }

  private static void capture(Class<?> type, Map<String, String> hashes) throws IOException {
    String name = type.getName().replace('.', '/');
    if (hashes.containsKey(name + ".class")) return;
    resource(type, name + ".class", hashes);
    for (Class<?> nested : type.getDeclaredClasses()) capture(nested, hashes);
    // javac-generated switch maps do not appear in getDeclaredClasses().
    for (int i = 1; i <= 32; i++) resource(type, name + "$" + i + ".class", hashes);
  }

  private static void resource(Class<?> type, String name, Map<String, String> hashes)
      throws IOException {
    try (InputStream stream = type.getResourceAsStream("/" + name)) {
      if (stream != null) hashes.put(name, sha(stream.readAllBytes()));
    }
  }

  static Map<String, String> environment() {
    Map<String, String> env = new TreeMap<>();
    for (String property :
        List.of("java.version", "java.vendor", "java.vm.name", "os.name", "os.arch", "os.version"))
      env.put(property, System.getProperty(property, "unavailable"));
    env.put("availableProcessors", "" + Runtime.getRuntime().availableProcessors());
    return env;
  }

  private static String version(Class<?> type) {
    return Objects.toString(type.getPackage().getImplementationVersion(), "unavailable");
  }

  static String sha(Path path) throws IOException {
    return sha(Files.readAllBytes(path));
  }

  static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
