package taller.inventario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Utilidades de JSON compartidas. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();

    private Json() {}

    public static JsonNode parse(String s) {
        try { return M.readTree(s); }
        catch (Exception e) { throw new IllegalArgumentException("JSON inválido: " + e.getMessage(), e); }
    }

    public static ObjectNode obj() { return M.createObjectNode(); }

    /** map("a", 1, "b", 2) -> {"a":1,"b":2} */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Entero que puede ser null (campo ausente o null en el JSON). */
    public static Long lng(JsonNode n) {
        return (n == null || n.isNull() || n.isMissingNode()) ? null : n.asLong();
    }

    /** Cantidad por unidad: puede venir decimal; se redondea hacia arriba. */
    public static Long qty(JsonNode n) {
        return (n == null || n.isNull() || n.isMissingNode()) ? null : (long) Math.ceil(n.asDouble());
    }
}
