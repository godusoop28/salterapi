package mx.salter.api.incident;

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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.common.Timestamped;
import mx.salter.api.guide.Guide;
import mx.salter.api.guide.GuideStatus;

@Entity
@Table(name = "incidents")
@Getter
@Setter
public class Incident extends Timestamped {
    public enum Type {
        DESTINATARIO_AUSENTE, DIRECCION_INCORRECTA, PAQUETE_DANADO, RECHAZADO, RETRASO, NO_LOCALIZADO,
        DIFERENCIA_RECEPCION, FALTANTE, OTRO
    }
    public enum Severity { BAJA, MEDIA, ALTA }
    public enum Status { ABIERTA, EN_REVISION, RESUELTA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String folio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guide_id")
    private Guide guide;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ABIERTA;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "guide_status_before", nullable = false)
    private GuideStatus guideStatusBefore;

    @Column(name = "reported_by", nullable = false)
    private Long reportedBy;

    @Column(name = "reported_by_name", nullable = false)
    private String reportedByName;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    private String resolution;

    @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<IncidentNote> notes = new ArrayList<>();

    @Version
    private long version;
}
