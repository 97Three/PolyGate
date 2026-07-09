package dev.polygate;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "polygate.database.enabled", havingValue = "true")
class AdminCredentialsController {
  private final AdminAuthService auth;
  private final ProviderCredentialService credentials;
  private final AdminModelsController providers;

  AdminCredentialsController(
      AdminAuthService auth,
      ProviderCredentialService credentials,
      AdminModelsController providers) {
    this.auth = auth;
    this.credentials = credentials;
    this.providers = providers;
  }

  private void write(HttpHeaders headers) {
    auth.requireWrite(headers);
    auth.requireAdmin(headers);
  }

  @PostMapping("/admin/providers/{provider}/credentials")
  Object create(
      @RequestHeader HttpHeaders headers,
      @PathVariable String provider,
      @RequestBody JsonNode input) {
    write(headers);
    credentials.create(provider, input);
    return java.util.Map.of("provider", providers.provider(provider));
  }

  @PutMapping("/admin/providers/{provider}/credentials/{id}")
  Object update(
      @RequestHeader HttpHeaders headers,
      @PathVariable String provider,
      @PathVariable String id,
      @RequestBody JsonNode input) {
    write(headers);
    credentials.update(provider, id, input);
    return java.util.Map.of("provider", providers.provider(provider));
  }

  @PostMapping("/admin/providers/{provider}/credentials/{id}/select")
  Object select(
      @RequestHeader HttpHeaders headers, @PathVariable String provider, @PathVariable String id) {
    write(headers);
    credentials.select(provider, id);
    return java.util.Map.of("provider", providers.provider(provider));
  }

  @DeleteMapping("/admin/providers/{provider}/credentials/{id}")
  Object delete(
      @RequestHeader HttpHeaders headers, @PathVariable String provider, @PathVariable String id) {
    write(headers);
    credentials.delete(provider, id);
    return java.util.Map.of("provider", providers.provider(provider));
  }

  @PutMapping("/admin/providers/{provider}/credential-pool")
  Object mode(
      @RequestHeader HttpHeaders headers,
      @PathVariable String provider,
      @RequestBody JsonNode input) {
    write(headers);
    credentials.mode(provider, input.path("mode").asText());
    return java.util.Map.of("provider", providers.provider(provider));
  }
}
