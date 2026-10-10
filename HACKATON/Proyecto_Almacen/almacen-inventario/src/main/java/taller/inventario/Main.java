package taller.inventario;

import java.nio.file.*;
import java.util.*;

/**
 * Uso:
 *   --file data/events.jsonl   lee los eventos de un archivo (sin broker)
 *   --kafka localhost:19092    lee de Kafka/Redpanda y publica ahí inventory.events
 *   --reset                    borra la base antes de empezar (y relee todo desde el inicio)
 *   --db inventario.db  --port 8000  --out out
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Map<String, String> o = parse(args);
        String dbPath = o.getOrDefault("db", "inventario.db");
        if (o.containsKey("reset")) {
            Files.deleteIfExists(Path.of(dbPath));
            Files.deleteIfExists(Path.of(dbPath + "-wal"));
            Files.deleteIfExists(Path.of(dbPath + "-shm"));
        }
        Db db = new Db(dbPath);
        Outbox outbox = new Outbox(db);
        Processor proc = new Processor(db, outbox);
        Floor floor = new Floor(db, outbox, proc.stock());

        boolean kafka = o.containsKey("kafka");
        Publisher pub = kafka ? new KafkaPublisher(o.get("kafka")) : new FilePublisher(o.getOrDefault("out", "out"));
        EventSource src;
        if (kafka) {
            // con --reset se usa un grupo nuevo para releer los tópicos desde el offset 0
            String group = o.containsKey("reset") ? "inventory-" + UUID.randomUUID() : "inventory-service";
            src = new KafkaEventSource(o.get("kafka"), group);
        } else {
            src = new FileEventSource(o.getOrDefault("file", "data/events.jsonl"));
        }

        Thread drain = new Thread(() -> {
            while (true) {
                try { outbox.drain(pub); Thread.sleep(300); }
                catch (InterruptedException e) { return; }
                catch (Exception e) { System.err.println("Error publicando salida: " + e); try { Thread.sleep(2000); } catch (InterruptedException ie) { return; } }
            }
        }, "outbox-drain");
        drain.setDaemon(true);
        drain.start();

        Api.start(Integer.parseInt(o.getOrDefault("port", "8000")), db, proc, floor);
        System.out.println("API en http://localhost:" + o.getOrDefault("port", "8000"));

        long[] n = {0};
        src.run(raw -> {
            safe(proc, pub, raw);
            if (++n[0] % 1000 == 0) System.out.println("Eventos procesados: " + n[0]);
        });
        System.out.println("Fuente terminada. Eventos procesados: " + n[0]);
    }

    /** Reintenta 3 veces; si sigue fallando, el evento va a inventory.dlq y el consumo continúa. */
    static void safe(Processor proc, Publisher pub, String raw) {
        Exception last = null;
        for (int i = 1; i <= 3; i++) {
            try { proc.handle(raw); return; }
            catch (Exception e) { last = e; try { Thread.sleep(100L * i); } catch (InterruptedException ie) { break; } }
        }
        System.err.println("Evento a DLQ: " + last);
        try { pub.publish("inventory.dlq", keyOf(raw), raw); } catch (Exception e) { System.err.println("No se pudo enviar a DLQ: " + e); }
    }

    /** La key del DLQ es la misma del evento original. */
    private static String keyOf(String raw) {
        try { return Json.parse(raw).path("key").asText(null); } catch (Exception e) { return null; }
    }

    private static Map<String, String> parse(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < a.length; i++) {
            if (!a[i].startsWith("--")) continue;
            String k = a[i].substring(2);
            if (i + 1 < a.length && !a[i + 1].startsWith("--")) m.put(k, a[++i]); else m.put(k, "true");
        }
        return m;
    }
}
