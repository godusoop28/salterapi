package mx.salter.api.operation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourierRepository extends JpaRepository<Courier, Long> {
    List<Courier> findAllByOrderByNameAsc();

    List<Courier> findByActiveTrueOrderByNameAsc();
}
