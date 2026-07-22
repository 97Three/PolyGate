package dev.polygate;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SmartRoutingParityTest {
  @Test
  void sessionAffinityIsStableScopedAndCandidateSpecific() {
    String a = SmartRoutingService.digest("caller-a\0session"),
        b = SmartRoutingService.digest("caller-b\0session");
    double first = SmartRoutingService.affinity(a, "p:model");
    assertThat(first).isBetween(0.0, 1.0);
    assertThat(first).isEqualTo(SmartRoutingService.affinity(a, "p:model"));
    assertThat(first).isNotEqualTo(SmartRoutingService.affinity(b, "p:model"));
    assertThat(first).isNotEqualTo(SmartRoutingService.affinity(a, "p:other"));
  }

  @Test
  void candidateNormalizationUsesRangeAndHandlesTies() {
    assertThat(SmartRoutingService.inverse(2, 2, 10)).isEqualTo(1);
    assertThat(SmartRoutingService.inverse(10, 2, 10)).isZero();
    assertThat(SmartRoutingService.inverse(4, 4, 4)).isEqualTo(1);
  }
}
