package mx.salter.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseUrlTest {
    @Test
    void conviertePostgresUrlAJdbc() throws Exception {
        var m = mx.salter.api.config.DatabaseUrlPostProcessor.class.getDeclaredMethod("convert", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> p = (Map<String, String>) m.invoke(null, "postgresql://salter:p%40ss@dpg-x-a/salter");
        assertThat(p.get("spring.datasource.url")).isEqualTo("jdbc:postgresql://dpg-x-a:5432/salter");
        assertThat(p.get("spring.datasource.username")).isEqualTo("salter");
        assertThat(p.get("spring.datasource.password")).isEqualTo("p@ss");
    }
}
