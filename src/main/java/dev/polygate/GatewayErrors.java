package dev.polygate;

import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@RestControllerAdvice
public class GatewayErrors {
  @ExceptionHandler(GatewayException.class)
  ResponseEntity<Map<String, Object>> handle(GatewayException error) {
    DetailedMetrics.rejected(error.code);
    return ResponseEntity.status(error.status)
        .header("x-polygate-error-contract", "v1")
        .body(
            Map.of(
                "error",
                Map.of(
                    "type",
                    error.type,
                    "code",
                    error.code,
                    "status",
                    error.status.value(),
                    "message",
                    error.getMessage(),
                    "retryable",
                    false)));
  }

  @ExceptionHandler({WebClientRequestException.class, TimeoutException.class})
  ResponseEntity<Map<String, Object>> transport(Exception error) {
    return handle(
        new GatewayException(
            HttpStatus.BAD_GATEWAY,
            "upstream_error",
            "upstream_transport",
            "Upstream connection failed"));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException error) {
    Throwable cause = error;
    while (cause != null) {
      if (cause instanceof GatewayTrafficFilter.BodyLimitException)
        return handle(
            new GatewayException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "invalid_request_error",
                "request_too_large",
                "Request body exceeds the configured limit"));
      cause = cause.getCause();
    }
    return handle(
        new GatewayException(
            HttpStatus.BAD_REQUEST,
            "invalid_request_error",
            "invalid_json",
            "Invalid JSON request body"));
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  ResponseEntity<Map<String, Object>> storage(org.springframework.dao.DataAccessException error) {
    return handle(
        new GatewayException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "server_error",
            "storage_unavailable",
            "Gateway storage is temporarily unavailable"));
  }

  @ExceptionHandler({
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<Map<String, Object>> arguments(Exception error) {
    return handle(
        new GatewayException(
            HttpStatus.BAD_REQUEST,
            "invalid_request_error",
            "invalid_parameter",
            "Invalid or missing request parameter"));
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<Map<String, Object>> unexpected(RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof TimeoutException || cause instanceof WebClientRequestException)
        return handle(UpstreamFailures.safe(error));
    return handle(
        new GatewayException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "server_error",
            "internal_error",
            "Gateway could not complete the request"));
  }
}
