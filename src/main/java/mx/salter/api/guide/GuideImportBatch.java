package mx.salter.api.guide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** Archivo de carga importado (un lote por remitente y archivo/carga): permite comparar cantidades y evitar duplicados. */
@Entity
@Table(name = "guide_import_batches")
@Getter
@Setter
public class GuideImportBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "file_key", nullable = false)
    private String fileKey;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "load_code")
    private String loadCode;

    @Column(name = "declared_packages")
    private Integer declaredPackages;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
