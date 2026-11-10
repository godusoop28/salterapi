package mx.salter.api.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Clocks {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneId businessZone(@Value("${salter.zone:America/Mexico_City}") String zone) {
        return ZoneId.of(zone);
    }

    public static Instant startOfDay(LocalDate d, ZoneId zone) {
        return d.atStartOfDay(zone).toInstant();
    }

    public static Instant endOfDayExclusive(LocalDate d, ZoneId zone) {
        return d.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
