package mx.salter.api.operation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceptionRepository extends JpaRepository<Reception, Long> {
    Optional<Reception> findByGuideId(Long guideId);

    boolean existsByGuideId(Long guideId);
}
