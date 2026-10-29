package ru.ssau.bibliography.research;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompareRunsCommandTest {
  @Test
  void savedJsonCountsCompareExactlyIndependentOfJacksonIntegerNodeWidth() throws Exception {
    var json = new ObjectMapper();
    assertTrue(
        CompareRunsCommand.sameJson(
            Map.of("tp", 3763L, "f1", 0.9535031040162169),
            json.readTree("{\"tp\":3763,\"f1\":0.9535031040162169}")));
    assertFalse(CompareRunsCommand.sameJson(Map.of("tp", 3763L), json.readTree("{\"tp\":3764}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> CompareRunsCommand.run(new String[] {"--compare-runs=missing"}));
  }
}
