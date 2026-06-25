package dev.polygate;

import org.springframework.http.HttpStatus;

class GatewayException extends RuntimeException {
  final HttpStatus status;
  final String type;
  final String code;
  final int upstreamStatus;

  GatewayException(HttpStatus status, String type, String code, String message) {
    this(status, type, code, message, 0);
  }

  GatewayException(
      HttpStatus status, String type, String code, String message, int upstreamStatus) {
    super(message);
    this.status = status;
    this.type = type;
    this.code = code;
    this.upstreamStatus = upstreamStatus;
  }
}
