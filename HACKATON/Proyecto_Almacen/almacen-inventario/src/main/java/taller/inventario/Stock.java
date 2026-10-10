package taller.inventario;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Núcleo del inventario: libro mayor, reservas, faltantes y liberaciones.
 * Siempre se llama DENTRO de db.tx(), nunca por fuera.
 *
 * La necesidad vive por (orden, bom_line_id): una aprobación posterior que repite la línea
 * REEMPLAZA la cantidad (reserva la diferencia o libera el excedente).
 */
final class Stock {
    /** Ubicación de recepción U-100 (location_id 100, fijo por contrato). */
    static final long RECEPTION_ID = 100;

    private final Db db;
    private final Outbox out;

    Stock(Db db, Outbox out) { this.db = db; this.out = out; }

    // ---------- consultas básicas ----------

    boolean locationExists(long id) {
        return id == RECEPTION_ID || db.one("SELECT 1 FROM locations WHERE location_id=?", id) != null;
    }

    boolean partExists(long id) { return db.one("SELECT 1 FROM parts WHERE part_id=?", id) != null; }

    boolean isService(long partId) {
        Map<String, Object> r = db.one("SELECT family FROM parts WHERE part_id=?", partId);
        return r != null && "SERVICIO".equalsIgnoreCase((String) r.get("family"));
    }

    /** Código de la orden (es la "key" de los eventos de salida). */
    String woCode(Ctx c, long woId) {
        Map<String, Object> r = db.one("SELECT code FROM work_orders WHERE work_order_id=?", woId);
        return (r != null && r.get("code") != null) ? (String) r.get("code") : c.key;
    }

    /** Disponible total de una pieza (suma de on_hand - reserved por ubicación, sin negativos). */
    long available(long partId) {
        return db.num("SELECT COALESCE(SUM(MAX(on_hand - reserved, 0)),0) FROM balances WHERE part_id=?", partId);
    }

    Map<String, Object> movement(long id) {
        return db.one("SELECT id AS movement_id, type, part_id, location_id, delta AS quantity,"
                + " created_at AS occurred_at, ref AS reference FROM movements WHERE id=?", id);
    }

    // ---------- libro mayor ----------

    /** Registra un movimiento (inmutable) y actualiza el saldo (proyección). Devuelve su id. */
    long move(Ctx c, long partId, long locId, long delta, String type, String refType, String ref,
              String cost, String currency, String reason) {
        long id = db.insert("INSERT INTO movements(part_id,location_id,delta,type,ref_type,ref,unit_cost,currency,reason,event_id,created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                partId, locId, delta, type, refType, ref, cost, currency, reason, c.eventId, c.occurredAt);
        db.update("INSERT INTO balances(part_id,location_id,on_hand,reserved) VALUES(?,?,?,0)"
                + " ON CONFLICT(part_id,location_id) DO UPDATE SET on_hand = on_hand + excluded.on_hand",
                partId, locId, delta);
        return id;
    }

    // ---------- reservas ----------

    /** Aparta hasta qty piezas para la línea de la orden. Devuelve cuántas logró apartar. */
    long reserve(Ctx c, long woId, long partId, long qty, Long inspId, long itemId, Long lineId) {
        long left = qty;
        List<Map<String, Object>> rows = db.query(
                "SELECT location_id, on_hand - reserved AS av FROM balances"
                + " WHERE part_id=? AND on_hand - reserved > 0 ORDER BY av DESC, location_id", partId);
        for (Map<String, Object> row : rows) {
            if (left == 0) break;
            long loc = Db.l(row.get("location_id"));
            long take = Math.min(left, Db.l(row.get("av")));
            // El WHERE garantiza que available nunca baje de cero.
            int ok = db.update("UPDATE balances SET reserved = reserved + ?"
                    + " WHERE part_id=? AND location_id=? AND on_hand - reserved >= ?", take, partId, loc, take);
            if (ok == 0) continue;
            long rid = db.insert("INSERT INTO reservations(work_order_id,bom_line_id,part_id,location_id,quantity,status,inspection_id,inspection_item_id)"
                    + " VALUES(?,?,?,?,?,'active',?,?)", woId, lineId, partId, loc, take, inspId, itemId);
            ObjectNode d = Json.obj();
            d.put("reservation_id", rid);
            d.put("work_order_id", woId);
            d.put("part_id", partId);
            d.put("quantity", take);
            d.put("inspection_item_id", itemId);
            out.emit(c, "stock.reserved", woCode(c, woId), d);
            left -= take;
        }
        return qty - left;
    }

    /** Quita hasta "excess" piezas de las reservas ACTIVAS de la línea (las ya entregadas no se tocan). */
    private void releaseExcess(long wo, long line, long excess) {
        for (Map<String, Object> r : db.query("SELECT id, part_id, location_id, quantity FROM reservations"
                + " WHERE work_order_id=? AND bom_line_id=? AND status='active' ORDER BY id DESC", wo, line)) {
            if (excess <= 0) break;
            long q = Db.l(r.get("quantity")), t = Math.min(q, excess);
            db.update("UPDATE balances SET reserved = reserved - ? WHERE part_id=? AND location_id=?",
                    t, r.get("part_id"), r.get("location_id"));
            if (t == q) db.update("UPDATE reservations SET status='cancelled' WHERE id=?", r.get("id"));
            else db.update("UPDATE reservations SET quantity = quantity - ? WHERE id=?", t, r.get("id"));
            excess -= t;
        }
    }

    // ---------- necesidad ----------

    /** Fija/actualiza la necesidad de una línea y la concilia contra el stock. */
    void setNeed(Ctx c, long wo, long line, Long inspId, long itemId, Long partId, String name, Long required) {
        db.update("INSERT INTO needs(work_order_id,bom_line_id,inspection_id,inspection_item_id,part_id,name,required) VALUES(?,?,?,?,?,?,?)"
                + " ON CONFLICT(work_order_id,bom_line_id) DO UPDATE SET inspection_id=excluded.inspection_id,"
                + " inspection_item_id=excluded.inspection_item_id, part_id=excluded.part_id, name=excluded.name, required=excluded.required",
                wo, line, inspId, itemId, partId, name, required);
        reconcile(c, wo, line);
    }

    private void reconcile(Ctx c, long wo, long line) {
        Map<String, Object> need = db.one("SELECT * FROM needs WHERE work_order_id=? AND bom_line_id=?", wo, line);
        if (need == null) return;
        Long part = Db.ln(need.get("part_id")), req = Db.ln(need.get("required")), insp = Db.ln(need.get("inspection_id"));
        long item = Db.l(need.get("inspection_item_id"));
        String name = (String) need.get("name");
        Map<String, Object> sh = db.one("SELECT id, missing_quantity FROM shortages WHERE work_order_id=? AND bom_line_id=? AND status='open'", wo, line);

        // Pieza sin identificar o sin cantidad: solo faltante, nunca reserva.
        if (part == null || req == null) {
            setShortage(c, wo, line, part, name, req, insp, item, sh);
            if (part != null) suggestReorder(c, part);
            return;
        }
        long held = db.num("SELECT COALESCE(SUM(quantity),0) FROM reservations"
                + " WHERE work_order_id=? AND bom_line_id=? AND status IN ('active','fulfilled')", wo, line);
        long unmet = req - held;
        if (unmet < 0) { // la necesidad bajó: liberar el excedente
            releaseExcess(wo, line, -unmet);
            unmet = 0;
            settle(c, part, -1);
        }
        long current = (sh == null || sh.get("missing_quantity") == null) ? 0 : Db.l(sh.get("missing_quantity"));
        boolean wasUnknown = sh != null && sh.get("missing_quantity") == null;
        long finalMissing = unmet;
        if (unmet > current) { // la necesidad subió o es nueva: reservar lo que se pueda
            long extra = unmet - current;
            long got = reserve(c, wo, part, extra, insp, item, line);
            finalMissing = current + (extra - got);
        }
        if (finalMissing != current || wasUnknown) setShortage(c, wo, line, part, name, finalMissing, insp, item, sh);
        suggestReorder(c, part);
    }

    /** Crea, actualiza o cierra el faltante abierto de una línea. missing null = cantidad desconocida. */
    private void setShortage(Ctx c, long wo, long line, Long part, String name, Long missing, Long insp, long item,
                             Map<String, Object> existing) {
        if (missing != null && missing <= 0) {
            if (existing != null) db.update("UPDATE shortages SET status='closed', missing_quantity=0 WHERE id=?", existing.get("id"));
            return;
        }
        if (existing == null) {
            db.update("INSERT INTO shortages(work_order_id,bom_line_id,part_id,name,missing_quantity,inspection_id,inspection_item_id,status,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,'open',?)", wo, line, part, name, missing, insp, item, c.occurredAt);
        } else {
            if (Objects.equals(Db.ln(existing.get("missing_quantity")), missing)) return;
            db.update("UPDATE shortages SET missing_quantity=?, inspection_id=?, inspection_item_id=? WHERE id=?",
                    missing, insp, item, existing.get("id"));
        }
        ObjectNode d = Json.obj();
        d.put("work_order_id", wo);
        d.put("part_id", part);
        d.put("name", name);
        d.put("missing_quantity", missing);
        d.put("inspection_item_id", item);
        out.emit(c, "stock.shortage_detected", woCode(c, wo), d);
    }

    // ---------- sugerencias de compra ----------

    /** Emite stock.reorder_suggested solo cuando la sugerencia de la pieza nace o cambia. */
    void suggestReorder(Ctx c, long partId) {
        List<Map<String, Object>> rows = db.query(
                "SELECT work_order_id, SUM(missing_quantity) AS q FROM shortages"
                + " WHERE part_id=? AND status='open' AND missing_quantity IS NOT NULL"
                + " GROUP BY work_order_id ORDER BY work_order_id", partId);
        long total = 0;
        ArrayNode ids = Json.M.createArrayNode();
        for (Map<String, Object> r : rows) { total += Db.l(r.get("q")); ids.add(Db.l(r.get("work_order_id"))); }
        if (total <= 0) { db.update("DELETE FROM reorder_state WHERE part_id=?", partId); return; }
        String sig = total + ":" + ids;
        Map<String, Object> old = db.one("SELECT signature FROM reorder_state WHERE part_id=?", partId);
        if (old != null && sig.equals(old.get("signature"))) return;
        db.update("INSERT INTO reorder_state(part_id,signature) VALUES(?,?)"
                + " ON CONFLICT(part_id) DO UPDATE SET signature=excluded.signature", partId, sig);
        ObjectNode d = Json.obj();
        d.put("part_id", partId);
        d.put("suggested_quantity", total);
        d.set("work_order_ids", ids);
        out.emit(c, "stock.reorder_suggested", "part:" + partId, d);
    }

    // ---------- surtido y liberación ----------

    /** Surte faltantes abiertos de la pieza (primero la orden preferida viva, luego del más antiguo). */
    void settle(Ctx c, long partId, long preferWoId) {
        List<Map<String, Object>> open = db.query(
                "SELECT id, work_order_id, bom_line_id, missing_quantity, inspection_id, inspection_item_id FROM shortages"
                + " WHERE part_id=? AND status='open' AND missing_quantity IS NOT NULL"
                + " ORDER BY (work_order_id = ?) DESC, id", partId, preferWoId);
        for (Map<String, Object> s : open) {
            if (available(partId) <= 0) break;
            long woId = Db.l(s.get("work_order_id")), missing = Db.l(s.get("missing_quantity"));
            long got = reserve(c, woId, partId, missing, Db.ln(s.get("inspection_id")),
                    Db.l(s.get("inspection_item_id")), Db.ln(s.get("bom_line_id")));
            if (got <= 0) continue;
            long rest = missing - got;
            db.update("UPDATE shortages SET missing_quantity=?, status=? WHERE id=?",
                    rest, rest == 0 ? "closed" : "open", s.get("id"));
            ObjectNode d = Json.obj();
            d.put("work_order_id", woId);
            d.put("part_id", partId);
            d.put("quantity", got);
            out.emit(c, "stock.shortage_resolved", woCode(c, woId), d);
        }
        suggestReorder(c, partId);
    }

    /**
     * Elimina las necesidades que cumplan "where" sobre la tabla needs
     * ("inspection_id=?" para una anulación, "work_order_id=?" para una baja):
     * libera sus reservas activas y cierra sus faltantes. Lo liberado puede surtir a otras órdenes.
     */
    void releaseNeeds(Ctx c, String where, Object arg) {
        Set<Long> parts = new LinkedHashSet<>();
        for (Map<String, Object> n : db.query("SELECT work_order_id, bom_line_id, part_id FROM needs WHERE " + where, arg)) {
            Object wo = n.get("work_order_id"), line = n.get("bom_line_id");
            for (Map<String, Object> r : db.query("SELECT id, part_id, location_id, quantity FROM reservations"
                    + " WHERE work_order_id=? AND bom_line_id=? AND status='active'", wo, line)) {
                db.update("UPDATE balances SET reserved = reserved - ? WHERE part_id=? AND location_id=?",
                        r.get("quantity"), r.get("part_id"), r.get("location_id"));
                db.update("UPDATE reservations SET status='cancelled' WHERE id=?", r.get("id"));
                parts.add(Db.l(r.get("part_id")));
            }
            db.update("UPDATE shortages SET status='cancelled' WHERE work_order_id=? AND bom_line_id=? AND status='open'", wo, line);
            if (n.get("part_id") != null) parts.add(Db.l(n.get("part_id")));
            db.update("DELETE FROM needs WHERE work_order_id=? AND bom_line_id=?", wo, line);
        }
        for (long p : parts) settle(c, p, -1);
    }
}
