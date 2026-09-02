package com.daf360.payroll.modules.ref.repository;

import com.daf360.payroll.modules.ref.entity.UsersRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsersRefRepository extends JpaRepository<UsersRef, Long> {

    Optional<UsersRef> findByEmail(String email);

    Optional<UsersRef> findByAzureOid(String azureOid);

    List<UsersRef> findByPaysId(Long paysId);

    /*
     * The picker queries. The is_employee predicate lives in SQL, not in a .filter() on the
     * result, so it shows up verbatim in the Hibernate DEBUG line: when a ghost account turns
     * up in a dropdown, one log entry distinguishes "the filter did not run" from "the replica
     * was stale" from "the page had not re-fetched". A stream filter leaves no such trace.
     */
    List<UsersRef> findByIsEmployeeTrueOrderByFullNameAsc();

    List<UsersRef> findByPaysIdAndIsEmployeeTrueOrderByFullNameAsc(Long paysId);
}
