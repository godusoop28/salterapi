package mx.salter.api.manifest;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.catalog.Shift;
import mx.salter.api.common.Timestamped;
import mx.salter.api.sender.Sender;
import mx.salter.api.user.User;

/**
 * Manifiesto de guías por turno/lote. Elaborarlo NO significa que la mercancía salió:
 * la salida se registra aparte (Dispatch) con su propia autorización.
 */
@Entity
@Table(name = "manifests")
@Getter
@Setter
public class Manifest extends Timestamped {
    public enum Status { BORRADOR, CERRADO, DESPACHADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.BORRADOR;

    @Column(name = "operation_date", nullable = false)
    private LocalDate operationDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private Sender sender;

    private String notes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private User closedBy;

    @OneToMany(mappedBy = "manifest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ManifestGuide> guides = new ArrayList<>();

    @Version
    private long version;
}
