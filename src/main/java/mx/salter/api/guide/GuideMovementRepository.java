package mx.salter.api.guide;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuideMovementRepository extends JpaRepository<GuideMovement, Long> {
    List<GuideMovement> findByGuideIdOrderByOccurredAtAscIdAsc(Long guideId);
}
