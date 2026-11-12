package mx.salter.api.recipient;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mx.salter.api.audit.AuditService;
import mx.salter.api.common.AddressDto;
import mx.salter.api.common.ApiException;
import mx.salter.api.common.PageResponse;
import mx.salter.api.common.Paging;
import mx.salter.api.common.Text;
import mx.salter.api.recipient.DuplicateDetector.Candidate;
import mx.salter.api.recipient.DuplicateDetector.Level;
import mx.salter.api.recipient.DuplicateDetector.Match;
import mx.salter.api.recipient.RecipientDtos.DuplicateCheckRequest;
import mx.salter.api.recipient.RecipientDtos.DuplicateCheckResponse;
import mx.salter.api.recipient.RecipientDtos.RecipientDto;
import mx.salter.api.recipient.RecipientDtos.RecipientRequest;
import mx.salter.api.security.CurrentUser;
import mx.salter.api.security.SalterPrincipal;
import mx.salter.api.sender.Sender;
import mx.salter.api.sender.SenderService;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipientService {
    private final RecipientRepository repo;
    private final SenderService senders;
    private final AuditService audit;

    public RecipientService(RecipientRepository repo, SenderService senders, AuditService audit) {
        this.repo = repo;
        this.senders = senders;
        this.audit = audit;
    }

    /**
     * Búsqueda tolerante: ignora mayúsculas y acentos, coincide por partes del nombre,
     * apellido, teléfono o dirección. Cada palabra debe aparecer.
     */
    @Transactional(readOnly = true)
    public PageResponse<RecipientDto> search(Long senderId, String q, Boolean active, Integer page, Integer size) {
        SalterPrincipal p = CurrentUser.get();
        Long scopedSender = p.restrictedToSender() ? senders.requireAccessible(senderId).getId() : senderId;
        Specification<Recipient> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (scopedSender != null) ps.add(cb.equal(root.get("sender").get("id"), scopedSender));
            ps.add(cb.equal(root.get("active"), active == null || active));
            if (q != null && !q.isBlank()) {
                for (String token : Text.normalize(q).split(" ")) {
                    if (token.isBlank()) continue;
                    String digits = token.replaceAll("\\D", "");
                    String like = Text.likePattern(token);
                    if (digits.length() >= 4 && digits.length() == token.length()) {
                        ps.add(cb.or(cb.like(root.get("searchText"), like, '\\'),
                                cb.like(root.get("phoneDigits"), "%" + digits + "%")));
                    } else {
                        ps.add(cb.like(root.get("searchText"), like, '\\'));
                    }
                }
            }
            if (query != null && Long.class != query.getResultType()) root.fetch("sender");
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return PageResponse.of(repo.findAll(spec, Paging.of(page, size, Sort.by("firstName", "lastName"))), RecipientDto::of);
    }

    @Transactional(readOnly = true)
    public RecipientDto get(Long id) {
        return RecipientDto.of(requireAccessible(id));
    }

    @Transactional(readOnly = true)
    public Recipient requireAccessible(Long id) {
        Recipient r = repo.findById(id).orElseThrow(() -> ApiException.notFound("Destinatario"));
        SalterPrincipal p = CurrentUser.get();
        if (p.restrictedToSender() && !r.getSender().getId().equals(p.senderId())) {
            throw ApiException.forbidden("No tienes acceso a ese destinatario.");
        }
        return r;
    }

    @Transactional(readOnly = true)
    public DuplicateCheckResponse checkDuplicates(DuplicateCheckRequest req) {
        Sender s = senders.requireAccessible(req.senderId());
        Candidate c = Candidate.of(req.firstName(), req.lastName(), req.phone(), req.street(), req.zip());
        List<Match> m = DuplicateDetector.matches(c, repo.findCandidates(s.getId(), c.nameKey(), c.phoneDigits())).stream()
                .filter(x -> !x.recipientId().equals(req.excludeId())).toList();
        return new DuplicateCheckResponse(m.stream().anyMatch(x -> x.level() == Level.EXACT), m);
    }

    @Transactional
    public RecipientDto create(RecipientRequest req) {
        Sender s = senders.requireAccessible(req.senderId());
        Recipient r = new Recipient();
        r.setSender(s);
        apply(r, req);
        guardDuplicates(s.getId(), r, null, req.allowProbableDuplicate());
        r.setCreatedBy(CurrentUser.get().userId());
        repo.save(r);
        audit.record("RECIPIENT_CREATED", "RECIPIENT", r.getId(), Map.of("senderId", s.getId(), "name", r.fullName()));
        return RecipientDto.of(r);
    }

    @Transactional
    public RecipientDto update(Long id, RecipientRequest req) {
        Recipient r = requireAccessible(id);
        if (req.version() != null && req.version() != r.getVersion()) {
            throw ApiException.conflict("CONCURRENT_MODIFICATION", "El destinatario fue modificado por otra persona. Recarga la información.");
        }
        apply(r, req);
        guardDuplicates(r.getSender().getId(), r, r.getId(), req.allowProbableDuplicate());
        audit.record("RECIPIENT_UPDATED", "RECIPIENT", r.getId(), Map.of("name", r.fullName()));
        repo.flush();
        return RecipientDto.of(r);
    }

    /** Baja lógica: las guías emitidas conservan su copia de datos. */
    @Transactional
    public void deactivate(Long id) {
        Recipient r = requireAccessible(id);
        r.setActive(false);
        audit.record("RECIPIENT_DEACTIVATED", "RECIPIENT", r.getId(), null);
    }

    private void guardDuplicates(Long senderId, Recipient r, Long excludeId, boolean allowProbable) {
        Candidate c = Candidate.of(r.getFirstName(), r.getLastName(), r.getPhone(),
                r.getAddress() == null ? null : r.getAddress().getStreet(), r.getAddress() == null ? null : r.getAddress().getZip());
        List<Match> m = DuplicateDetector.matches(c, repo.findCandidates(senderId, c.nameKey(), c.phoneDigits())).stream()
                .filter(x -> !x.recipientId().equals(excludeId)).toList();
        if (m.stream().anyMatch(x -> x.level() == Level.EXACT)) {
            throw new DuplicateRecipientException(true, m);
        }
        if (!m.isEmpty() && !allowProbable) {
            throw new DuplicateRecipientException(false, m);
        }
    }

    static void apply(Recipient r, RecipientRequest req) {
        r.setFirstName(req.firstName().trim());
        r.setLastName(Text.trimToNull(req.lastName()));
        r.setPhone(Text.trimToNull(req.phone()));
        r.setEmail(Text.trimToNull(req.email()));
        r.setAddress((req.address() == null ? new AddressDto(null, null, null, null, null, null) : req.address()).toEntity());
        r.setNotes(Text.trimToNull(req.notes()));
        r.setClientCode(Text.trimToNull(req.clientCode()));
        r.setRoute(Text.trimToNull(req.route()));
        r.setSubRoute(RecipientDtos.normalizeSubRoute(req.subRoute()));
    }

    /** 409 con la lista de coincidencias para que la interfaz las muestre. */
    public static class DuplicateRecipientException extends ApiException {
        public DuplicateRecipientException(boolean exact, List<Match> matches) {
            super(HttpStatus.CONFLICT, exact ? "RECIPIENT_DUPLICATE" : "RECIPIENT_PROBABLE_DUPLICATE",
                    exact ? "Ya existe un destinatario con los mismos datos."
                            : "Existen destinatarios parecidos. Revisa las coincidencias o confirma que es una persona distinta.");
            with("matches", matches);
        }
    }
}
