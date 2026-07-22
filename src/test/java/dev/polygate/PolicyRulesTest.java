package dev.polygate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyRulesTest {
  @Test
  void exactPublicModelDoesNotAuthorizeAnotherModelOrProvider() {
    var rules = List.of("approved:allowed");
    assertThat(PolicyRules.model(rules, "approved:allowed", "allowed")).isTrue();
    assertThat(PolicyRules.model(rules, "approved:private", "private")).isFalse();
    assertThat(PolicyRules.model(rules, "other:allowed", "allowed")).isFalse();
  }

  @Test
  void ExplicitUpstreamRuleAllowsItsAliasesButNotOtherResolvedModels() {
    var rules = List.of("allowed");
    assertThat(PolicyRules.model(rules, "approved:allowed", "allowed")).isTrue();
    assertThat(PolicyRules.model(rules, "friendly-alias", "allowed")).isTrue();
    assertThat(PolicyRules.model(rules, "friendly-alias", "private")).isFalse();
  }

  @Test
  void WildcardsRequireExplicitTrailingSyntax() {
    assertThat(PolicyRules.model(List.of("approved:*"), "approved:allowed", "allowed")).isTrue();
    assertThat(PolicyRules.model(List.of("approved:*"), "other:allowed", "allowed")).isFalse();
    assertThat(PolicyRules.matches("a*b", "axb")).isFalse();
    assertThat(PolicyRules.matches("allowed", "allowed-private")).isFalse();
  }
}
