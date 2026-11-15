package mx.salter.api.route;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.common.Timestamped;
import mx.salter.api.user.User;

/**
 * Ruta de EMBARQUE: agrupa las guías en manifiestos de salida. No confundir con la ruta y la subruta
 * del destinatario (listado de clientes del remitente, ej. ruta 74 / subruta CVA-01).
 */
@Entity
@Table(name = "shipping_routes")
@Getter
@Setter
public class ShippingRoute extends Timestamped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsible_user_id")
    private User responsible;

    private String notes;

    @Version
    private long version;

    public String label() {
        return code + " · " + name;
    }
}
