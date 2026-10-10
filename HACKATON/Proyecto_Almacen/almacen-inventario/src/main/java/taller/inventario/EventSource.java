package taller.inventario;

import java.util.function.Consumer;

/** De dónde llegan los eventos de entrada: de un archivo o de Kafka. La lógica no cambia. */
public interface EventSource {
    void run(Consumer<String> handler) throws Exception;
}
