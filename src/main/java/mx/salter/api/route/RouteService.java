package mx.salter.api.route;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.auth.AuthDtos.Ref;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.Text;
import mx.salter.api.guide.GuideStatus;
import mx.salter.api.route.RouteDtos.RouteDto;
import mx.salter.api.route.RouteDtos.RouteOverview;
import mx.salter.api.route.RouteDtos.RouteRequest;
import mx.salter.api.user.UserRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RouteService {
    private final ShippingRouteRepository routes;
    private final UserRepository users;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;

    public RouteService(ShippingRouteRepository routes, UserRepository users, AuditService audit, NamedParameterJdbcTemplate jdbc) {
        this.routes = routes;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<RouteDto> list(Boolean active) {
        return routes.list(active).stream().map(RouteDto::of).toList();
    }

    /** Ruta activa o error 400/422. */
    @Transactional(readOnly = true)
    public ShippingRoute requireActive(Long id) {
        ShippingRoute r = routes.findById(id).orElseThrow(() -> ApiException.badRequest("INVALID_ROUTE", "Ruta de embarque no válida."));
        if (!r.isActive()) throw ApiException.business("ROUTE_INACTIVE", "La ruta de embarque " + r.getCode() + " está inactiva.");
        return r;
    }

    /**
     * Ruta de embarque activa cuya clave es la RUTA del destinatario (listado de clientes, ej. «74» o «074»).
     * Vacío si no hay ruta o no está dada de alta.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ShippingRoute> findActiveByClientRoute(String clientRoute) {
        String code = Text.trimToNull(clientRoute);
        if (code == null) return java.util.Optional.empty();
        if (code.matches("\\d{1,15}(\\.0+)?")) code = String.valueOf(Long.parseLong(code.replaceAll("\\.0+$", "")));
        return routes.findFirstByCodeIgnoreCaseAndActiveTrue(code);
    }

    @Transactional
    public RouteDto create(RouteRequest req) {
        String code = req.code().trim().toUpperCase();
        if (routes.existsByCodeIgnoreCase(code)) throw ApiException.conflict("CODE_TAKEN", "Ya existe una ruta con la clave " + code + ".");
        ShippingRoute r = new ShippingRoute();
        r.setCode(code);
        apply(r, req);
        routes.saveAndFlush(r);
        audit.record("ROUTE_CREATED", "SHIPPING_ROUTE", r.getId(), Map.of("code", code));
        return RouteDto.of(r);
    }

    @Transactional
    public RouteDto update(Long id, RouteRequest req) {
        ShippingRoute r = routes.findById(id).orElseThrow(() -> ApiException.notFound("Ruta"));
        if (req.version() != null && req.version() != r.getVersion()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "La ruta fue modificada por otra persona. Recarga la información.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("code", r.getCode());
        before.put("name", r.getName());
        before.put("active", r.isActive());
        String code = req.code().trim().toUpperCase();
        if (!code.equals(r.getCode())) {
            if (routes.existsByCodeIgnoreCaseAndIdNot(code, id)) throw ApiException.conflict("CODE_TAKEN", "Ya existe una ruta con la clave " + code + ".");
            r.setCode(code);
        }
        apply(r, req);
        routes.flush();
        audit.record("ROUTE_UPDATED", "SHIPPING_ROUTE", r.getId(), Map.of("before", before, "code", r.getCode(), "active", r.isActive()));
        return RouteDto.of(r);
    }

    private void apply(ShippingRoute r, RouteRequest req) {
        r.setName(req.name().trim());
        if (req.active() != null) r.setActive(req.active());
        r.setNotes(Text.trimToNull(req.notes()));
        r.setResponsible(req.responsibleUserId() == null ? null
                : users.findById(req.responsibleUserId()).orElseThrow(() -> ApiException.badRequest("INVALID_USER", "Responsable no válido.")));
    }

    /** Panorama por ruta para la sección de operaciones (consultas agrupadas, sin N+1). */
    @Transactional(readOnly = true)
    public List<RouteOverview> overview() {
        Map<Long, Map<String, Long>> byStatus = new HashMap<>();
        jdbc.query("select g.shipping_route_id rid, g.status, count(*) c from guides g where g.shipping_route_id is not null "
                + "and g.status not in ('BORRADOR','CANCELADA','ENTREGADA') group by 1, 2", new MapSqlParameterSource(), rs -> {
            byStatus.computeIfAbsent(rs.getLong("rid"), k -> new LinkedHashMap<>()).put(rs.getString("status"), rs.getLong("c"));
        });
        Map<Long, Long> pending = new HashMap<>();
        jdbc.query("select g.shipping_route_id rid, count(*) c from guides g where g.shipping_route_id is not null and g.status = 'CONFIRMADA' "
                        + "and not exists (select 1 from manifest_guides mg where mg.guide_id = g.id and mg.active) group by 1",
                new MapSqlParameterSource(), rs -> { pending.put(rs.getLong("rid"), rs.getLong("c")); });
        Map<Long, Object[]> open = new HashMap<>();
        jdbc.query("select m.shipping_route_id rid, m.id, m.number, "
                + "(select count(*) from manifest_guides mg where mg.manifest_id = m.id and mg.active) c "
                + "from manifests m where m.status = 'BORRADOR' and m.shipping_route_id is not null", new MapSqlParameterSource(), rs -> {
            open.put(rs.getLong("rid"), new Object[]{rs.getLong("id"), rs.getString("number"), rs.getInt("c")});
        });
        List<GuideStatus> shown = List.of(GuideStatus.CONFIRMADA, GuideStatus.LISTA_SALIDA, GuideStatus.EN_TRANSITO,
                GuideStatus.RECIBIDA_DESTINO, GuideStatus.CON_INCIDENCIA, GuideStatus.RETENIDA);
        return routes.list(null).stream().map(r -> {
            Object[] o = open.get(r.getId());
            Map<String, Long> st = new LinkedHashMap<>();
            for (GuideStatus s : shown) st.put(s.name(), byStatus.getOrDefault(r.getId(), Map.of()).getOrDefault(s.name(), 0L));
            return new RouteOverview(RouteDto.of(r), o == null ? null : new Ref((Long) o[0], (String) o[1]),
                    o == null ? 0 : (Integer) o[2], st, pending.getOrDefault(r.getId(), 0L));
        }).toList();
    }
}
