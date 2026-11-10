package mx.salter.api.operation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.common.Timestamped;

@Entity
@Table(name = "couriers")
@Getter
@Setter
public class Courier extends Timestamped {
    public enum Status { DISPONIBLE, EN_RUTA, DESCANSO, INACTIVO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String phone;
    private String email;
    private String zone;
    private String vehicle;
    private String plate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DISPONIBLE;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;
}
