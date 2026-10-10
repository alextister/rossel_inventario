package taller.inventario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recibe un evento de entrada y lo procesa. Garantías:
 *  - Idempotente: un event_id ya procesado se ignora (processed_events).
 *  - Atómico: cambios + processed_events + eventos de salida van en una sola transacción.
 *  - Tolerante al desorden: si falta una dependencia (pieza o línea de la lista de partes),
 *    el evento se aparca en pending_events y se reintenta cuando llega el catálogo.
 */
public final class Processor {
    /** Etiqueta de una pieza sin SKU: "P-" + part_id (ver contract/README.md, sección 5). */
    private static final Pattern PART_LABEL = Pattern.compile("^P-(\\d+)$", Pattern.CASE_INSENSITIVE);

    /** Falta una dependencia que aún no llega (se lanza ANTES de escribir nada). */
    static final class Missing extends RuntimeException {
        Missing(String m) { super(m, null, false, false); }
    }

    private final Db db;
    private final Outbox out;
    private final Stock stock;

    public Processor(Db db, Outbox out) {
        this.db = db; this.out = out; this.stock = new Stock(db, out);
    }

    Stock stock() { return stock; }

    /** Punto de entrada: procesa un evento crudo (JSON). */
    public void handle(String raw) {
        JsonNode ev = Json.parse(raw);
        db.tx(d -> {
            apply(ev, raw);
            switch (ev.path("event_type").asText()) {
                case "part.upserted", "location.upserted", "bom.upserted", "work_order.opened" -> retryPending();
                default -> { }
            }
            return null;
        });
    }

    private boolean apply(JsonNode ev, String raw) {
        String id = ev.path("event_id").asText();
        String type = ev.path("event_type").asText();
        if (db.one("SELECT 1 FROM processed_events WHERE event_id=?", id) != null) return true; // duplicado
        Ctx c = new Ctx(id, ev.path("occurred_at").asText(), ev.path("key").asText());
        try {
            dispatch(c, type, ev.path("data"));
        } catch (Missing m) {
            db.update("INSERT OR IGNORE INTO pending_events(event_id,payload,reason) VALUES(?,?,?)", id, raw, m.getMessage());
            return false;
        }
        db.update("INSERT INTO processed_events(event_id,event_type) VALUES(?,?)", id, type);
        return true;
    }

    /** Reintenta los eventos aparcados. Un fallo en uno no afecta al evento que disparó el reintento. */
    private void retryPending() {
        for (Map<String, Object> row : db.query("SELECT event_id, payload FROM pending_events ORDER BY rowid")) {
            String raw = (String) row.get("payload");
            db.update("SAVEPOINT retry");
            try {
                if (apply(Json.parse(raw), raw)) db.update("DELETE FROM pending_events WHERE event_id=?", row.get("event_id"));
                db.update("RELEASE retry");
            } catch (RuntimeException e) {
                db.update("ROLLBACK TO retry");
                db.update("RELEASE retry");
                System.err.println("Evento aparcado sigue fallando (" + row.get("event_id") + "): " + e);
            }
        }
    }

    private void dispatch(Ctx c, String type, JsonNode d) {
        switch (type) {
            case "part.upserted" -> part(d);
            case "location.upserted" -> location(d);
            case "bom.upserted" -> bom(d);
            case "work_order.opened" -> workOrderOpened(d);
            case "work_order.stage_changed" -> db.update("UPDATE work_orders SET stage=? WHERE work_order_id=?",
                    d.path("to_stage").asText(), d.path("work_order_id").asLong());
            case "work_order.deleted" -> {
                long wo = d.path("work_order_id").asLong();
                db.update("INSERT INTO work_orders(work_order_id,code,deleted) VALUES(?,?,1)"
                        + " ON CONFLICT(work_order_id) DO UPDATE SET deleted=1", wo, d.path("code").asText(null));
                stock.releaseNeeds(c, "work_order_id=?", wo);
            }
            case "inspection.submitted" -> storeInspection(c, d, "submitted");
            case "inspection.approved" -> approved(c, d);
            case "inspection.rejected" -> setInspectionStatus(d, "rejected");
            case "inspection.discarded" -> setInspectionStatus(d, "discarded");
            case "inspection.voided" -> {
                long ins = d.path("inspection_id").asLong();
                setInspectionStatus(d, "voided");
                stock.releaseNeeds(c, "inspection_id=?", ins);
            }
            case "purchase.item_received" -> purchase(c, d);
            default -> { /* evento desconocido: se ignora */ }
        }
    }

    // ---------- catálogo ----------

    /** "7014 CTA/P4" y " 7014cta/p4 " deben dar la misma llave (sin espacios, en mayúsculas). */
    static String norm(String s) {
        if (s == null) return null;
        String n = s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return n.isEmpty() ? null : n;
    }

    private void part(JsonNode d) {
        String sku = d.path("sku").asText(null);
        db.update("INSERT INTO parts(part_id,sku,sku_norm,name,family,grp,subgroup,unit,active) VALUES(?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT(part_id) DO UPDATE SET sku=excluded.sku, sku_norm=excluded.sku_norm, name=excluded.name,"
                + " family=excluded.family, grp=excluded.grp, subgroup=excluded.subgroup, unit=excluded.unit, active=excluded.active",
                d.path("part_id").asLong(), sku, norm(sku), d.path("name").asText(null), d.path("family").asText(null),
                d.path("group").asText(null), d.path("subgroup").asText(null), d.path("unit").path("name").asText(null),
                d.path("active").asBoolean(true) ? 1 : 0);
    }

    private void location(JsonNode d) {
        db.update("INSERT INTO locations(location_id,code,name,is_workbench,active) VALUES(?,?,?,?,?)"
                + " ON CONFLICT(location_id) DO UPDATE SET code=excluded.code, name=excluded.name,"
                + " is_workbench=excluded.is_workbench, active=excluded.active",
                d.path("location_id").asLong(), d.path("code").asText(null), d.path("name").asText(null),
                d.path("is_workbench").asBoolean(false) ? 1 : 0, d.path("active").asBoolean(true) ? 1 : 0);
    }

    private void bom(JsonNode d) {
        long model = d.path("model_id").asLong();
        for (JsonNode l : d.path("lines")) {
            db.update("INSERT INTO bom_lines(bom_line_id,model_id,part_id,name,group_name,qty_per_unit) VALUES(?,?,?,?,?,?)"
                    + " ON CONFLICT(bom_line_id) DO UPDATE SET model_id=excluded.model_id, part_id=excluded.part_id,"
                    + " name=excluded.name, group_name=excluded.group_name, qty_per_unit=excluded.qty_per_unit",
                    l.path("bom_line_id").asLong(), model, Json.lng(l.path("part_id")), l.path("name").asText(null),
                    l.path("group_name").asText(null), Json.qty(l.path("qty_per_unit")));
        }
    }

    private void workOrderOpened(JsonNode d) {
        db.update("INSERT INTO work_orders(work_order_id,code,model_id,department,stage) VALUES(?,?,?,?,'awaiting_quick_inspection')"
                + " ON CONFLICT(work_order_id) DO UPDATE SET code=excluded.code, model_id=excluded.model_id, department=excluded.department",
                d.path("work_order_id").asLong(), d.path("code").asText(null), d.path("model_id").asLong(),
                d.path("department").asText(null));
    }

    // ---------- inspecciones ----------

    private void storeInspection(Ctx c, JsonNode d, String status) {
        long ins = d.path("inspection_id").asLong(), wo = d.path("work_order_id").asLong();
        // la key de los eventos de inspección es el código de la orden (la orden puede no haber llegado aún)
        db.update("INSERT INTO work_orders(work_order_id,code) VALUES(?,?)"
                + " ON CONFLICT(work_order_id) DO UPDATE SET code=COALESCE(work_orders.code, excluded.code)", wo, c.key);
        db.update("INSERT INTO inspections(inspection_id,work_order_id,kind,status) VALUES(?,?,?,?)"
                + " ON CONFLICT(inspection_id) DO UPDATE SET status = CASE"
                + " WHEN inspections.status IN ('approved','voided') AND excluded.status='submitted' THEN inspections.status"
                + " ELSE excluded.status END", ins, wo, d.path("kind").asText("full"), status);
    }

    private void setInspectionStatus(JsonNode d, String status) {
        db.update("UPDATE inspections SET status=? WHERE inspection_id=?", status, d.path("inspection_id").asLong());
    }

    /**
     * Solo una aprobación crea necesidad. Cada renglón buy (no ok) fija la necesidad de su línea
     * y REEMPLAZA la anterior; las líneas que no menciona conservan la suya.
     */
    private void approved(Ctx c, JsonNode d) {
        JsonNode items = d.path("items");
        // 1) Verificar dependencias ANTES de escribir nada.
        for (JsonNode it : items) {
            if (!isNeed(it)) continue;
            Long pid = Json.lng(it.path("part_id"));
            if (pid != null && !stock.partExists(pid))
                throw new Missing("la pieza " + pid + " aún no está en el catálogo");
            Long bl = Json.lng(it.path("bom_line_id"));
            if (Json.lng(it.path("quantity")) == null && bl != null
                    && db.one("SELECT 1 FROM bom_lines WHERE bom_line_id=?", bl) == null)
                throw new Missing("la línea " + bl + " aún no está en la lista de partes");
        }
        long ins = d.path("inspection_id").asLong(), wo = d.path("work_order_id").asLong();
        storeInspection(c, d, "approved");
        Map<String, Object> order = db.one("SELECT deleted FROM work_orders WHERE work_order_id=?", wo);
        if (order != null && Db.l(order.get("deleted")) == 1) return; // la orden ya se dio de baja

        // 2) Fijar la necesidad de cada línea; setNeed reserva o deja faltante.
        for (JsonNode it : items) {
            if (!isNeed(it)) continue;
            long itemId = it.path("inspection_item_id").asLong();
            Long partId = Json.lng(it.path("part_id"));
            if (partId != null && stock.isService(partId)) continue; // los servicios no se inventarían
            Long lineId = Json.lng(it.path("bom_line_id"));
            long line = lineId != null ? lineId : -itemId;
            Long qty = Json.lng(it.path("quantity"));
            if (qty == null && lineId != null) { // cantidad sin capturar: usar la de la lista de partes
                Map<String, Object> b = db.one("SELECT qty_per_unit FROM bom_lines WHERE bom_line_id=?", lineId);
                qty = b == null ? null : Db.ln(b.get("qty_per_unit"));
            }
            stock.setNeed(c, wo, line, ins, itemId, partId, it.path("name").asText(null), qty);
        }
    }

    /** repair (pieza del cliente) y ok no generan necesidad. */
    private static boolean isNeed(JsonNode it) {
        return "buy".equals(it.path("action").asText()) && !"ok".equals(it.path("condition").asText());
    }

    // ---------- compras ----------

    private void purchase(Ctx c, JsonNode d) {
        if ("customer_order".equals(d.path("purpose").asText())) return; // material de un cliente: no es existencia
        long lineId = d.path("line_id").asLong(), qty = d.path("quantity").asLong();
        String pn = d.path("part_number").asText(null);
        String key = norm(pn);
        List<Map<String, Object>> matches = key == null ? List.of()
                : db.query("SELECT part_id, family FROM parts WHERE sku_norm=?", key);
        if (matches.isEmpty() && pn != null) { // "P-24" identifica a la pieza 24 solo si esa pieza no tiene SKU
            Matcher lm = PART_LABEL.matcher(pn.trim());
            if (lm.matches())
                matches = db.query("SELECT part_id, family FROM parts WHERE part_id=? AND (sku IS NULL OR TRIM(sku)='')",
                        Long.parseLong(lm.group(1)));
        }
        if (matches.size() != 1) { // sin coincidencia o ambiguo (mismo sku en varias piezas): revisión manual
            db.insert("INSERT INTO unmatched_receipts(purchase_line_id,part_number,description,quantity,unit_price,currency,work_order_code,received_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)", lineId, pn == null ? "" : pn, d.path("description").asText(""), qty,
                    d.path("unit_price").asText(null), d.path("currency").asText(null),
                    d.path("work_order_code").asText(null), d.path("received_at").asText(null));
            ObjectNode o = Json.obj();
            o.put("purchase_line_id", lineId);
            o.put("part_number", pn == null ? "" : pn);
            o.put("description", d.path("description").asText(""));
            o.put("quantity", qty);
            out.emit(c, "stock.unmatched_receipt", "purchase_line:" + lineId, o);
            return;
        }
        Map<String, Object> part = matches.get(0);
        if ("SERVICIO".equalsIgnoreCase((String) part.get("family"))) return; // un servicio no es existencia
        receive(c, lineId, qty, d.path("unit_price").asText(null), d.path("currency").asText(null),
                Db.l(part.get("part_id")), Stock.RECEPTION_ID, d.path("work_order_code").asText(null));
    }

    /** Entrada de stock por compra y surtido de faltantes (primero los de la orden indicada si sigue viva). */
    private Map<String, Object> receive(Ctx c, long lineId, long qty, String price, String currency,
                                        long partId, long loc, String woCode) {
        long mid = stock.move(c, partId, loc, qty, "receipt", "purchase_line", "purchase_line:" + lineId, price, currency, null);
        ObjectNode o = Json.obj();
        o.put("part_id", partId);
        o.put("location_id", loc);
        o.put("quantity", qty);
        o.put("unit_cost", price);
        o.put("currency", currency);
        o.put("purchase_line_id", lineId);
        out.emit(c, "stock.received", "part:" + partId, o);
        long prefer = -1;
        if (woCode != null) {
            Map<String, Object> w = db.one("SELECT work_order_id FROM work_orders WHERE code=? AND deleted=0", woCode);
            if (w != null) prefer = Db.l(w.get("work_order_id"));
        }
        stock.settle(c, partId, prefer);
        return stock.movement(mid);
    }

    /** Resolución manual (API): relaciona una recepción pendiente con una pieza. */
    public Map<String, Object> resolveReceipt(String idText, long partId, Long locationId) {
        return db.tx(d -> {
            long id;
            try { id = Long.parseLong(idText); } catch (NumberFormatException e) { throw new ApiError(404, "recepción no encontrada"); }
            Map<String, Object> r = db.one("SELECT * FROM unmatched_receipts WHERE id=?", id);
            if (r == null) throw new ApiError(404, "recepción no encontrada");
            if (!"pending".equals(r.get("status"))) throw new ApiError(409, "la recepción ya fue resuelta");
            if (!stock.partExists(partId)) throw new ApiError(404, "la pieza no existe");
            long loc = locationId != null ? locationId : Stock.RECEPTION_ID;
            if (!stock.locationExists(loc)) throw new ApiError(404, "la ubicación no existe");
            Map<String, Object> mv = receive(Ctx.api(), Db.l(r.get("purchase_line_id")), Db.l(r.get("quantity")),
                    (String) r.get("unit_price"), (String) r.get("currency"), partId, loc, (String) r.get("work_order_code"));
            db.update("UPDATE unmatched_receipts SET status='resolved', resolved_part_id=? WHERE id=?", partId, id);
            return mv;
        });
    }
}
