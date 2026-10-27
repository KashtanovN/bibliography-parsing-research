package ru.ssau.bibliography;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bibliography_citationstandard")
public class CitationStandard {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, unique = true, length = 80)
  public String key;

  @Column(nullable = false, length = 200)
  public String label;

  @Column(nullable = false, length = 40)
  public String version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  public JsonNode schema;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  protected CitationStandard() {}
}
