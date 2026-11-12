package mx.salter.api.guide;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.catalog.Shift;
import mx.salter.api.common.Text;
import mx.salter.api.common.Timestamped;
import mx.salter.api.recipient.Recipient;
import mx.salter.api.sender.Sender;
import mx.salter.api.user.User;

/**
 * Guía digital. Guarda una COPIA HISTÓRICA del remitente y del destinatario: los cambios
 * posteriores en los catálogos no alteran documentos ya emitidos.
 */
@Entity
@Table(name = "guides")
@Getter
@Setter
public class Guide extends Timestamped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String folio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuideStatus status = GuideStatus.BORRADOR;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_hold")
    private GuideStatus statusBeforeHold;

    @Column(name = "client_request_id")
    private UUID clientRequestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id")
    private Sender sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
    private Recipient recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private User createdBy;

    // Copia del remitente
    @Column(name = "sender_name", nullable = false) private String senderName;
    @Column(name = "sender_contact") private String senderContact;
    @Column(name = "sender_phone") private String senderPhone;
    @Column(name = "sender_street") private String senderStreet;
    @Column(name = "sender_neighborhood") private String senderNeighborhood;
    @Column(name = "sender_city") private String senderCity;
    @Column(name = "sender_state") private String senderState;
    @Column(name = "sender_zip") private String senderZip;

    // Copia del destinatario
    @Column(name = "rcpt_name", nullable = false) private String rcptName;
    @Column(name = "rcpt_phone") private String rcptPhone;
    @Column(name = "rcpt_email") private String rcptEmail;
    @Column(name = "rcpt_street") private String rcptStreet;
    @Column(name = "rcpt_neighborhood") private String rcptNeighborhood;
    @Column(name = "rcpt_city") private String rcptCity;
    @Column(name = "rcpt_state") private String rcptState;
    @Column(name = "rcpt_zip") private String rcptZip;
    @Column(name = "rcpt_refs") private String rcptRefs;
    @Column(name = "rcpt_one_time", nullable = false) private boolean rcptOneTime;
    @Column(name = "rcpt_client_code") private String rcptClientCode;

    @Column(name = "cd_reference") private String cdReference;
    @Column(name = "order_reference") private String orderReference;
    @Column(name = "package_count", nullable = false) private int packageCount = 1;
    @Column(name = "total_weight_kg") private BigDecimal totalWeightKg;
    // Formato oficial de guía (boceto del cliente)
    @Column(name = "origin_code") private String originCode;
    @Column(name = "destination_code") private String destinationCode;
    private String dimensions;
    @Column(name = "service_type", nullable = false) private String serviceType = "ORDINARIO";
    @Column(name = "commitment_date") private LocalDate commitmentDate;
    private String notes;

    @Column(name = "search_text", nullable = false)
    private String searchText = "";

    @Column(name = "confirmed_at") private Instant confirmedAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "confirmed_by") private User confirmedBy;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "cancelled_by") private User cancelledBy;
    @Column(name = "cancel_reason") private String cancelReason;

    @OneToMany(mappedBy = "guide", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<GuideItem> items = new ArrayList<>();

    @Version
    private long version;

    public void replaceItems(List<GuideItem> newItems) {
        items.clear();
        int pos = 1;
        for (GuideItem i : newItems) {
            i.setGuide(this);
            i.setPosition(pos++);
            items.add(i);
        }
        BigDecimal total = null;
        for (GuideItem i : items) {
            if (i.getWeightKg() != null) total = (total == null ? BigDecimal.ZERO : total).add(i.getWeightKg());
        }
        totalWeightKg = total;
    }

    @PrePersist
    @PreUpdate
    void computeSearch() {
        searchText = Text.truncate(Text.normalize(Text.join(folio, rcptClientCode, rcptName, originCode, destinationCode, rcptPhone, rcptCity, rcptNeighborhood,
                senderName, cdReference, orderReference)), 1200);
    }

    /** Referencia visible: folio si ya fue confirmada; si no, número interno de borrador. */
    public String displayReference() {
        return folio != null ? folio : "BORRADOR-" + id;
    }
}
