package taller.inventario;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Contexto del evento que se está procesando (sirve para generar eventos de salida deterministas). */
final class Ctx {
    final String eventId, occurredAt, key;
    int seq = 0;

    Ctx(String eventId, String occurredAt, String key) {
        this.eventId = eventId; this.occurredAt = occurredAt; this.key = key;
    }

    /** Contexto para operaciones que vienen de la API. Fecha en segundos exactos (formato del contrato). */
    static Ctx api() {
        return new Ctx("api-" + UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), "api");
    }
}
