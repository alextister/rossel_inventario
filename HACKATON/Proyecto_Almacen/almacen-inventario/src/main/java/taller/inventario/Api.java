package taller.inventario;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

/** API HTTP según contract/openapi.yaml: listas {"items": [...]}, errores {"detail": "..."}. */
public final class Api {
    private Api() {}

    private static long id(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { throw new ApiError(404, "no encontrado: " + s); }
    }

    public static Javalin start(int port, Db db, Processor proc, Floor floor) {
        Javalin app = Javalin.create(cfg -> cfg.staticFiles.add("/public", Location.CLASSPATH)).start(port);

        app.exception(ApiError.class, (e, ctx) -> ctx.status(e.status).json(Json.map("detail", e.getMessage())));
        app.exception(IllegalArgumentException.class, (e, ctx) -> ctx.status(422).json(Json.map("detail", e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            e.printStackTrace();
            ctx.status(500).json(Json.map("detail", String.valueOf(e.getMessage())));
        });

        app.get("/parts/{id}/availability", ctx -> {
            long pid = id(ctx.pathParam("id"));
            ctx.json(db.tx(d -> {
                if (d.one("SELECT 1 FROM parts WHERE part_id=?", pid) == null) throw new ApiError(404, "pieza no encontrada: " + pid);
                List<Map<String, Object>> locs = d.query(
                        "SELECT b.location_id, l.code AS code, b.on_hand, b.reserved, b.on_hand - b.reserved AS available"
                        + " FROM balances b LEFT JOIN locations l ON l.location_id=b.location_id"
                        + " WHERE b.part_id=? ORDER BY b.location_id", pid);
                long onHand = 0, reserved = 0;
                for (Map<String, Object> l : locs) { onHand += Db.l(l.get("on_hand")); reserved += Db.l(l.get("reserved")); }
                return Json.map("part_id", pid, "on_hand", onHand, "reserved", reserved, "available", onHand - reserved, "locations", locs);
            }));
        });

        app.get("/parts/{id}/ledger", ctx -> {
            long pid = id(ctx.pathParam("id"));
            ctx.json(db.tx(d -> {
                if (d.one("SELECT 1 FROM parts WHERE part_id=?", pid) == null) throw new ApiError(404, "pieza no encontrada: " + pid);
                List<Map<String, Object>> rows = d.query(
                        "SELECT id AS movement_id, type, part_id, location_id, delta AS quantity, created_at AS occurred_at, ref AS reference"
                        + " FROM movements WHERE part_id=? ORDER BY created_at, id", pid);
                long run = 0;
                for (Map<String, Object> m : rows) { run += Db.l(m.get("quantity")); m.put("balance", run); } // saldo corrido
                Collections.reverse(rows); // del más reciente al más antiguo
                return Json.map("part_id", pid, "entries", rows);
            }));
        });

        app.get("/work-orders/{code}/materials", ctx -> {
            String code = ctx.pathParam("code");
            ctx.json(db.tx(d -> {
                Map<String, Object> wo = d.one("SELECT work_order_id FROM work_orders WHERE code=? ORDER BY deleted, work_order_id LIMIT 1", code);
                if (wo == null) throw new ApiError(404, "orden no encontrada: " + code);
                List<Map<String, Object>> needs = d.query(
                        "SELECT n.bom_line_id, n.inspection_item_id, n.part_id, n.name, n.required AS required,"
                        + " (SELECT sku FROM parts p WHERE p.part_id=n.part_id) AS sku,"
                        + " COALESCE((SELECT SUM(quantity) FROM reservations r WHERE r.work_order_id=n.work_order_id AND r.bom_line_id=n.bom_line_id AND r.status='active'),0) AS reserved,"
                        + " COALESCE((SELECT SUM(quantity) FROM reservations r WHERE r.work_order_id=n.work_order_id AND r.bom_line_id=n.bom_line_id AND r.status='fulfilled'),0) AS issued,"
                        + " (SELECT COUNT(*) FROM shortages s WHERE s.work_order_id=n.work_order_id AND s.bom_line_id=n.bom_line_id AND s.status='open' AND s.missing_quantity IS NULL) AS unknown,"
                        + " COALESCE((SELECT SUM(missing_quantity) FROM shortages s WHERE s.work_order_id=n.work_order_id AND s.bom_line_id=n.bom_line_id AND s.status='open'),0) AS missing"
                        + " FROM needs n WHERE n.work_order_id=? ORDER BY n.bom_line_id", wo.get("work_order_id"));
                for (Map<String, Object> n : needs) {
                    if (Db.l(n.remove("unknown")) > 0) n.put("missing", null); // cantidad faltante desconocida
                }
                return Json.map("work_order_id", Db.l(wo.get("work_order_id")), "code", code, "lines", needs);
            }));
        });

        app.get("/shortages", ctx -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT s.work_order_id, w.code AS work_order_code, s.part_id, s.name, s.missing_quantity,"
                    + " (SELECT sku FROM parts p WHERE p.part_id=s.part_id) AS sku,"
                    + " s.inspection_item_id, s.created_at AS opened_at FROM shortages s"
                    + " LEFT JOIN work_orders w ON w.work_order_id=s.work_order_id WHERE s.status='open'");
            List<Object> args = new ArrayList<>();
            if (ctx.queryParam("work_order_code") != null) { sql.append(" AND w.code=?"); args.add(ctx.queryParam("work_order_code")); }
            if (ctx.queryParam("part_id") != null) { sql.append(" AND s.part_id=?"); args.add(id(ctx.queryParam("part_id"))); }
            sql.append(" ORDER BY s.id");
            ctx.json(Json.map("items", db.tx(d -> d.query(sql.toString(), args.toArray()))));
        });

        app.get("/reorder-suggestions", ctx -> ctx.json(Json.map("items", db.tx(d -> {
            Map<Long, Map<String, Object>> sug = new TreeMap<>();
            for (Map<String, Object> r : d.query("SELECT part_id, SUM(missing_quantity) AS q, GROUP_CONCAT(DISTINCT work_order_id) AS ids"
                    + " FROM shortages WHERE status='open' AND part_id IS NOT NULL AND missing_quantity IS NOT NULL GROUP BY part_id")) {
                List<Long> ids = new ArrayList<>();
                for (String s : ((String) r.get("ids")).split(",")) ids.add(Long.parseLong(s.trim()));
                Collections.sort(ids);
                sug.put(Db.l(r.get("part_id")), Json.map("part_id", Db.l(r.get("part_id")), "suggested_quantity", Db.l(r.get("q")), "work_order_ids", ids));
            }
            // piezas por debajo de su mínimo (si hay política configurada)
            for (Map<String, Object> p : d.query("SELECT p.part_id, p.min_qty, p.max_qty, p.reorder_qty,"
                    + " COALESCE((SELECT SUM(on_hand - reserved) FROM balances b WHERE b.part_id=p.part_id),0) AS av FROM reorder_policies p")) {
                long av = Db.l(p.get("av")), min = Db.l(p.get("min_qty"));
                if (min <= 0 || av >= min) continue;
                long extra = Db.l(p.get("reorder_qty")) > 0 ? Db.l(p.get("reorder_qty")) : Math.max(Db.l(p.get("max_qty")) - av, min - av);
                if (extra <= 0) continue;
                Map<String, Object> s = sug.computeIfAbsent(Db.l(p.get("part_id")),
                        k -> Json.map("part_id", k, "suggested_quantity", 0L, "work_order_ids", new ArrayList<Long>()));
                s.put("suggested_quantity", Db.l(s.get("suggested_quantity")) + extra);
            }
            for (Map<String, Object> s : sug.values()) { // sku y nombre de la pieza
                Map<String, Object> part = d.one("SELECT sku, name FROM parts WHERE part_id=?", s.get("part_id"));
                if (part != null) { s.put("sku", part.get("sku")); s.put("name", part.get("name")); }
            }
            return new ArrayList<>(sug.values());
        }))));

        // Extra (no está en el contrato): definir mínimo/máximo/reorden de una pieza.
        app.put("/parts/{id}/policy", ctx -> {
            long pid = id(ctx.pathParam("id"));
            var b = Json.parse(ctx.body());
            db.tx(d -> d.update("INSERT INTO reorder_policies(part_id,min_qty,max_qty,reorder_qty,lead_time_days) VALUES(?,?,?,?,?)"
                    + " ON CONFLICT(part_id) DO UPDATE SET min_qty=excluded.min_qty, max_qty=excluded.max_qty,"
                    + " reorder_qty=excluded.reorder_qty, lead_time_days=excluded.lead_time_days",
                    pid, b.path("min_qty").asLong(0), b.path("max_qty").asLong(0), b.path("reorder_qty").asLong(0), b.path("lead_time_days").asLong(0)));
            ctx.json(Json.map("status", "ok"));
        });

        app.get("/unmatched-receipts", ctx -> ctx.json(Json.map("items", db.tx(d -> d.query(
                "SELECT id, purchase_line_id, part_number, description, quantity, unit_price, currency, received_at, work_order_code"
                + " FROM unmatched_receipts WHERE status='pending' ORDER BY id")))));

        app.post("/unmatched-receipts/{id}/resolve", ctx -> {
            var b = Json.parse(ctx.body());
            if (!b.hasNonNull("part_id")) throw new ApiError(422, "falta part_id");
            ctx.json(proc.resolveReceipt(ctx.pathParam("id"), b.get("part_id").asLong(), Json.lng(b.get("location_id"))));
        });

        app.post("/issues", ctx -> ctx.status(201).json(floor.issue(Json.parse(ctx.body()))));
        app.post("/transfers", ctx -> ctx.status(201).json(floor.transfer(Json.parse(ctx.body()))));
        app.post("/counts", ctx -> ctx.status(201).json(floor.count(Json.parse(ctx.body()))));

        // ---- Extras para las pantallas (no están en el contrato) ----
        app.get("/stock", ctx -> {
            String q = ctx.queryParam("q") == null ? "" : ctx.queryParam("q").trim();
            StringBuilder sql = new StringBuilder(
                    "SELECT b.part_id, p.sku, p.name, b.location_id, l.code AS location_code, b.on_hand, b.reserved,"
                    + " b.on_hand - b.reserved AS available FROM balances b JOIN parts p ON p.part_id=b.part_id"
                    + " LEFT JOIN locations l ON l.location_id=b.location_id WHERE (b.on_hand<>0 OR b.reserved<>0)");
            List<Object> args = new ArrayList<>();
            if (!q.isEmpty()) {
                sql.append(" AND (UPPER(p.name) LIKE ? OR p.sku_norm LIKE ? OR CAST(p.part_id AS TEXT)=?)");
                args.add("%" + q.toUpperCase() + "%"); args.add("%" + Processor.norm(q) + "%"); args.add(q);
            }
            sql.append(" ORDER BY p.name, b.location_id LIMIT 500");
            ctx.json(Json.map("items", db.tx(d -> d.query(sql.toString(), args.toArray()))));
        });

        app.get("/parts", ctx -> {
            String q = ctx.queryParam("q") == null ? "" : ctx.queryParam("q").trim();
            String norm = Processor.norm(q);
            ctx.json(Json.map("items", db.tx(d -> q.isEmpty() ? new ArrayList<Map<String, Object>>()
                    : d.query("SELECT part_id, sku, name FROM parts WHERE UPPER(name) LIKE ? OR sku_norm LIKE ? OR CAST(part_id AS TEXT)=?"
                    + " ORDER BY name LIMIT 8", "%" + q.toUpperCase() + "%", "%" + norm + "%", q))));
        });

        app.get("/scan/location", ctx -> {
            String code = ctx.queryParam("code") == null ? "" : ctx.queryParam("code").trim();
            ctx.json(db.tx(d -> {
                Map<String, Object> r = d.one("SELECT location_id, code, name FROM locations WHERE UPPER(code)=UPPER(?)", code);
                if (r == null && code.equalsIgnoreCase("U-100")) r = Json.map("location_id", 100L, "code", "U-100", "name", "Recepción de material");
                if (r == null) throw new ApiError(404, "ubicación no encontrada: " + code);
                return r;
            }));
        });

        // Etiqueta de pieza: su SKU tal cual, o "P-" + part_id si no tiene SKU.
        app.get("/scan/part", ctx -> {
            String code = ctx.queryParam("code") == null ? "" : ctx.queryParam("code").trim();
            ctx.json(db.tx(d -> {
                Matcher m = Pattern.compile("^P-(\\d+)$", Pattern.CASE_INSENSITIVE).matcher(code);
                List<Map<String, Object>> rows = m.matches()
                        ? d.query("SELECT part_id, sku, name FROM parts WHERE part_id=?", Long.parseLong(m.group(1)))
                        : d.query("SELECT part_id, sku, name FROM parts WHERE sku_norm=?", Processor.norm(code));
                if (rows.isEmpty()) throw new ApiError(404, "pieza no encontrada: " + code);
                return Json.map("items", rows);
            }));
        });

        app.get("/health", ctx -> ctx.result("ok"));
        return app;
    }
}
