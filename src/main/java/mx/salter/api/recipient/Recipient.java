package mx.salter.api.recipient;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.common.Address;
import mx.salter.api.common.Text;
import mx.salter.api.common.Timestamped;
import mx.salter.api.sender.Sender;

@Entity
@Table(name = "recipients")
@Getter
@Setter
public class Recipient extends Timestamped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id")
    private Sender sender;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String phone;

    @Column(name = "phone_digits")
    private String phoneDigits;

    private String email;

    @Embedded
    private Address address = new Address();

    private String notes;

    @Column(name = "search_text", nullable = false)
    private String searchText;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Version
    private long version;

    public String fullName() {
        return Text.join(firstName, lastName);
    }

    @PrePersist
    @PreUpdate
    void computeKeys() {
        this.phoneDigits = Text.digits(phone);
        this.nameKey = nameKey(firstName, lastName);
        Address a = address == null ? new Address() : address;
        this.searchText = Text.truncate(Text.normalize(Text.join(firstName, lastName, phone, phoneDigits, email,
                a.getStreet(), a.getNeighborhood(), a.getCity(), a.getZip())), 600);
    }

    public static String nameKey(String first, String last) {
        return Text.truncate(Text.normalize(Text.join(first, last)), 300);
    }
}
