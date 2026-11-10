package mx.salter.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/** Emite y valida tokens firmados (HS256). El token sólo lleva id de usuario y versión. */
@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "salter-api";

    private final SecretKey key;
    private final SecurityProperties props;
    private final Clock clock;

    public record Issued(String token, Instant expiresAt) {}
    public record Parsed(long userId, int tokenVersion) {}

    public JwtService(SecurityProperties props, Clock clock, Environment env) {
        this.props = props;
        this.clock = clock;
        String secret = props.jwtSecret();
        if (secret == null || secret.length() < 32) {
            if (env.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("SALTER_JWT_SECRET es obligatorio en producción (mínimo 32 caracteres).");
            }
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            log.warn("SALTER_JWT_SECRET no definido: se generó un secreto temporal. Las sesiones se perderán al reiniciar.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Issued issue(long userId, int tokenVersion) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.jwtTtl());
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(Long.toString(userId))
                .claim("ver", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new Issued(token, exp);
    }

    public Optional<Parsed> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            Number ver = c.get("ver", Number.class);
            return Optional.of(new Parsed(Long.parseLong(c.getSubject()), ver == null ? 0 : ver.intValue()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
