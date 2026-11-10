package mx.salter.api.security;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "salter.security")
public record SecurityProperties(
        String jwtSecret,
        Duration jwtTtl,
        String cookieName,
        boolean cookieSecure,
        String cookieSameSite,
        String corsAllowedOrigins,
        int maxFailedLogins,
        Duration lockDuration) {

    public List<String> corsOrigins() {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) return List.of();
        return Arrays.stream(corsAllowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
