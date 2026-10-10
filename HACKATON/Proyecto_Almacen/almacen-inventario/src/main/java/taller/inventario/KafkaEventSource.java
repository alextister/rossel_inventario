package taller.inventario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Consume los tópicos del taller.
 * Regla 22 del contrato: primero shop.catalog hasta el final y solo después los 4 tópicos juntos.
 * Además, dentro de cada lote se procesa primero el catálogo y al final las compras, y si el lote
 * trae compras se espera un instante por si el catálogo recién publicado aún viene en camino
 * (entre tópicos no hay orden garantizado).
 */
public final class KafkaEventSource implements EventSource {
    static final List<String> TOPICS =
            List.of("shop.catalog", "shop.work_orders", "shop.inspections", "shop.purchasing");
    private final String bootstrap, groupId;

    public KafkaEventSource(String bootstrap, String groupId) {
        this.bootstrap = bootstrap; this.groupId = groupId;
    }

    private static int priority(String topic) {
        return switch (topic) {
            case "shop.catalog" -> 0;
            case "shop.work_orders" -> 1;
            case "shop.inspections" -> 2;
            default -> 3; // shop.purchasing
        };
    }

    @Override
    public void run(Consumer<String> handler) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("group.id", groupId);
        p.put("key.deserializer", StringDeserializer.class.getName());
        p.put("value.deserializer", StringDeserializer.class.getName());
        p.put("auto.offset.reset", "earliest");
        p.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            // Fase 1: solo el catálogo, hasta alcanzar su final.
            c.subscribe(List.of("shop.catalog"));
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) handler.accept(r.value());
                c.commitSync();
                Set<TopicPartition> assigned = c.assignment();
                if (assigned.isEmpty()) continue;
                Map<TopicPartition, Long> end = c.endOffsets(assigned);
                boolean caughtUp = true;
                for (TopicPartition tp : assigned) if (c.position(tp) < end.get(tp)) caughtUp = false;
                if (caughtUp) break;
            }
            System.out.println("Catálogo cargado. Consumiendo todos los tópicos.");

            // Fase 2: todos los tópicos, por lotes ordenados (catálogo, órdenes, inspecciones, compras).
            c.subscribe(TOPICS);
            List<ConsumerRecord<String, String>> batch = new ArrayList<>();
            while (true) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) batch.add(r);
                if (batch.isEmpty()) continue;
                if (batch.stream().anyMatch(r -> r.topic().equals("shop.purchasing"))) {
                    try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(100))) batch.add(r);
                }
                // sort es estable: dentro de un mismo tópico y partición se conserva el orden original
                batch.sort(Comparator.comparingInt(r -> priority(r.topic())));
                for (ConsumerRecord<String, String> r : batch) handler.accept(r.value());
                c.commitSync();
                batch.clear();
            }
        }
    }
}
