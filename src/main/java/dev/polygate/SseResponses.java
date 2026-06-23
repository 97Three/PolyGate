package dev.polygate;

import java.util.concurrent.atomic.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

final class SseResponses {
  private SseResponses() {}

  static ResponseEntity<?> adapt(ResponseEntity<?> response) {
    if (!(response.getBody() instanceof Flux<?> flux)) return response;
    SseEmitter emitter = new SseEmitter(0L);
    AtomicReference<Disposable> subscription = new AtomicReference<>();
    AtomicBoolean ended = new AtomicBoolean();
    Runnable cancel =
        () -> {
          ended.set(true);
          Disposable active = subscription.get();
          if (active != null) active.dispose();
        };
    emitter.onCompletion(cancel);
    emitter.onTimeout(cancel);
    emitter.onError(error -> cancel.run());
    Disposable active =
        flux.publishOn(Schedulers.boundedElastic())
            .subscribe(
                value -> {
                  if (!(value instanceof ServerSentEvent<?> event))
                    throw new IllegalStateException("Invalid SSE event type");
                  try {
                    SseEmitter.SseEventBuilder outgoing = SseEmitter.event();
                    if (event.event() != null) outgoing.name(event.event());
                    if (event.id() != null) outgoing.id(event.id());
                    if (event.comment() != null) outgoing.comment(event.comment());
                    if (event.data() != null) outgoing.data(event.data());
                    emitter.send(outgoing);
                  } catch (Exception error) {
                    cancel.run();
                    emitter.completeWithError(error);
                  }
                },
                emitter::completeWithError,
                emitter::complete);
    subscription.set(active);
    if (ended.get()) active.dispose();
    return ResponseEntity.status(response.getStatusCode())
        .headers(response.getHeaders())
        .body(emitter);
  }
}
