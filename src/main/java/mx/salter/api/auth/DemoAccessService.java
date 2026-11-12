package mx.salter.api.auth;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mx.salter.api.audit.AuditService;
import mx.salter.api.auth.AuthDtos.LoginResponse;
import mx.salter.api.common.Address;
import mx.salter.api.common.ApiException;
import mx.salter.api.operation.Courier;
import mx.salter.api.operation.CourierRepository;
import mx.salter.api.security.JwtService;
import mx.salter.api.sender.Sender;
import mx.salter.api.sender.SenderRepository;
import mx.salter.api.user.RoleRepository;
import mx.salter.api.user.User;
import mx.salter.api.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACCESO DE PRUEBAS (temporal). Con {@code salter.demo.enabled=true} (variable SALTER_DEMO_ACCESS):
 * <ul>
 *   <li>al arrancar asegura el remitente piloto <b>ATM (Auto Todo Mexicana)</b>, un repartidor y un usuario de prueba
 *       por rol (*.demo@salter.test). El capturista y el remitente de prueba quedan ligados a ATM;</li>
 *   <li>elimina los datos de relleno que existieron antes (remitente "PRUEBAS" con sus destinatarios y guías);</li>
 *   <li>expone {@code POST /api/auth/demo-login} para entrar o cambiar de rol con un clic.</li>
 * </ul>
 * Los usuarios de prueba tienen una contraseña aleatoria que nadie conoce: sólo se entra por el botón.
 * Para desactivarlo: SALTER_DEMO_ACCESS=false (el endpoint responde 404 y los botones desaparecen).
 */
@Service
@Order(3)
public class DemoAccessService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoAccessService.class);
    private static final String PILOT_CODE = "ATM";
    private static final String FILLER_CODE = "PRUEBAS";
    private static final String COURIER_NAME = "Repartidor de Pruebas";

    public record DemoRole(String code, String email, String name, String description) {}

    public static final List<DemoRole> ROLES = List.of(
            new DemoRole("REMITENTE", "remitente.demo@salter.test", "ATM (remitente)",
                    "Remitente ATM: elabora sus guías (busca al cliente por nombre o número) y consulta sus envíos y reportes."),
            new DemoRole("ADMIN", "admin.demo@salter.test", "Administrador (pruebas)",
                    "Administrador general: guías, manifiestos, salidas, repartidores, usuarios y reportes."),
            new DemoRole("RECEPCION", "recepcion.demo@salter.test", "Receptor (pruebas)",
                    "Busca guías por folio y confirma la recepción en destino."),
            new DemoRole("REPARTIDOR", "repartidor.demo@salter.test", "Repartidor (pruebas)",
                    "App móvil: sus envíos asignados, incidencias y entrega."));

    private final boolean enabled;
    private final UserRepository users;
    private final RoleRepository roles;
    private final SenderRepository senders;
    private final CourierRepository couriers;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public DemoAccessService(@Value("${salter.demo.enabled:false}") boolean enabled, UserRepository users,
                             RoleRepository roles, SenderRepository senders, CourierRepository couriers,
                             PasswordEncoder encoder, JwtService jwt, AuditService audit, Clock clock, JdbcTemplate jdbc) {
        this.enabled = enabled;
        this.users = users;
        this.roles = roles;
        this.senders = senders;
        this.couriers = couriers;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        Sender atm = findByCode(PILOT_CODE).orElseGet(this::createPilotSender);
        Courier courier = couriers.findAllByOrderByNameAsc().stream().filter(c -> COURIER_NAME.equals(c.getName()))
                .findFirst().orElseGet(this::createCourier);
        int created = 0;
        for (DemoRole r : ROLES) {
            User u = users.findByEmailForLogin(r.email()).orElse(null);
            if (u == null) {
                u = new User();
                u.setEmail(r.email());
                u.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
                u.setRole(roles.findByCode(r.code()).orElseThrow());
                created++;
            }
            u.setFullName(r.name());
            if (r.code().equals("REMITENTE")) u.setSender(atm);
            if (r.code().equals("REPARTIDOR")) u.setCourier(courier);
            users.save(u);
        }
        users.flush();
        findByCode(FILLER_CODE).ifPresent(f -> purgeFiller(f.getId(), atm.getId()));
        log.warn("ACCESO DE PRUEBAS ACTIVO (SALTER_DEMO_ACCESS=true). Usuarios de prueba creados ahora: {}. Desactivar en producción real.", created);
    }

    @Transactional
    public LoginResponse login(String roleCode) {
        if (!enabled) throw ApiException.notFound("Recurso");
        DemoRole r = ROLES.stream().filter(x -> x.code().equalsIgnoreCase(roleCode == null ? "" : roleCode.trim()))
                .findFirst().orElseThrow(() -> ApiException.badRequest("INVALID_ROLE", "Rol de prueba no válido."));
        User u = users.findByEmailForLogin(r.email())
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_NOT_READY",
                        "El usuario de prueba aún no existe. Reinicia la API para crearlo."));
        if (!u.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Este usuario de prueba fue desactivado.");
        }
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        u.setLastLoginAt(clock.instant());
        JwtService.Issued issued = jwt.issue(u.getId(), u.getTokenVersion());
        audit.recordIndependent("LOGIN_DEMO", "USER", u.getId(), null, u.getId(), u.getEmail());
        return new LoginResponse(issued.token(), issued.expiresAt(), AuthService.toMe(u));
    }

    private Optional<Sender> findByCode(String code) {
        return senders.findAll().stream().filter(s -> code.equalsIgnoreCase(s.getCode())).findFirst();
    }

    /** Elimina el remitente de relleno "PRUEBAS" con sus destinatarios, guías y todo lo que cuelga de ellas. */
    private void purgeFiller(long fillerId, long pilotId) {
        String g = "(select id from guides where sender_id = " + fillerId + ")";
        jdbc.update("delete from incidents where guide_id in " + g);
        jdbc.update("delete from deliveries where guide_id in " + g);
        jdbc.update("delete from receptions where guide_id in " + g);
        jdbc.update("delete from courier_assignments where guide_id in " + g);
        jdbc.update("delete from manifest_guides where guide_id in " + g);
        jdbc.update("delete from guide_movements where guide_id in " + g);
        int guides = jdbc.update("delete from guides where sender_id = ?", fillerId);
        jdbc.update("delete from dispatches where manifest_id in (select id from manifests where sender_id = ?)", fillerId);
        jdbc.update("delete from manifests where sender_id = ?", fillerId);
        int recipients = jdbc.update("delete from recipients where sender_id = ?", fillerId);
        jdbc.update("update users set sender_id = ? where sender_id = ?", pilotId, fillerId);
        jdbc.update("delete from senders where id = ?", fillerId);
        log.warn("Datos de relleno eliminados: remitente PRUEBAS, {} guías y {} destinatarios.", guides, recipients);
    }

    private Sender createPilotSender() {
        Sender s = new Sender();
        s.setCode(PILOT_CODE);
        s.setName("AUTO TODO MEXICANA");
        s.setKind(Sender.Kind.EMPRESA);
        s.setAddress(new Address(null, null, "Puebla", "Puebla", null, null));
        s.setPlazaCode("PBA");
        s.setGuidePrefix("ATM");
        return senders.save(s);
    }

    private Courier createCourier() {
        Courier c = new Courier();
        c.setName(COURIER_NAME);
        c.setPhone("5551111111");
        c.setZone("Zona Centro");
        c.setVehicle("Motocicleta");
        return couriers.save(c);
    }
}
