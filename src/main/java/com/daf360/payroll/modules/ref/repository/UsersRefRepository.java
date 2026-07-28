package com.daf360.payroll.modules.ref.repository;

import com.daf360.payroll.modules.ref.entity.UsersRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UsersRefRepository extends JpaRepository<UsersRef, Long> {

    Optional<UsersRef> findByEmail(String email);

    Optional<UsersRef> findByAzureOid(String azureOid);

    List<UsersRef> findByPaysId(Long paysId);
}
