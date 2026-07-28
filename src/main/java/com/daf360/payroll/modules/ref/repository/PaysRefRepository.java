package com.daf360.payroll.modules.ref.repository;

import com.daf360.payroll.modules.ref.entity.PaysRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaysRefRepository extends JpaRepository<PaysRef, Long> {

    Optional<PaysRef> findByIsoCode(String isoCode);
}
