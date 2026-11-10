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

/** Entrega final al destinatario. */
@Entity
@Table(name = "deliveries")
@Getter
@Setter
public class Delivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guide_id", nullable = false, unique = true)
    private Long guideId;

    @Column(name = "delivered_by", nullable = false)
    private Long deliveredBy;

    @Column(name = "courier_id")
    private Long courierId;

    @Column(name = "received_by_name", nullable = false)
    private String receivedByName;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;

    private String notes;
}
