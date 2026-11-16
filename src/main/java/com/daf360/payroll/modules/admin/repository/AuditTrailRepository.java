package com.daf360.payroll.modules.admin.repository;

import com.daf360.payroll.modules.admin.entity.AuditTrail;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditTrailRepository extends JpaRepository<AuditTrail, Long> {

    /**
     * Entries whose captured row JSON has a pays_id matching the caller's country.
     * Tables with no pays_id column never match -- see the service's javadoc.
     *
     * Returns {@code List}, not {@code Page}: a {@code Page} return type makes Spring Data
     * derive a count query by keeping this query's trailing {@code ORDER BY} clause, which
     * SQL Server rejects (Msg 8127 -- a column in ORDER BY must be aggregated or grouped when
     * the query has no GROUP BY). Matches the sibling audit repositories, which never needed
     * a total count either.
     */
    @Query(value = """
            SELECT * FROM dbo.audit_trail
            WHERE TRY_CAST(JSON_VALUE(COALESCE(new_value, old_value), '$.pays_id') AS BIGINT) = :paysId
            ORDER BY changed_at DESC
            """, nativeQuery = true)
    List<AuditTrail> findByPaysId(@Param("paysId") Long paysId, Pageable pageable);
}
