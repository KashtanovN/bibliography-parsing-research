package ru.ssau.bibliography;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StandardRepository extends JpaRepository<CitationStandard, Long> {
  Optional<CitationStandard> findByKey(String key);
}
