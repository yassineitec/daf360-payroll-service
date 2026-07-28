package com.daf360.payroll.modules.payroll.orchestrator;

import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates that the rubrique sequence (ordered by displayOrder) contains no forward
 * references: each rubrique's assietteCode must refer to an already-resolved variable
 * (a system variable or a rubrique with a lower displayOrder).
 */
@Service
public class SequenceValidator {

    /** System variable names that are always available in context at run start. */
    private static final Set<String> SYSTEM_VARS = Set.of(
        "JOURS_OUVRES", "MOIS", "ANNEE",
        "STRATE_1", "STRATE_2", "STRATE_3", "STRATE_4", "STRATE_5"
    );

    public void validate(List<PayrollRubriqueDef> rubriques) {
        Set<String> resolved = new HashSet<>(SYSTEM_VARS);

        List<PayrollRubriqueDef> ordered = rubriques.stream()
            .sorted(Comparator.comparingInt(PayrollRubriqueDef::getDisplayOrder))
            .collect(Collectors.toList());

        for (PayrollRubriqueDef r : ordered) {
            if (r.getAssietteCode() != null && !resolved.contains(r.getAssietteCode())) {
                throw new IllegalStateException(
                    "Rubrique " + r.getCode()
                        + " references unknown/forward variable: " + r.getAssietteCode());
            }
            resolved.add(r.getCode());
        }
    }
}
