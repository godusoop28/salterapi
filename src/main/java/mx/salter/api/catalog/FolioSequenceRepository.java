package mx.salter.api.catalog;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FolioSequenceRepository extends JpaRepository<FolioSequence, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FolioSequence f where f.key = :key")
    Optional<FolioSequence> lockByKey(@Param("key") String key);

    /** Crea la secuencia si no existe (seguro ante solicitudes simultáneas). */
    @Modifying
    @Query(value = "insert into folio_sequences (key, prefix, padding, next_value) values (:key, :prefix, :padding, 1) on conflict (key) do nothing",
            nativeQuery = true)
    int createIfMissing(@Param("key") String key, @Param("prefix") String prefix, @Param("padding") int padding);
}
