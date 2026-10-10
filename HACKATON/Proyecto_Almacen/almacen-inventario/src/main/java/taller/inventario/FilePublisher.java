package taller.inventario;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Escribe cada evento de salida en out/<topico>.jsonl (útil para desarrollar sin broker). */
public final class FilePublisher implements Publisher {
    private final Path dir;

    public FilePublisher(String dir) throws Exception {
        this.dir = Path.of(dir);
        Files.createDirectories(this.dir);
    }

    @Override
    public synchronized void publish(String topic, String key, String json) throws Exception {
        Files.writeString(dir.resolve(topic + ".jsonl"), json + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
