package com.daf360.payroll.modules.simulation.repository;

import com.daf360.payroll.modules.simulation.entity.CohortDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CohortDefinitionRepository extends JpaRepository<CohortDefinition, Long> {

    List<CohortDefinition> findByPaysIdOrderByCreatedAtDesc(Long paysId);

    List<CohortDefinition> findByPaysIdAndFiscalYear(Long paysId, Integer fiscalYear);

    List<CohortDefinition> findByParameterSetId(Long parameterSetId);
}
