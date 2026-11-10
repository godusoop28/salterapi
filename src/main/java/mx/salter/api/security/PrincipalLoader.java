package mx.salter.api.security;

import java.util.Optional;
import java.util.Set;
import mx.salter.api.user.User;
import mx.salter.api.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PrincipalLoader {
    private final UserRepository users;

    public PrincipalLoader(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Optional<SalterPrincipal> load(long userId, int tokenVersion) {
        return users.findForPrincipal(userId)
                .filter(User::isActive)
                .filter(u -> u.getTokenVersion() == tokenVersion)
                .map(PrincipalLoader::toPrincipal);
    }

    public static SalterPrincipal toPrincipal(User u) {
        return new SalterPrincipal(u.getId(), u.getEmail(), u.getFullName(), u.getRole().getCode(),
                Set.copyOf(u.effectivePermissions()),
                u.getSender() == null ? null : u.getSender().getId(),
                u.getCourier() == null ? null : u.getCourier().getId());
    }
}
