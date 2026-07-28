package com.daf360.payroll.modules.parameterset.repository;

import com.daf360.payroll.modules.parameterset.entity.PayrollRubrique;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PayrollRubriqueRepository extends JpaRepository<PayrollRubrique, Long> {

    List<PayrollRubrique> findByParameterSetId(Long parameterSetId);

    List<PayrollRubrique> findByParameterSetIdAndIsActiveTrue(Long parameterSetId);

    void deleteByParameterSetId(Long parameterSetId);
}
