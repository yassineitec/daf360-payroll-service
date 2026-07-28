package com.daf360.payroll.modules.payroll.repository;

import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PayrollRubriqueDefRepository extends JpaRepository<PayrollRubriqueDef, Long> {

    List<PayrollRubriqueDef> findByCountryIdAndActiveTrue(Long countryId);

    Optional<PayrollRubriqueDef> findByCountryIdAndCode(Long countryId, String code);
}
