package com.daf360.payroll.modules.payroll.repository;

import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayrollCountryRepository extends JpaRepository<PayrollCountry, Long> {

    Optional<PayrollCountry> findByPaysId(Long paysId);

    Optional<PayrollCountry> findByPaysIdAndActiveTrue(Long paysId);
}
