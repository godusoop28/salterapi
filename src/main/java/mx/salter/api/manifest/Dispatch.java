package mx.salter.api.manifest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Autorización de salida de un manifiesto (una sola por manifiesto). */
@Entity
@Table(name = "dispatches")
@Getter
@Setter
public class Dispatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "manifest_id", nullable = false, unique = true)
    private Long manifestId;

    @Column(name = "dispatched_by", nullable = false)
    private Long dispatchedBy;

    @Column(name = "dispatched_at", nullable = false)
    private Instant dispatchedAt;

    @Column(name = "courier_id")
    private Long courierId;

    private String vehicle;

    private String notes;

    @Column(name = "guide_count", nullable = false)
    private int guideCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
