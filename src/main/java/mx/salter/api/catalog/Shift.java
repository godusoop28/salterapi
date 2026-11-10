package mx.salter.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.common.Timestamped;

@Entity
@Table(name = "shifts")
@Getter
@Setter
public class Shift extends Timestamped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private boolean active = true;

    /** Considera turnos que cruzan la medianoche (p. ej. 22:00–06:00). */
    public boolean contains(LocalTime t) {
        if (startTime.isBefore(endTime)) return !t.isBefore(startTime) && t.isBefore(endTime);
        return !t.isBefore(startTime) || t.isBefore(endTime);
    }
}
