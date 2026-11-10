package mx.salter.api.config;

import java.util.Set;
import mx.salter.api.common.Address;
import mx.salter.api.operation.Courier;
import mx.salter.api.operation.CourierRepository;
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
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SOLO perfil dev: crea un remitente de ejemplo, un repartidor y un usuario por rol para probar
 * el flujo completo. Requiere SALTER_DEV_PASSWORD (no hay contraseñas en el código).
 * Los datos quedan claramente marcados como "(ejemplo)".
 */
@Component
@Profile("dev")
@Order(2)
public class DevDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final SenderRepository senders;
    private final CourierRepository couriers;
    private final PasswordEncoder encoder;
    private final String password;

    public DevDataSeeder(UserRepository users, RoleRepository roles, SenderRepository senders, CourierRepository couriers,
                         PasswordEncoder encoder, @Value("${salter.dev.seed-password:}") String password) {
        this.users = users;
        this.roles = roles;
        this.senders = senders;
        this.couriers = couriers;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) return;
        if (users.existsByEmailIgnoreCase("supervisor@salter.local")) return;

        Sender s = new Sender();
        s.setCode("DEMO-01");
        s.setName("Empresa Ejemplo S.A. (ejemplo)");
        s.setKind(Sender.Kind.EMPRESA);
        s.setContactName("Contacto Ejemplo");
        s.setPhone("5550000000");
        s.setAddress(new Address("Av. Ejemplo 100", "Centro", "Ciudad de México", "CDMX", "06000", null));
        senders.save(s);

        Courier c = new Courier();
        c.setName("Repartidor Ejemplo");
        c.setPhone("5551111111");
        c.setZone("Zona Centro");
        c.setVehicle("Motocicleta");
        couriers.save(c);

        String hash = encoder.encode(password);
        if (!users.existsByEmailIgnoreCase("admin@salter.local")) create("admin@salter.local", "Administración (ejemplo)", "ADMIN", hash, null, null, Set.of());
        create("supervisor@salter.local", "Supervisor (ejemplo)", "SUPERVISOR", hash, null, null, Set.of());
        // El capturista de ejemplo recibe permisos adicionales para mostrar que son configurables por usuario.
        create("capturista@salter.local", "Capturista (ejemplo)", "CAPTURISTA", hash, s, null, Set.of("GUIDES_CONFIRM", "RECIPIENTS_IMPORT"));
        create("repartidor@salter.local", "Repartidor (ejemplo)", "REPARTIDOR", hash, null, c, Set.of());
        create("recepcion@salter.local", "Recepción (ejemplo)", "RECEPCION", hash, null, null, Set.of());
        create("remitente@salter.local", "Remitente (ejemplo)", "REMITENTE", hash, s, null, Set.of());
        log.info("Datos de ejemplo (perfil dev) creados: usuarios *@salter.local");
    }

    private void create(String email, String name, String role, String hash, Sender sender, Courier courier, Set<String> grants) {
        User u = new User();
        u.setEmail(email);
        u.setFullName(name);
        u.setPasswordHash(hash);
        u.setRole(roles.findByCode(role).orElseThrow());
        u.setSender(sender);
        u.setCourier(courier);
        u.getGrantedPermissions().addAll(grants);
        users.save(u);
    }
}
