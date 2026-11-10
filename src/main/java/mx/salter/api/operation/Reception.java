package mx.salter.api.operation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Recepción en instalación de destino (distinta de la entrega final al destinatario). */
@Entity
@Table(name = "receptions")
@Getter
@Setter
public class Reception {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guide_id", nullable = false, unique = true)
    private Long guideId;

    @Column(name = "received_by", nullable = false)
    private Long receivedBy;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    private String location;

    @Column(name = "received_count")
    private Integer receivedCount;

    @Column(name = "has_differences", nullable = false)
    private boolean hasDifferences;

    private String differences;

    private String notes;
}
