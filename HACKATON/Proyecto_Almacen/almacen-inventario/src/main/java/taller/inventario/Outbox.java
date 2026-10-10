package taller.inventario;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Eventos de salida. emit() los guarda en la MISMA transacción que el cambio de inventario
 * (si la transacción falla, no sale ningún evento) y drain() los publica después.
 */
public final class Outbox {
    private final Db db;
    public Outbox(Db db) { this.db = db; }

    void emit(Ctx c, String type, String key, ObjectNode data) {
        // ID determinista: el mismo evento de entrada siempre produce los mismos IDs de salida.
        String id = UUID.nameUUIDFromBytes((c.eventId + "#" + (c.seq++)).getBytes(StandardCharsets.UTF_8)).toString();
        ObjectNode env = Json.obj();
        env.put("event_id", id);
        env.put("event_type", type);
        env.put("event_version", 1);
        env.put("occurred_at", c.occurredAt);
        env.put("source", "inventory");
        env.put("key", key);
        env.set("data", data);
        db.update("INSERT OR IGNORE INTO outbox(event_id,event_type,key,payload) VALUES(?,?,?,?)",
                id, type, key, env.toString());
    }

    /** Publica lo pendiente. Se llama periódicamente desde otro hilo. */
    public int drain(Publisher pub) throws Exception {
        List<Map<String, Object>> rows = db.tx(d ->
                d.query("SELECT id, key, payload FROM outbox WHERE published=0 ORDER BY id LIMIT 500"));
        for (Map<String, Object> r : rows) {
            pub.publish("inventory.events", (String) r.get("key"), (String) r.get("payload"));
            db.tx(d -> d.update("UPDATE outbox SET published=1 WHERE id=?", r.get("id")));
        }
        return rows.size();
    }
}
