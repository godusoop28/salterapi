package mx.salter.api.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.Clocks;
import mx.salter.api.guide.GuideStatus;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.Permission;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Indicadores calculados directamente en PostgreSQL a partir de las guías y su historial de
 * movimientos, de modo que los reportes coinciden con las operaciones almacenadas.
 */
@Service
public class ReportService {
    public record Filter(LocalDate from, LocalDate to, Long senderId, Long shiftId, Long courierId, GuideStatus status, String destination,
                         String client, String subRoute, Long shippingRouteId, Long manifestId, String folio) {
        public Filter(LocalDate from, LocalDate to, Long senderId, Long shiftId, Long courierId, GuideStatus status, String destination) {
            this(from, to, senderId, shiftId, courierId, status, destination, null, null, null, null, null);
        }

        Filter with(LocalDate f, LocalDate t, Long sender, Long courier) {
            return new Filter(f, t, sender, shiftId, courier, status, destination, client, subRoute, shippingRouteId, manifestId, folio);
        }
    }

    /** Número de pedidos de una guía (separados por coma u otro separador en order_reference). */
    static final String ORDERS_SQL = "coalesce(cardinality(array_remove(regexp_split_to_array(upper(coalesce(g.order_reference, '')), '[^A-Z0-9-]+'), '')), 0)";

    /**
     * Gráfica del día del cliente. Conjunto evaluado: guías del remitente CONFIRMADAS en el día (zona horaria de México),
     * sin canceladas. Cada guía cae en UNA sola categoría según su estado actual:
     * entregadas = ENTREGADA; en tránsito = EN_TRANSITO, RECIBIDA_DESTINO, CON_INCIDENCIA;
     * pendientes = CONFIRMADA, LISTA_SALIDA, RETENIDA. Los porcentajes suman 100 (método del residuo mayor).
     */
    public record DayStatus(LocalDate date, Long senderId, long total, long delivered, long inTransit, long pending,
                            int deliveredPct, int inTransitPct, int pendingPct, Map<String, List<String>> definition) {}

    static final Map<String, List<String>> DAY_BUCKETS = new LinkedHashMap<>();
    static {
        DAY_BUCKETS.put("delivered", List.of("ENTREGADA"));
        DAY_BUCKETS.put("inTransit", List.of("EN_TRANSITO", "RECIBIDA_DESTINO", "CON_INCIDENCIA"));
        DAY_BUCKETS.put("pending", List.of("CONFIRMADA", "LISTA_SALIDA", "RETENIDA"));
    }

    /** Porcentajes enteros que suman exactamente 100 (residuo mayor). Con total 0 devuelve ceros. */
    public static int[] percentages(long... counts) {
        long total = 0;
        for (long c : counts) total += c;
        int[] out = new int[counts.length];
        if (total == 0) return out;
        double[] rem = new double[counts.length];
        int assigned = 0;
        for (int i = 0; i < counts.length; i++) {
            double exact = counts[i] * 100.0 / total;
            out[i] = (int) Math.floor(exact);
            rem[i] = exact - out[i];
            assigned += out[i];
        }
        while (assigned < 100) {
            int best = 0;
            for (int i = 1; i < rem.length; i++) if (rem[i] > rem[best]) best = i;
            out[best]++;
            rem[best] = -1;
            assigned++;
        }
        return out;
    }

    @Transactional(readOnly = true)
    public DayStatus today(LocalDate date, Long requestedSender) {
        SalterPrincipal p = CurrentUser.get();
        Long senderId = requestedSender;
        if (p.restrictedToSender()) {
            if (p.senderId() == null) throw ApiException.forbidden("Tu usuario no tiene remitente asignado.");
            senderId = p.senderId();
        }
        LocalDate day = date == null ? LocalDate.now(clock.withZone(zone)) : date;
        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("from", java.sql.Timestamp.from(Clocks.startOfDay(day, zone)))
                .addValue("to", java.sql.Timestamp.from(Clocks.endOfDayExclusive(day, zone)));
        String where = "";
        if (senderId != null) {
            where = " and g.sender_id = :senderId";
            ps.addValue("senderId", senderId);
        }
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("select g.status, count(*) c from guides g where g.confirmed_at >= :from and g.confirmed_at < :to "
                + "and g.status <> 'CANCELADA'" + where + " group by g.status", ps, rs -> { byStatus.put(rs.getString("status"), rs.getLong("c")); });
        long[] c = new long[3];
        int k = 0;
        for (List<String> states : DAY_BUCKETS.values()) {
            for (String st : states) c[k] += byStatus.getOrDefault(st, 0L);
            k++;
        }
        int[] pct = percentages(c);
        return new DayStatus(day, senderId, c[0] + c[1] + c[2], c[0], c[1], c[2], pct[0], pct[1], pct[2], DAY_BUCKETS);
    }

    public record Summary(LocalDate from, LocalDate to, Map<String, Object> filters,
                          long generated, long confirmed, long readyForDispatch, long dispatched, long inTransit,
                          long received, long delivered, long pending, long incidentsCreated, long incidentsOpen,
                          Map<String, Long> currentByStatus, List<Map<String, Object>> byShift,
                          List<Map<String, Object>> movementsByDay, List<Map<String, Object>> byCourier,
                          List<Map<String, Object>> bySender, List<Map<String, Object>> byCreator) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    public ReportService(NamedParameterJdbcTemplate jdbc, Clock clock, ZoneId zone) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = zone;
    }

    /** Aplica el alcance: un remitente empresarial sólo ve sus propios datos. */
    public Filter scoped(Filter f) {
        SalterPrincipal p = CurrentUser.get();
        if (p.has(Permission.REPORTS_VIEW)) return f;
        if (p.has(Permission.REPORTS_OWN) && p.senderId() != null) {
            return f.with(f.from(), f.to(), p.senderId(), null);
        }
        throw ApiException.forbidden("No tienes permiso para consultar reportes.");
    }

    @Transactional(readOnly = true)
    public Summary summary(Filter raw) {
        Filter f = scoped(withDefaults(raw));
        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("from", java.sql.Timestamp.from(Clocks.startOfDay(f.from(), zone)))
                .addValue("to", java.sql.Timestamp.from(Clocks.endOfDayExclusive(f.to(), zone)));
        String gw = guideWhere(f, ps);

        // Guías elaboradas en el periodo y su estado actual
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (GuideStatus s : GuideStatus.values()) byStatus.put(s.name(), 0L);
        jdbc.query("select g.status, count(*) c from guides g where g.created_at >= :from and g.created_at < :to" + gw + " group by g.status",
                ps, rs -> { byStatus.put(rs.getString("status"), rs.getLong("c")); });
        long generated = byStatus.values().stream().mapToLong(Long::longValue).sum();

        // Eventos del periodo (según el historial de movimientos)
        Map<String, Long> events = new LinkedHashMap<>();
        jdbc.query("select m.type, count(distinct m.guide_id) c from guide_movements m join guides g on g.id = m.guide_id "
                + "where m.occurred_at >= :from and m.occurred_at < :to" + gw + " group by m.type", ps,
                rs -> { events.put(rs.getString("type"), rs.getLong("c")); });

        // Estado actual (independiente de la fecha) para pendientes y en tránsito
        Map<String, Long> current = new LinkedHashMap<>();
        for (GuideStatus s : GuideStatus.values()) current.put(s.name(), 0L);
        MapSqlParameterSource ps2 = new MapSqlParameterSource();
        String gw2 = guideWhere(f, ps2);
        jdbc.query("select g.status, count(*) c from guides g where 1=1" + gw2 + " group by g.status", ps2,
                rs -> { current.put(rs.getString("status"), rs.getLong("c")); });

        Long incCreated = jdbc.queryForObject("select count(*) from incidents i join guides g on g.id = i.guide_id "
                + "where i.created_at >= :from and i.created_at < :to" + gw, ps, Long.class);
        Long incOpen = jdbc.queryForObject("select count(*) from incidents i join guides g on g.id = i.guide_id "
                + "where i.status <> 'RESUELTA'" + gw2, ps2, Long.class);

        List<Map<String, Object>> byShift = jdbc.queryForList(
                "select coalesce(s.name, 'Sin turno') shift, count(*) guides, "
                        + "count(*) filter (where g.status <> 'BORRADOR' and g.status <> 'CANCELADA') confirmed "
                        + "from guides g left join shifts s on s.id = g.shift_id "
                        + "where g.created_at >= :from and g.created_at < :to" + gw + " group by s.name, s.start_time order by s.start_time nulls last", ps);

        List<Map<String, Object>> byDay = jdbc.queryForList(
                "select to_char((m.occurred_at at time zone :tz)::date, 'YYYY-MM-DD') as day, "
                        + "count(*) filter (where m.type = 'CREACION') created, "
                        + "count(*) filter (where m.type = 'CONFIRMACION') confirmed, "
                        + "count(*) filter (where m.type = 'DESPACHO') dispatched, "
                        + "count(*) filter (where m.type = 'RECEPCION') received, "
                        + "count(*) filter (where m.type = 'ENTREGA') delivered, "
                        + "count(*) filter (where m.type = 'INCIDENCIA') incidents, "
                        + "count(*) total "
                        + "from guide_movements m join guides g on g.id = m.guide_id "
                        + "where m.occurred_at >= :from and m.occurred_at < :to" + gw + " group by 1 order by 1",
                ps.addValue("tz", zone.getId()));

        // Productividad por operador (repartidor): guías asignadas/transportadas y lo entregado (guías, pedidos y piezas).
        List<Map<String, Object>> byCourier = jdbc.queryForList(
                "select c.id courier_id, c.name courier, count(*) filter (where a.active) active_assignments, "
                        + "count(distinct a.guide_id) guides_assigned, "
                        + "count(distinct a.guide_id) filter (where g.status in ('EN_TRANSITO','RECIBIDA_DESTINO','ENTREGADA','CON_INCIDENCIA')) guides_transported, "
                        + "count(distinct d.guide_id) delivered, "
                        + "coalesce(sum(" + ORDERS_SQL + ") filter (where d.guide_id is not null), 0) orders_delivered, "
                        + "coalesce(sum(g.package_count) filter (where d.guide_id is not null), 0) pieces_delivered "
                        + "from courier_assignments a join couriers c on c.id = a.courier_id join guides g on g.id = a.guide_id "
                        + "left join deliveries d on d.guide_id = a.guide_id and d.delivered_at >= :from and d.delivered_at < :to "
                        + "where (a.active or a.assigned_at >= :from)" + gw + " group by c.id, c.name order by c.name", ps);

        // Productividad por usuario que elaboró (nombre, no correo): guías, pedidos y piezas confirmadas.
        List<Map<String, Object>> byCreator = jdbc.queryForList(
                "select u.id user_id, u.full_name user_name, count(*) guides, "
                        + "count(*) filter (where g.status not in ('BORRADOR','CANCELADA')) confirmed, "
                        + "coalesce(sum(" + ORDERS_SQL + ") filter (where g.status not in ('BORRADOR','CANCELADA')), 0) orders, "
                        + "coalesce(sum(g.package_count) filter (where g.status not in ('BORRADOR','CANCELADA')), 0) pieces "
                        + "from guides g join users u on u.id = g.created_by "
                        + "where g.created_at >= :from and g.created_at < :to" + gw + " group by u.id, u.full_name order by guides desc limit 50", ps);

        List<Map<String, Object>> bySender = jdbc.queryForList(
                "select g.sender_id, max(g.sender_name) sender, count(*) guides "
                        + "from guides g where g.created_at >= :from and g.created_at < :to" + gw
                        + " group by g.sender_id order by guides desc limit 20", ps);

        long pending = current.get("BORRADOR") + current.get("CONFIRMADA") + current.get("LISTA_SALIDA");
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("senderId", f.senderId());
        filters.put("shiftId", f.shiftId());
        filters.put("courierId", f.courierId());
        filters.put("status", f.status());
        filters.put("destination", f.destination());
        return new Summary(f.from(), f.to(), filters, generated,
                events.getOrDefault("CONFIRMACION", 0L), current.get("LISTA_SALIDA"),
                events.getOrDefault("DESPACHO", 0L), current.get("EN_TRANSITO"),
                events.getOrDefault("RECEPCION", 0L), events.getOrDefault("ENTREGA", 0L), pending,
                incCreated == null ? 0 : incCreated, incOpen == null ? 0 : incOpen, current, byShift, byDay, byCourier, bySender, byCreator);
    }

    /** Filas para exportar (máximo 10 000) con los mismos filtros. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> guideRows(Filter raw) {
        Filter f = scoped(withDefaults(raw));
        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("from", java.sql.Timestamp.from(Clocks.startOfDay(f.from(), zone)))
                .addValue("to", java.sql.Timestamp.from(Clocks.endOfDayExclusive(f.to(), zone)))
                .addValue("tz", zone.getId());
        String gw = guideWhere(f, ps);
        // Sin LPN/DPN: el reporte es por guía (los identificadores de bulto no se exportan).
        return jdbc.queryForList(
                "select coalesce(g.folio, 'BORRADOR-' || g.id) folio, g.status, "
                        + "to_char(g.created_at at time zone :tz, 'YYYY-MM-DD HH24:MI') created_at, "
                        + "to_char(g.confirmed_at at time zone :tz, 'YYYY-MM-DD HH24:MI') confirmed_at, "
                        + "g.sender_name, g.rcpt_name, g.rcpt_city, g.rcpt_state, g.sub_route, sr.code shipping_route, "
                        + "(select m.number from manifest_guides mg join manifests m on m.id = mg.manifest_id where mg.guide_id = g.id and mg.active limit 1) manifest, "
                        + "g.order_reference, " + ORDERS_SQL + " orders, g.package_count, "
                        + "s.name shift, u.full_name created_by, c.name courier "
                        + "from guides g left join shifts s on s.id = g.shift_id join users u on u.id = g.created_by "
                        + "left join shipping_routes sr on sr.id = g.shipping_route_id "
                        + "left join courier_assignments a on a.guide_id = g.id and a.active left join couriers c on c.id = a.courier_id "
                        + "where g.created_at >= :from and g.created_at < :to" + gw + " order by g.created_at limit 10000", ps);
    }

    private Filter withDefaults(Filter f) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate to = f.to() == null ? today : f.to();
        LocalDate from = f.from() == null ? to.minusDays(29) : f.from();
        if (from.isAfter(to)) throw ApiException.badRequest("INVALID_RANGE", "La fecha inicial no puede ser posterior a la final.");
        if (from.plusYears(2).isBefore(to)) throw ApiException.badRequest("INVALID_RANGE", "El periodo máximo es de 2 años.");
        return f.with(from, to, f.senderId(), f.courierId());
    }

    private static String guideWhere(Filter f, MapSqlParameterSource ps) {
        List<String> w = new ArrayList<>();
        if (f.senderId() != null) {
            w.add("g.sender_id = :senderId");
            ps.addValue("senderId", f.senderId());
        }
        if (f.shiftId() != null) {
            w.add("g.shift_id = :shiftId");
            ps.addValue("shiftId", f.shiftId());
        }
        if (f.status() != null) {
            w.add("g.status = :status");
            ps.addValue("status", f.status().name());
        }
        if (f.courierId() != null) {
            w.add("exists (select 1 from courier_assignments ca where ca.guide_id = g.id and ca.courier_id = :courierId)");
            ps.addValue("courierId", f.courierId());
        }
        if (f.destination() != null && !f.destination().isBlank()) {
            w.add("(lower(g.rcpt_city) like :dest or lower(g.rcpt_state) like :dest or upper(g.destination_code) = upper(:destCode))");
            ps.addValue("dest", "%" + f.destination().trim().toLowerCase() + "%");
            ps.addValue("destCode", f.destination().trim());
        }
        if (f.client() != null && !f.client().isBlank()) {
            w.add("g.search_text like :client");
            ps.addValue("client", mx.salter.api.common.Text.likePattern(f.client()));
        }
        if (f.subRoute() != null && !f.subRoute().isBlank()) {
            w.add("g.sub_route = :subRoute");
            ps.addValue("subRoute", mx.salter.api.recipient.RecipientDtos.normalizeSubRoute(f.subRoute()));
        }
        if (f.shippingRouteId() != null) {
            w.add("g.shipping_route_id = :routeId");
            ps.addValue("routeId", f.shippingRouteId());
        }
        if (f.manifestId() != null) {
            w.add("exists (select 1 from manifest_guides mg where mg.guide_id = g.id and mg.manifest_id = :manifestId)");
            ps.addValue("manifestId", f.manifestId());
        }
        if (f.folio() != null && !f.folio().isBlank()) {
            w.add("upper(g.folio) = :folio");
            ps.addValue("folio", mx.salter.api.guide.GuideService.normalizeFolio(f.folio()));
        }
        return w.isEmpty() ? "" : " and " + String.join(" and ", w);
    }

    Instant now() {
        return clock.instant();
    }
}
