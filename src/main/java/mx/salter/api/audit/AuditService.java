package mx.salter.api.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.SalterPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Registro de acciones sensibles. Se escribe en la misma transacción que la operación
 * (si la operación falla, no queda un registro falso). Los intentos de acceso fallidos se
 * registran en una transacción independiente.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger("AUDIT");
    private final AuditRepository repo;
    private final Clock clock;

    public AuditService(AuditRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, Map<String, Object> details) {
        SalterPrincipal p = CurrentUser.orNull();
        save(action, entityType, entityId, details, p == null ? null : p.userId(), p == null ? null : p.email());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(String action, String entityType, Object entityId, Map<String, Object> details,
                                  Long userId, String email) {
        save(action, entityType, entityId, details, userId, email);
    }

    private void save(String action, String entityType, Object entityId, Map<String, Object> details, Long userId, String email) {
        AuditLog a = new AuditLog();
        a.setOccurredAt(clock.instant());
        a.setAction(action);
        a.setEntityType(entityType);
        a.setEntityId(entityId == null ? null : String.valueOf(entityId));
        a.setUserId(userId);
        a.setUserEmail(email);
        a.setIp(clientIp());
        a.setDetails(details == null ? null : new LinkedHashMap<>(details));
        repo.save(a);
        log.info("action={} entity={}:{} user={}", action, entityType, entityId, userId);
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            return req.getRemoteAddr();
        }
        return null;
    }
}
