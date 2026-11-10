package mx.salter.api.operation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {
    Optional<Delivery> findByGuideId(Long guideId);

    boolean existsByGuideId(Long guideId);
}
