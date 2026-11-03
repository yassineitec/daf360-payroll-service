package com.daf360.payroll.modules.admin.repository;

import com.daf360.payroll.modules.admin.entity.AuditTrail;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditTrailRepository extends JpaRepository<AuditTrail, Long> {

    /**
     * Entries whose captured row JSON has a pays_id matching the caller's country.
     * Tables with no pays_id column never match -- see the service's javadoc.
     */
    @Query(value = """
            SELECT * FROM dbo.audit_trail
            WHERE TRY_CAST(JSON_VALUE(COALESCE(new_value, old_value), '$.pays_id') AS BIGINT) = :paysId
            ORDER BY changed_at DESC
            """, nativeQuery = true)
    Page<AuditTrail> findByPaysId(@Param("paysId") Long paysId, Pageable pageable);
}
