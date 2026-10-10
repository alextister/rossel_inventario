package taller.inventario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operaciones de piso: salida contra orden, transferencia y conteo cíclico. */
public final class Floor {
    private final Db db;
    private final Outbox out;
    private final Stock stock;

    Floor(Db db, Outbox out, Stock stock) { this.db = db; this.out = out; this.stock = stock; }

    private static long req(JsonNode b, String field) {
        JsonNode n = b.get(field);
        if (n == null || !n.canConvertToLong() || n.isTextual()) throw new ApiError(422, "falta o es inválido: " + field);
        return n.asLong();
    }

    /** Retira piezas contra una orden: consume primero su reserva (pasa a fulfilled) y baja el saldo. */
    public Map<String, Object> issue(JsonNode b) {
        return db.tx(d -> {
            Ctx c = Ctx.api();
            String code = b.path("work_order_code").asText("");
            long partId = req(b, "part_id"), loc = req(b, "location_id"), qty = req(b, "quantity");
            if (qty < 1) throw new ApiError(422, "quantity debe ser mayor que 0");
            if (!b.hasNonNull("issued_by") || b.get("issued_by").asText().isBlank()) throw new ApiError(422, "falta issued_by");
            Map<String, Object> wo = db.one("SELECT work_order_id FROM work_orders WHERE code=? AND deleted=0", code);
            if (wo == null) throw new ApiError(404, "orden no encontrada: " + code);
            if (!stock.partExists(partId)) throw new ApiError(404, "pieza no encontrada: " + partId);
            long woId = Db.l(wo.get("work_order_id"));

            long left = qty, fromReserved = 0;
            for (Map<String, Object> r : db.query("SELECT id, quantity FROM reservations"
                    + " WHERE work_order_id=? AND part_id=? AND location_id=? AND status='active' ORDER BY id", woId, partId, loc)) {
                if (left == 0) break;
                long rq = Db.l(r.get("quantity")), t = Math.min(rq, left);
                if (t == rq) {
                    db.update("UPDATE reservations SET status='fulfilled' WHERE id=?", r.get("id"));
                } else { // salida parcial: se divide la reserva
                    db.update("UPDATE reservations SET quantity = quantity - ? WHERE id=?", t, r.get("id"));
                    db.update("INSERT INTO reservations(work_order_id,bom_line_id,part_id,location_id,quantity,status,inspection_id,inspection_item_id)"
                            + " SELECT work_order_id,bom_line_id,part_id,location_id,?,'fulfilled',inspection_id,inspection_item_id"
                            + " FROM reservations WHERE id=?", t, r.get("id"));
                }
                left -= t; fromReserved += t;
            }
            // Lo que exceda la reserva de la orden debe estar libre en esa ubicación; si no, 409 y no se mueve nada.
            long free = db.num("SELECT COALESCE(SUM(on_hand - reserved),0) FROM balances WHERE part_id=? AND location_id=?", partId, loc);
            if (left > 0 && free + fromReserved < qty) throw new ApiError(409, "stock insuficiente en esa ubicación");
            if (left == 0 && db.num("SELECT COALESCE(SUM(on_hand),0) FROM balances WHERE part_id=? AND location_id=?", partId, loc) < qty)
                throw new ApiError(409, "stock insuficiente en esa ubicación");

            db.update("UPDATE balances SET reserved = reserved - ? WHERE part_id=? AND location_id=?", fromReserved, partId, loc);
            long mid = stock.move(c, partId, loc, -qty, "issue", "work_order", code, null, null, null);

            ObjectNode o = Json.obj();
            o.put("work_order_id", woId);
            o.put("part_id", partId);
            o.put("location_id", loc);
            o.put("quantity", qty);
            o.set("issued_by", b.get("issued_by"));
            out.emit(c, "stock.issued", code, o);
            return stock.movement(mid);
        });
    }

    public Map<String, Object> transfer(JsonNode b) {
        return db.tx(d -> {
            Ctx c = Ctx.api();
            long part = req(b, "part_id"), from = req(b, "from_location_id"), to = req(b, "to_location_id"), qty = req(b, "quantity");
            if (qty < 1 || from == to) throw new ApiError(422, "cantidad o ubicaciones inválidas");
            if (!stock.partExists(part)) throw new ApiError(404, "pieza no encontrada: " + part);
            if (!stock.locationExists(to)) throw new ApiError(404, "ubicación destino no encontrada: " + to);
            long free = db.num("SELECT COALESCE(SUM(on_hand - reserved),0) FROM balances WHERE part_id=? AND location_id=?", part, from);
            if (free < qty) throw new ApiError(409, "stock disponible insuficiente en el origen");
            long outId = stock.move(c, part, from, -qty, "transfer_out", "location", "to:" + to, null, null, null);
            stock.move(c, part, to, qty, "transfer_in", "location", "from:" + from, null, null, null);
            ObjectNode o = Json.obj();
            o.put("part_id", part);
            o.put("from_location_id", from);
            o.put("to_location_id", to);
            o.put("quantity", qty);
            out.emit(c, "stock.transferred", "part:" + part, o);
            return stock.movement(outId);
        });
    }

    /** Conteo cíclico: compara lo contado contra el saldo y registra un ajuste por cada diferencia. */
    public Map<String, Object> count(JsonNode b) {
        return db.tx(d -> {
            Ctx c = Ctx.api();
            String reason = b.path("reason").asText("").trim();
            if (reason.isEmpty()) throw new ApiError(422, "el motivo es obligatorio");
            long loc = req(b, "location_id");
            if (!b.path("lines").isArray()) throw new ApiError(422, "falta lines");
            if (!stock.locationExists(loc)) throw new ApiError(404, "ubicación no encontrada: " + loc);
            List<Object> adjustments = new ArrayList<>();
            for (JsonNode line : b.get("lines")) {
                long part = req(line, "part_id"), counted = req(line, "counted_quantity");
                if (counted < 0) throw new ApiError(422, "counted_quantity no puede ser negativa");
                if (!stock.partExists(part)) throw new ApiError(404, "pieza no encontrada: " + part);
                long onHand = db.num("SELECT COALESCE(SUM(on_hand),0) FROM balances WHERE part_id=? AND location_id=?", part, loc);
                long delta = counted - onHand;
                if (delta == 0) continue;
                stock.move(c, part, loc, delta, "adjustment", "count", reason, null, null, reason);
                ObjectNode o = Json.obj();
                o.put("part_id", part);
                o.put("location_id", loc);
                o.put("delta", delta);
                o.put("reason", reason);
                out.emit(c, "stock.adjusted", "part:" + part, o);
                Map<String, Object> adj = new LinkedHashMap<>();
                adj.put("part_id", part);
                adj.put("delta", delta);
                adjustments.add(adj);
            }
            return Json.map("adjustments", adjustments);
        });
    }
}
