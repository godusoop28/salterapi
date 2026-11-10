package mx.salter.api.report;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
    public record Filter(LocalDate from, LocalDate to, Long senderId, Long shiftId, Long courierId, GuideStatus status, String destination) {}

    public record Summary(LocalDate from, LocalDate to, Map<String, Object> filters,
                          long generated, long confirmed, long readyForDispatch, long dispatched, long inTransit,
                          long received, long delivered, long pending, long incidentsCreated, long incidentsOpen,
                          Map<String, Long> currentByStatus, List<Map<String, Object>> byShift,
                          List<Map<String, Object>> movementsByDay, List<Map<String, Object>> byCourier,
                          List<Map<String, Object>> bySender) {}

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
            return new Filter(f.from(), f.to(), p.senderId(), f.shiftId(), null, f.status(), f.destination());
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

        List<Map<String, Object>> byCourier = jdbc.queryForList(
                "select c.id courier_id, c.name courier, count(*) filter (where a.active) active_assignments, "
                        + "count(distinct d.guide_id) delivered "
                        + "from courier_assignments a join couriers c on c.id = a.courier_id join guides g on g.id = a.guide_id "
                        + "left join deliveries d on d.guide_id = a.guide_id and d.delivered_at >= :from and d.delivered_at < :to "
                        + "where (a.active or a.assigned_at >= :from)" + gw + " group by c.id, c.name order by c.name", ps);

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
                incCreated == null ? 0 : incCreated, incOpen == null ? 0 : incOpen, current, byShift, byDay, byCourier, bySender);
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
        return jdbc.queryForList(
                "select coalesce(g.folio, 'BORRADOR-' || g.id) folio, g.status, "
                        + "to_char(g.created_at at time zone :tz, 'YYYY-MM-DD HH24:MI') created_at, "
                        + "to_char(g.confirmed_at at time zone :tz, 'YYYY-MM-DD HH24:MI') confirmed_at, "
                        + "g.sender_name, g.rcpt_name, g.rcpt_city, g.rcpt_state, g.cd_reference, g.order_reference, g.package_count, "
                        + "s.name shift, u.full_name created_by, c.name courier "
                        + "from guides g left join shifts s on s.id = g.shift_id join users u on u.id = g.created_by "
                        + "left join courier_assignments a on a.guide_id = g.id and a.active left join couriers c on c.id = a.courier_id "
                        + "where g.created_at >= :from and g.created_at < :to" + gw + " order by g.created_at limit 10000", ps);
    }

    private Filter withDefaults(Filter f) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate to = f.to() == null ? today : f.to();
        LocalDate from = f.from() == null ? to.minusDays(29) : f.from();
        if (from.isAfter(to)) throw ApiException.badRequest("INVALID_RANGE", "La fecha inicial no puede ser posterior a la final.");
        if (from.plusYears(2).isBefore(to)) throw ApiException.badRequest("INVALID_RANGE", "El periodo máximo es de 2 años.");
        return new Filter(from, to, f.senderId(), f.shiftId(), f.courierId(), f.status(), f.destination());
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
            w.add("(lower(g.rcpt_city) like :dest or lower(g.rcpt_state) like :dest)");
            ps.addValue("dest", "%" + f.destination().trim().toLowerCase() + "%");
        }
        return w.isEmpty() ? "" : " and " + String.join(" and ", w);
    }

    Instant now() {
        return clock.instant();
    }
}
