package mx.salter.api.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import mx.salter.api.common.Timestamped;
import mx.salter.api.operation.Courier;
import mx.salter.api.sender.Sender;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends Timestamped {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String phone;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id")
    private Role role;

    /** Remitente empresarial asignado: por defecto (capturista) o alcance exclusivo (remitente). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private Sender sender;

    /** Repartidor vinculado a la cuenta. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courier_id")
    private Courier courier;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_permission_grants", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission")
    private Set<String> grantedPermissions = new HashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_permission_revokes", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "permission")
    private Set<String> revokedPermissions = new HashSet<>();

    @Version
    private long version;

    /** Permisos efectivos = rol + concedidos − retirados. */
    public Set<String> effectivePermissions() {
        Set<String> result = new HashSet<>(role.getPermissions());
        result.addAll(grantedPermissions);
        result.removeAll(revokedPermissions);
        return result;
    }
}
