package mx.salter.api.sender;

import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.common.AddressDto;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PageResponse;
import mx.salter.api.common.Paging;
import mx.salter.api.common.Text;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.SalterPrincipal;
import mx.salter.api.sender.SenderDtos.SenderDto;
import mx.salter.api.sender.SenderDtos.SenderRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SenderService {
    private final SenderRepository repo;
    private final AuditService audit;

    public SenderService(SenderRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** Remitente al que el usuario tiene acceso; valida el alcance de usuarios restringidos. */
    @Transactional(readOnly = true)
    public Sender requireAccessible(Long senderId) {
        SalterPrincipal p = CurrentUser.get();
        Long effective = senderId;
        if (p.restrictedToSender()) {
            if (p.senderId() == null) {
                throw ApiException.forbidden("Tu usuario no tiene un remitente empresarial asignado. Contacta a administración.");
            }
            if (effective != null && !effective.equals(p.senderId())) {
                throw ApiException.forbidden("No tienes acceso a ese remitente.");
            }
            effective = p.senderId();
        }
        if (effective == null) throw ApiException.badRequest("SENDER_REQUIRED", "Selecciona un remitente.");
        Sender s = repo.findById(effective).orElseThrow(() -> ApiException.notFound("Remitente"));
        if (!s.isActive()) throw ApiException.business("SENDER_INACTIVE", "El remitente está inactivo.");
        return s;
    }

    @Transactional(readOnly = true)
    public PageResponse<SenderDto> search(String q, Boolean active, Integer page, Integer size) {
        SalterPrincipal p = CurrentUser.get();
        if (p.restrictedToSender()) {
            if (p.senderId() == null) return new PageResponse<>(java.util.List.of(), 0, 0, 0, 0);
            Sender own = repo.findById(p.senderId()).orElseThrow(() -> ApiException.notFound("Remitente"));
            return PageResponse.of(new PageImpl<>(java.util.List.of(own), PageRequest.of(0, 1), 1), SenderDto::of);
        }
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return PageResponse.of(repo.search(like, active, Paging.of(page, size, Sort.by("name"))), SenderDto::of);
    }

    @Transactional(readOnly = true)
    public SenderDto get(Long id) {
        SalterPrincipal p = CurrentUser.get();
        if (p.restrictedToSender() && !id.equals(p.senderId())) throw ApiException.forbidden("No tienes acceso a ese remitente.");
        return SenderDto.of(repo.findById(id).orElseThrow(() -> ApiException.notFound("Remitente")));
    }

    @Transactional
    public SenderDto create(SenderRequest req) {
        Sender s = new Sender();
        String code = Text.trimToNull(req.code());
        if (code == null) code = nextCode();
        if (repo.existsByCodeIgnoreCase(code)) throw ApiException.conflict("CODE_TAKEN", "Ya existe un remitente con esa clave.");
        s.setCode(code.toUpperCase());
        apply(s, req);
        repo.save(s);
        audit.record("SENDER_CREATED", "SENDER", s.getId(), Map.of("name", s.getName()));
        return SenderDto.of(s);
    }

    @Transactional
    public SenderDto update(Long id, SenderRequest req) {
        Sender s = repo.findById(id).orElseThrow(() -> ApiException.notFound("Remitente"));
        if (req.version() != null && req.version() != s.getVersion()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "El remitente fue modificado por otra persona. Recarga la información.");
        }
        String code = Text.trimToNull(req.code());
        if (code != null && !code.equalsIgnoreCase(s.getCode())) {
            if (repo.existsByCodeIgnoreCaseAndIdNot(code, id)) throw ApiException.conflict("CODE_TAKEN", "Ya existe un remitente con esa clave.");
            s.setCode(code.toUpperCase());
        }
        apply(s, req);
        if (req.active() != null) s.setActive(req.active());
        audit.record("SENDER_UPDATED", "SENDER", s.getId(), Map.of("name", s.getName(), "active", s.isActive()));
        repo.flush();
        return SenderDto.of(s);
    }

    private void apply(Sender s, SenderRequest req) {
        s.setName(req.name().trim());
        s.setKind(req.kind());
        s.setContactName(Text.trimToNull(req.contactName()));
        s.setPhone(Text.trimToNull(req.phone()));
        s.setEmail(Text.trimToNull(req.email()));
        s.setAddress((req.address() == null ? new AddressDto(null, null, null, null, null, null) : req.address()).toEntity());
        s.setNotes(Text.trimToNull(req.notes()));
    }

    private String nextCode() {
        long n = repo.count() + 1;
        String code;
        do {
            code = String.format("REM-%04d", n++);
        } while (repo.existsByCodeIgnoreCase(code));
        return code;
    }
}
