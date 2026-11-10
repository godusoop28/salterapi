package mx.salter.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.common.Timestamped;

@Entity
@Table(name = "merchandise_concepts")
@Getter
@Setter
public class MerchandiseConcept extends Timestamped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String unit = "PIEZA";

    @Column(nullable = false)
    private boolean active = true;
}
