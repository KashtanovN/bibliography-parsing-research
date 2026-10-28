package ru.ssau.bibliography.research;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Extraction methods receive no expected fields, formatting context, or dataset split. */
public interface CitationParser {
  String name();

  Map<String, Object> configuration();

  ParsedCitation parse(ResearchDataset.ParserInput input) throws IOException, InterruptedException;

  record ParsedCitation(
      Map<ReferenceField, String> fields, List<String> warnings, String rawResponse) {
    public ParsedCitation {
      fields = Map.copyOf(fields);
      warnings = List.copyOf(warnings);
    }
  }

  final class ParseFailure extends IOException {
    private final String rawResponse;

    public ParseFailure(String message, String rawResponse) {
      super(message);
      this.rawResponse = rawResponse;
    }

    public String rawResponse() {
      return rawResponse;
    }
  }
}
