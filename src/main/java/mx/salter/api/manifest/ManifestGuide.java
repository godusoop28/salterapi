package mx.salter.api.manifest;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import mx.salter.api.guide.Guide;

@Entity
@Table(name = "manifest_guides")
@Getter
@Setter
@NoArgsConstructor
public class ManifestGuide {
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        @Column(name = "manifest_id")
        private Long manifestId;
        @Column(name = "guide_id")
        private Long guideId;

        public Key(Long manifestId, Long guideId) {
            this.manifestId = manifestId;
            this.guideId = guideId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(manifestId, k.manifestId) && Objects.equals(guideId, k.guideId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(manifestId, guideId);
        }
    }

    @EmbeddedId
    private Key id = new Key();

    @MapsId("manifestId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manifest_id")
    private Manifest manifest;

    @MapsId("guideId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guide_id")
    private Guide guide;

    @Column(nullable = false)
    private int position;

    /** Viaja = sí por defecto. Las que no viajan no se despachan ni se cuentan como despachadas. */
    @Column(nullable = false)
    private boolean travels = true;

    @Column(name = "travel_changed_by")
    private Long travelChangedBy;

    @Column(name = "travel_changed_at")
    private java.time.Instant travelChangedAt;

    @Column(name = "travel_reason")
    private String travelReason;

    /** false = historial: la guía estuvo en este manifiesto pero no viajó y quedó libre para otro. */
    @Column(nullable = false)
    private boolean active = true;

    public ManifestGuide(Manifest manifest, Guide guide, int position) {
        this.manifest = manifest;
        this.guide = guide;
        this.position = position;
        this.id = new Key(manifest.getId(), guide.getId());
    }
}
