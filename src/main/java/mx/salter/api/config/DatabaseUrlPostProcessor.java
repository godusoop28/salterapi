package mx.salter.api.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Acepta DATABASE_URL en formato de proveedor (postgres://usuario:contraseña@host:puerto/base), como la que
 * Render entrega al vincular la base, además del formato JDBC. Convierte la URL a datasource de Spring.
 */
public class DatabaseUrlPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String raw = env.getProperty("DATABASE_URL");
        if (raw == null || !(raw.startsWith("postgres://") || raw.startsWith("postgresql://"))) return;
        Map<String, Object> props = new HashMap<>(convert(raw));
        env.getPropertySources().addFirst(new MapPropertySource("salterDatabaseUrl", props));
    }

    static Map<String, String> convert(String raw) {
        URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "http://"));
        Map<String, String> out = new HashMap<>();
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        out.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + query);
        String info = uri.getRawUserInfo();
        if (info != null) {
            String[] parts = info.split(":", 2);
            out.put("spring.datasource.username", URLDecoder.decode(parts[0], StandardCharsets.UTF_8));
            if (parts.length > 1) out.put("spring.datasource.password", URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return out;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
