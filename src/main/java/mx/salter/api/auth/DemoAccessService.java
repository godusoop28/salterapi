package mx.salter.api.auth;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mx.salter.api.audit.AuditService;
import mx.salter.api.auth.AuthDtos.LoginResponse;
import mx.salter.api.common.Address;
import mx.salter.api.common.ApiException;
import mx.salter.api.operation.Courier;
import mx.salter.api.operation.CourierRepository;
import mx.salter.api.recipient.Recipient;
import mx.salter.api.recipient.RecipientRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACCESO DE PRUEBAS (temporal). Con {@code salter.demo.enabled=true} (variable SALTER_DEMO_ACCESS):
 * <ul>
 *   <li>al arrancar crea un usuario de prueba por rol (*.demo@salter.test), un remitente, un repartidor y
 *       algunos destinatarios de ejemplo, si no existen;</li>
 *   <li>expone {@code POST /api/auth/demo-login} para entrar con un clic eligiendo el rol.</li>
 * </ul>
 * Los usuarios de prueba tienen una contraseña aleatoria que nadie conoce: sólo se entra por el botón.
 * Para desactivarlo: SALTER_DEMO_ACCESS=false (el endpoint responde 404 y los botones desaparecen).
 */
@Service
@Order(3)
public class DemoAccessService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoAccessService.class);
    private static final String SENDER_CODE = "PRUEBAS";

    public record DemoRole(String code, String email, String name, String description) {}

    public static final List<DemoRole> ROLES = List.of(
            new DemoRole("ADMIN", "admin.demo@salter.test", "Administrador (pruebas)",
                    "Acceso total: usuarios, catálogos, configuración y reportes."),
            new DemoRole("SUPERVISOR", "supervisor.demo@salter.test", "Supervisor (pruebas)",
                    "Confirma guías, arma manifiestos, autoriza salidas y asigna repartidores."),
            new DemoRole("CAPTURISTA", "capturista.demo@salter.test", "Capturista (pruebas)",
                    "Elabora guías para el remitente de pruebas."),
            new DemoRole("RECEPCION", "recepcion.demo@salter.test", "Recepción (pruebas)",
                    "Busca guías por folio y confirma la recepción en destino."),
            new DemoRole("REPARTIDOR", "repartidor.demo@salter.test", "Repartidor (pruebas)",
                    "App móvil: sus envíos asignados, incidencias y entrega."),
            new DemoRole("REMITENTE", "remitente.demo@salter.test", "Remitente (pruebas)",
                    "Portal del cliente: sólo sus envíos y reportes."));

    private final boolean enabled;
    private final UserRepository users;
    private final RoleRepository roles;
    private final SenderRepository senders;
    private final CourierRepository couriers;
    private final RecipientRepository recipients;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final Clock clock;

    public DemoAccessService(@Value("${salter.demo.enabled:false}") boolean enabled, UserRepository users,
                             RoleRepository roles, SenderRepository senders, CourierRepository couriers,
                             RecipientRepository recipients, PasswordEncoder encoder, JwtService jwt,
                             AuditService audit, Clock clock) {
        this.enabled = enabled;
        this.users = users;
        this.roles = roles;
        this.senders = senders;
        this.couriers = couriers;
        this.recipients = recipients;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        Sender sender = senders.findAll().stream().filter(s -> SENDER_CODE.equalsIgnoreCase(s.getCode())).findFirst()
                .orElseGet(this::createSender);
        Courier courier = couriers.findAllByOrderByNameAsc().stream().filter(c -> "Repartidor de Pruebas".equals(c.getName()))
                .findFirst().orElseGet(this::createCourier);
        int created = 0;
        for (DemoRole r : ROLES) {
            if (users.existsByEmailIgnoreCase(r.email())) continue;
            User u = new User();
            u.setEmail(r.email());
            u.setFullName(r.name());
            u.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
            u.setRole(roles.findByCode(r.code()).orElseThrow());
            if (r.code().equals("CAPTURISTA") || r.code().equals("REMITENTE")) u.setSender(sender);
            if (r.code().equals("REPARTIDOR")) u.setCourier(courier);
            if (r.code().equals("CAPTURISTA")) u.getGrantedPermissions().addAll(Set.of("GUIDES_CONFIRM", "RECIPIENTS_IMPORT"));
            users.save(u);
            created++;
        }
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

    private Sender createSender() {
        Sender s = new Sender();
        s.setCode(SENDER_CODE);
        s.setName("Empresa de Pruebas S.A.");
        s.setKind(Sender.Kind.EMPRESA);
        s.setContactName("Contacto de Pruebas");
        s.setPhone("5550000000");
        s.setAddress(new Address("Av. Ejemplo 100", "Centro", "Puebla", "Puebla", "72000", null));
        s.setPlazaCode("PBA");
        s.setGuidePrefix("PRU");
        senders.save(s);
        String[][] rows = {
                {"Ana", "López Pérez", "9511000001", "Oaxaca de Juárez", "Oaxaca", "68000", "Centro", "Macedonio Alcalá 101"},
                {"Bruno", "Martínez Ruiz", "9511000002", "Oaxaca de Juárez", "Oaxaca", "68050", "Reforma", "Av. Universidad 220"},
                {"Carla", "Hernández Díaz", "9511000003", "Santa Lucía del Camino", "Oaxaca", "71228", "Centro", "Calle Juárez 15"},
                {"Daniel", "García Santos", "9511000004", "Tlacolula", "Oaxaca", "70400", "Centro", "Av. 2 de Abril 33"},
                {"Elena", "Ramírez Cruz", "2221000005", "Puebla", "Puebla", "72000", "Centro", "5 de Mayo 410"},
                {"Fernando", "Sánchez Mora", "2221000006", "Cholula", "Puebla", "72760", "San Andrés", "Calle 14 Poniente 8"},
                {"Gabriela", "Torres Vega", "5551000007", "Ciudad de México", "CDMX", "06700", "Roma Norte", "Orizaba 45"},
                {"Hugo", "Flores Reyes", "5551000008", "Ciudad de México", "CDMX", "03100", "Del Valle", "Av. Coyoacán 1200"},
        };
        for (String[] x : rows) {
            Recipient rc = new Recipient();
            rc.setSender(s);
            rc.setFirstName(x[0]);
            rc.setLastName(x[1]);
            rc.setPhone(x[2]);
            rc.setAddress(new Address(x[7], x[6], x[3], x[4], x[5], null));
            rc.setNotes("Destinatario de pruebas");
            recipients.save(rc);
        }
        return s;
    }

    private Courier createCourier() {
        Courier c = new Courier();
        c.setName("Repartidor de Pruebas");
        c.setPhone("5551111111");
        c.setZone("Zona Centro");
        c.setVehicle("Motocicleta");
        return couriers.save(c);
    }
}
