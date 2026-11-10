package mx.salter.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.auth.AuthDtos.LoginResponse;
import mx.salter.api.auth.AuthDtos.MeResponse;
import mx.salter.api.auth.AuthDtos.Ref;
import mx.salter.api.common.ApiException;
import mx.salter.api.security.JwtService;
import mx.salter.api.security.SecurityProperties;
import mx.salter.api.user.PasswordPolicy;
import mx.salter.api.user.User;
import mx.salter.api.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    /** Hash ficticio para igualar tiempos cuando el correo no existe. */
    private final String dummyHash;

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final SecurityProperties props;
    private final AuditService audit;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, SecurityProperties props,
                       AuditService audit, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResponse login(String email, String password) {
        Instant now = clock.instant();
        User u = users.findByEmailForLogin(email.trim()).orElse(null);
        if (u == null) {
            encoder.matches(password, dummyHash);
            audit.recordIndependent("LOGIN_FAILED", "USER", null, Map.of("email", email.trim().toLowerCase()), null, null);
            throw invalid();
        }
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)) {
            audit.recordIndependent("LOGIN_LOCKED", "USER", u.getId(), null, u.getId(), u.getEmail());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED",
                    "La cuenta está bloqueada temporalmente por intentos fallidos. Intenta más tarde.");
        }
        if (!encoder.matches(password, u.getPasswordHash())) {
            u.setFailedAttempts(u.getFailedAttempts() + 1);
            if (u.getFailedAttempts() >= props.maxFailedLogins()) {
                u.setLockedUntil(now.plus(props.lockDuration()));
                u.setFailedAttempts(0);
            }
            audit.recordIndependent("LOGIN_FAILED", "USER", u.getId(), null, u.getId(), u.getEmail());
            throw invalid();
        }
        if (!u.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "Esta cuenta está desactivada. Contacta a administración.");
        }
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        u.setLastLoginAt(now);
        JwtService.Issued issued = jwt.issue(u.getId(), u.getTokenVersion());
        audit.recordIndependent("LOGIN", "USER", u.getId(), null, u.getId(), u.getEmail());
        return new LoginResponse(issued.token(), issued.expiresAt(), toMe(u));
    }

    @Transactional(readOnly = true)
    public MeResponse me(long userId) {
        return users.findForPrincipal(userId).map(AuthService::toMe).orElseThrow(() -> ApiException.notFound("Usuario"));
    }

    @Transactional
    public void changePassword(long userId, String current, String next) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Usuario"));
        if (!encoder.matches(current, u.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_PASSWORD", "La contraseña actual no es correcta.");
        }
        PasswordPolicy.validate(next);
        u.setPasswordHash(encoder.encode(next));
        u.setTokenVersion(u.getTokenVersion() + 1); // invalida otras sesiones
        audit.record("PASSWORD_CHANGED", "USER", u.getId(), null);
    }

    /** Cierra la sesión en todos los dispositivos. */
    @Transactional
    public void logoutAll(long userId) {
        users.findById(userId).ifPresent(u -> {
            u.setTokenVersion(u.getTokenVersion() + 1);
            audit.record("LOGOUT_ALL", "USER", u.getId(), null);
        });
    }

    public static MeResponse toMe(User u) {
        return new MeResponse(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole().getCode(),
                u.getRole().getName(), u.effectivePermissions().stream().sorted().toList(),
                u.getSender() == null ? null : new Ref(u.getSender().getId(), u.getSender().getName()),
                u.getCourier() == null ? null : new Ref(u.getCourier().getId(), u.getCourier().getName()),
                u.getLastLoginAt());
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Correo o contraseña incorrectos.");
    }
}
