package mx.salter.api.guide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bulto original del reporte de carga: LPN (identificador del bulto), su tipo y el pedido al que pertenece.
 * Conserva la relación LPN ↔ pedido ↔ cliente que antes sólo quedaba en las notas.
 */
@Entity
@Table(name = "guide_packages")
@Getter
@Setter
@NoArgsConstructor
public class GuidePackage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "guide_id", nullable = false)
    private Long guideId;

    private String lpn;

    @Column(name = "package_type", nullable = false)
    private String packageType;

    @Column(name = "order_number")
    private String orderNumber;

    public GuidePackage(Long guideId, String lpn, String packageType, String orderNumber) {
        this.guideId = guideId;
        this.lpn = lpn;
        this.packageType = packageType;
        this.orderNumber = orderNumber;
    }
}
