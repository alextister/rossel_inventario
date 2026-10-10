package taller.inventario;

import java.util.Properties;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

/** Publica en Kafka/Redpanda. */
public final class KafkaPublisher implements Publisher {
    private final KafkaProducer<String, String> producer;

    public KafkaPublisher(String bootstrap) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        p.put("acks", "all");
        producer = new KafkaProducer<>(p);
    }

    @Override
    public void publish(String topic, String key, String json) throws Exception {
        producer.send(new ProducerRecord<>(topic, key, json)).get(); // espera confirmación
    }
}
