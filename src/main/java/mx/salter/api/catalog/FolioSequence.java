package mx.salter.api.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "folio_sequences")
@Getter
@Setter
public class FolioSequence {
    @Id
    private String key;

    @Column(nullable = false)
    private String prefix;

    @Column(nullable = false)
    private int padding;

    @Column(name = "next_value", nullable = false)
    private long nextValue;
}
