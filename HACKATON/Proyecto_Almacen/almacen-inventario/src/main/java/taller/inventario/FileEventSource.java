package taller.inventario;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lee data/events.jsonl. Cada línea es {"topic":..., "replay_at":..., "event": {...}}.
 * Regla 22 del contrato: primero TODO shop.catalog, después el resto en el orden del archivo.
 */
public final class FileEventSource implements EventSource {
    private final Path path;
    public FileEventSource(String path) { this.path = Path.of(path); }

    @Override
    public void run(Consumer<String> handler) throws Exception {
        List<String> catalog = new ArrayList<>(), rest = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n = Json.parse(line);
                JsonNode ev = n.has("event") ? n.get("event") : n; // acepta también eventos sin envoltura
                boolean isCatalog = "shop.catalog".equals(n.path("topic").asText(""));
                (isCatalog ? catalog : rest).add(ev.toString());
            }
        }
        catalog.forEach(handler);
        rest.forEach(handler);
    }
}
