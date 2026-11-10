package mx.salter.api.sender;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SenderRepository extends JpaRepository<Sender, Long> {
    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    @Query("""
            select s from Sender s
            where (:q is null or lower(s.name) like :q or lower(s.code) like :q)
              and (:active is null or s.active = :active)
            """)
    Page<Sender> search(@Param("q") String q, @Param("active") Boolean active, Pageable pageable);
}
