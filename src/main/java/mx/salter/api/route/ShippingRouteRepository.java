package mx.salter.api.route;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShippingRouteRepository extends JpaRepository<ShippingRoute, Long> {
    @Query("select r from ShippingRoute r left join fetch r.responsible where (:active is null or r.active = :active) order by r.code")
    List<ShippingRoute> list(@Param("active") Boolean active);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    /** Serializa la búsqueda/creación del manifiesto en preparación de una ruta. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ShippingRoute r where r.id = :id")
    Optional<ShippingRoute> lockById(@Param("id") Long id);
}
