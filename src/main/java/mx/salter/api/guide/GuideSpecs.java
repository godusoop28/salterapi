package mx.salter.api.guide;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import mx.salter.api.common.Clocks;
import mx.salter.api.common.Text;
import mx.salter.api.guide.GuideDtos.GuideFilter;
import mx.salter.api.manifest.ManifestGuide;
import mx.salter.api.operation.CourierAssignment;
import mx.salter.api.security.SalterPrincipal;
import org.springframework.data.jpa.domain.Specification;

public final class GuideSpecs {
    private GuideSpecs() {}

    public static Specification<Guide> filter(GuideFilter f, ZoneId zone, SalterPrincipal p) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f == null) return cb.conjunction();
            if (f.status() != null && !f.status().isEmpty()) ps.add(root.get("status").in(f.status()));
            if (f.senderId() != null) ps.add(cb.equal(root.get("sender").get("id"), f.senderId()));
            if (f.shiftId() != null) ps.add(cb.equal(root.get("shift").get("id"), f.shiftId()));
            if (Boolean.TRUE.equals(f.mine()) && p != null) ps.add(cb.equal(root.get("createdBy").get("id"), p.userId()));
            if (f.from() != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), Clocks.startOfDay(f.from(), zone)));
            if (f.to() != null) ps.add(cb.lessThan(root.get("createdAt"), Clocks.endOfDayExclusive(f.to(), zone)));
            if (f.folio() != null && !f.folio().isBlank()) ps.add(cb.equal(cb.upper(root.get("folio")), f.folio().trim().toUpperCase()));
            if (f.q() != null && !f.q().isBlank()) {
                for (String token : Text.normalize(f.q()).split(" ")) {
                    if (!token.isBlank()) ps.add(cb.like(root.get("searchText"), Text.likePattern(token), '\\'));
                }
            }
            if (f.courierId() != null) {
                Subquery<Long> sq = query.subquery(Long.class);
                var a = sq.from(CourierAssignment.class);
                sq.select(a.get("id")).where(cb.equal(a.get("guide"), root), cb.equal(a.get("courier").get("id"), f.courierId()),
                        cb.isTrue(a.get("active")));
                ps.add(cb.exists(sq));
            }
            if (Boolean.TRUE.equals(f.withoutManifest())) {
                Subquery<Long> sq = query.subquery(Long.class);
                var mg = sq.from(ManifestGuide.class);
                sq.select(mg.get("guide").get("id")).where(cb.equal(mg.get("guide"), root));
                ps.add(cb.not(cb.exists(sq)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }
}
