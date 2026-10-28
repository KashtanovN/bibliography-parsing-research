package ru.ssau.bibliography.research;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ResearchDataset(String sha256, List<AnnotatedReference> references) {
  public ResearchDataset {
    references = List.copyOf(references);
  }

  public enum Split {
    DEV,
    TEST
  }

  /** The entire input visible to a citation parser. Never pass AnnotatedReference to a parser. */
  public record ParserInput(String id, String rawCitation) {}

  /** Common input for the separate formatting stage, not input to an extraction method. */
  public record FormattingContext(
      String standard, String selectedUrl, LocalDate accessDate, String expectedCitation) {}

  public record AnnotatedReference(
      ParserInput input,
      Split split,
      String sourceGroup,
      String sourceType,
      Map<ReferenceField, String> expectedFields,
      Set<ReferenceField> ambiguousFields,
      FormattingContext formatting) {
    public AnnotatedReference {
      expectedFields = Map.copyOf(expectedFields);
      ambiguousFields = Set.copyOf(ambiguousFields);
    }
  }

  public List<AnnotatedReference> references(Split split) {
    return references.stream().filter(reference -> reference.split() == split).toList();
  }

  public List<ParserInput> parserInputs(Split split) {
    return references(split).stream().map(AnnotatedReference::input).toList();
  }
}
