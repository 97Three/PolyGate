package dev.polygate;

import java.util.List;

/** Supported rule forms: exact name, an explicit trailing wildcard, or '*'. */
final class PolicyRules {
  private PolicyRules() {}

  static boolean matches(String rule, String value) {
    if (rule == null || value == null) return false;
    rule = rule.trim();
    return rule.endsWith("*")
        ? value.startsWith(rule.substring(0, rule.length() - 1))
        : rule.equals(value);
  }

  static boolean model(List<String> rules, String requested, String resolved) {
    return rules.isEmpty()
        || rules.stream().anyMatch(rule -> matches(rule, requested) || matches(rule, resolved));
  }
}
