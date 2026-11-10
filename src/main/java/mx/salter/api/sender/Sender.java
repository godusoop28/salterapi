package mx.salter.api.sender;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.common.Address;
import mx.salter.api.common.Timestamped;

@Entity
@Table(name = "senders")
@Getter
@Setter
public class Sender extends Timestamped {
    public enum Kind { EMPRESA, PARTICULAR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.EMPRESA;

    @Column(name = "contact_name")
    private String contactName;

    private String phone;
    private String email;

    @Embedded
    private Address address = new Address();

    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private long version;
}
