package mx.salter.api.catalog;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConceptRepository extends JpaRepository<MerchandiseConcept, Long> {
    List<MerchandiseConcept> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
