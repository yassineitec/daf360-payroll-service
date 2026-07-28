package com.daf360.payroll.modules.simulation.repository;

import com.daf360.payroll.modules.simulation.entity.SimulationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimulationResultRepository extends JpaRepository<SimulationResult, Long> {

    List<SimulationResult> findByPaysIdOrderBySimulatedAtDesc(Long paysId);

    List<SimulationResult> findByPaysIdAndSimulationTypeOrderBySimulatedAtDesc(Long paysId, String type);

    List<SimulationResult> findByCohortId(Long cohortId);
}
