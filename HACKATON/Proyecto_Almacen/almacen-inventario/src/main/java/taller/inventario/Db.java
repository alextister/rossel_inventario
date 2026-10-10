package taller.inventario;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Acceso a SQLite con UNA sola conexión. Todo pasa por tx(), que es synchronized:
 * las operaciones se ejecutan de una en una, así dos aprobaciones simultáneas
 * nunca pueden reservar la misma última pieza.
 */
public final class Db {
    @FunctionalInterface
    public interface Work<T> { T run(Db db) throws Exception; }

    private final Connection conn;

    public Db(String path) throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        conn.setAutoCommit(false);
        try (InputStream in = Db.class.getResourceAsStream("/schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) try (Statement s = conn.createStatement()) { s.execute(stmt); }
            }
        }
        conn.commit();
    }

    /** Ejecuta todo en una transacción: o se guarda completo o no se guarda nada. */
    public synchronized <T> T tx(Work<T> work) {
        try {
            T r = work.run(this);
            conn.commit();
            return r;
        } catch (Throwable e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) {
            if (p[i] == null) ps.setNull(i + 1, Types.NULL); else ps.setObject(i + 1, p[i]);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... p) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) { throw new RuntimeException(e.getMessage() + " :: " + sql, e); }
    }

    public Map<String, Object> one(String sql, Object... p) {
        List<Map<String, Object>> r = query(sql, p);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Primer valor de la primera fila como número (0 si no hay). */
    public long num(String sql, Object... p) {
        Map<String, Object> m = one(sql, p);
        if (m == null) return 0;
        Object v = m.values().iterator().next();
        return v == null ? 0 : ((Number) v).longValue();
    }

    public int update(String sql, Object... p) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, p);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e.getMessage() + " :: " + sql, e); }
    }

    public long insert(String sql, Object... p) {
        update(sql, p);
        return num("SELECT last_insert_rowid()");
    }

    public static long l(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    public static Long ln(Object o) { return o == null ? null : ((Number) o).longValue(); }
}
