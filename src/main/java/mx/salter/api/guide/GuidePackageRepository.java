package mx.salter.api.guide;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuidePackageRepository extends JpaRepository<GuidePackage, Long> {
    List<GuidePackage> findByGuideIdOrderByIdAsc(Long guideId);

    List<GuidePackage> findByGuideIdIn(Collection<Long> guideIds);

    boolean existsByGuideId(Long guideId);
}
