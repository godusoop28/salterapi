package mx.salter.api.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea el primer administrador cuando la base está vacía, a partir de variables de entorno.
 * Nunca hay credenciales en el código ni en las migraciones.
 */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;
    private final String name;

    public AdminBootstrap(UserRepository users, RoleRepository roles, PasswordEncoder encoder,
                          @Value("${salter.bootstrap.admin-email:}") String email,
                          @Value("${salter.bootstrap.admin-password:}") String password,
                          @Value("${salter.bootstrap.admin-name:Administrador SALTER}") String name) {
        this.users = users;
        this.roles = roles;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("No hay usuarios. Define SALTER_BOOTSTRAP_ADMIN_EMAIL y SALTER_BOOTSTRAP_ADMIN_PASSWORD para crear el primer administrador.");
            return;
        }
        PasswordPolicy.validate(password);
        User u = new User();
        u.setEmail(email.trim().toLowerCase());
        u.setFullName(name);
        u.setPasswordHash(encoder.encode(password));
        u.setRole(roles.findByCode("ADMIN").orElseThrow());
        users.save(u);
        log.info("Administrador inicial creado: {}", u.getEmail());
    }
}
