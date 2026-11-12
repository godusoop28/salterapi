package mx.salter.api.recipient;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecipientRepository extends JpaRepository<Recipient, Long>, JpaSpecificationExecutor<Recipient> {

    @Query("""
            select r from Recipient r
            where r.sender.id = :senderId and r.active = true
              and (r.nameKey = :nameKey or (:phone is not null and r.phoneDigits = :phone))
            """)
    List<Recipient> findCandidates(@Param("senderId") Long senderId, @Param("nameKey") String nameKey, @Param("phone") String phone);

    @Query("""
            select r from Recipient r
            where r.sender.id = :senderId and r.active = true
              and (r.nameKey in :nameKeys or r.phoneDigits in :phones)
            """)
    List<Recipient> findCandidatesBulk(@Param("senderId") Long senderId, @Param("nameKeys") Collection<String> nameKeys,
                                       @Param("phones") Collection<String> phones);

    List<Recipient> findBySenderIdAndActiveTrue(Long senderId);
}
