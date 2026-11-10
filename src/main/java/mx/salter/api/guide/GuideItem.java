package mx.salter.api.guide;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** Concepto de mercancía incluido en una guía (copia del texto del catálogo). */
@Entity
@Table(name = "guide_items")
@Getter
@Setter
public class GuideItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guide_id")
    private Guide guide;

    @Column(nullable = false)
    private int position;

    @Column(name = "concept_id")
    private Long conceptId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(nullable = false)
    private String unit;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;
}
