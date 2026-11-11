package mx.salter.api.attachment;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Archivo asociado (evidencias, firmas, documentos). Se guarda en PostgreSQL (bytea) en esta etapa;
 * la interfaz permite migrar después a un almacenamiento externo sin cambiar los endpoints.
 */
@Entity
@Table(name = "attachments")
@Getter
@Setter
public class Attachment {
    public enum OwnerType { GUIDE, INCIDENT }
    public enum Category { EVIDENCIA_ENTREGA, FIRMA, INCIDENCIA, DOCUMENTO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    /** Contenido cuando storage = DB. */
    @Basic(fetch = FetchType.LAZY)
    private byte[] data;

    /** DB (PostgreSQL) o CLOUDINARY (recurso privado). */
    @Column(nullable = false)
    private String storage = "DB";

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "storage_version")
    private Long storageVersion;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
