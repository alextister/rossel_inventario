package taller.inventario;

/** Destino de los eventos de salida (inventory.events) y de errores (inventory.dlq). */
public interface Publisher {
    void publish(String topic, String key, String json) throws Exception;
}
