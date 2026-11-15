package mx.salter.api;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;

/**
 * Servidor local para revisión manual de pantallas (NO es una prueba automática): levanta la API en el puerto 8080
 * con un PostgreSQL embebido y temporal (se borra al terminar). Sólo corre si se pide explícitamente:
 * {@code ./mvnw test -Dtest=ServidorLocalManual -Dsalter.manual=true}. Se detiene al borrar target/servidor-local.lock.
 */
class ServidorLocalManual {
    @Test
    @EnabledIfSystemProperty(named = "salter.manual", matches = "true")
    void levantar() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            Path lock = Path.of("target", "servidor-local.lock");
            Files.writeString(lock, "borra este archivo para detener el servidor");
            var ctx = SpringApplication.run(SalterApplication.class,
                    "--spring.datasource.url=" + pg.getJdbcUrl("postgres", "postgres"),
                    "--spring.datasource.username=postgres", "--spring.datasource.password=postgres",
                    "--salter.security.jwt-secret=secreto-local-solo-para-revision-manual-0123456789",
                    "--salter.bootstrap.admin-email=admin@local.test", "--salter.bootstrap.admin-password=AdminLocal2026",
                    "--salter.demo.enabled=true", "--server.port=8080");
            while (Files.exists(lock)) Thread.sleep(1000);
            ctx.close();
        }
    }
}
