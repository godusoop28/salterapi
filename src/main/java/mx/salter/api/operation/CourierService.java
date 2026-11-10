package mx.salter.api.operation;

import java.util.List;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.Text;
import mx.salter.api.operation.OperationDtos.CourierDto;
import mx.salter.api.operation.OperationDtos.CourierRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourierService {
    private final CourierRepository repo;
    private final AssignmentRepository assignments;
    private final jakarta.persistence.EntityManager em;
    private final AuditService audit;

    public CourierService(CourierRepository repo, AssignmentRepository assignments, jakarta.persistence.EntityManager em, AuditService audit) {
        this.repo = repo;
        this.assignments = assignments;
        this.em = em;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CourierDto> list(Boolean onlyActive) {
        List<Courier> list = Boolean.TRUE.equals(onlyActive) ? repo.findByActiveTrueOrderByNameAsc() : repo.findAllByOrderByNameAsc();
        Map<Long, Long> counts = new java.util.HashMap<>();
        Map<Long, Long> users = new java.util.HashMap<>();
        em.createQuery("select a.courier.id, count(a) from CourierAssignment a where a.active = true group by a.courier.id", Object[].class)
                .getResultList().forEach(r -> counts.put((Long) r[0], (Long) r[1]));
        em.createQuery("select u.courier.id, u.id from User u where u.courier is not null", Object[].class)
                .getResultList().forEach(r -> users.put((Long) r[0], (Long) r[1]));
        return list.stream().map(c -> toDto(c, counts.getOrDefault(c.getId(), 0L), users.get(c.getId()))).toList();
    }

    @Transactional
    public CourierDto create(CourierRequest req) {
        Courier c = new Courier();
        apply(c, req);
        repo.save(c);
        audit.record("COURIER_CREATED", "COURIER", c.getId(), Map.of("name", c.getName()));
        return toDto(c, 0, null);
    }

    @Transactional
    public CourierDto update(Long id, CourierRequest req) {
        Courier c = repo.findById(id).orElseThrow(() -> ApiException.notFound("Repartidor"));
        if (req.version() != null && req.version() != c.getVersion()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "El repartidor fue modificado por otra persona. Recarga la información.");
        }
        apply(c, req);
        if (req.active() != null) c.setActive(req.active());
        audit.record("COURIER_UPDATED", "COURIER", c.getId(), Map.of("name", c.getName(), "active", c.isActive()));
        repo.flush();
        return toDto(c, assignments.countActiveByCourier(c.getId()), null);
    }

    private static void apply(Courier c, CourierRequest req) {
        c.setName(req.name().trim());
        c.setPhone(Text.trimToNull(req.phone()));
        c.setEmail(Text.trimToNull(req.email()));
        c.setZone(Text.trimToNull(req.zone()));
        c.setVehicle(Text.trimToNull(req.vehicle()));
        c.setPlate(Text.trimToNull(req.plate()));
        if (req.status() != null) c.setStatus(req.status());
    }

    static CourierDto toDto(Courier c, long active, Long userId) {
        return new CourierDto(c.getId(), c.getName(), c.getPhone(), c.getEmail(), c.getZone(), c.getVehicle(), c.getPlate(),
                c.getStatus(), c.isActive(), active, userId, c.getVersion());
    }
}
