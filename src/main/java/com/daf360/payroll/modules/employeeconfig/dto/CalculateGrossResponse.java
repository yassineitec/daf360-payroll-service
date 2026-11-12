package com.daf360.payroll.modules.employeeconfig.dto;

import java.math.BigDecimal;

/**
 * @param grossSalary brut trouvé, dans la devise de saisie (celle du champ « Salaire brut »)
 * @param netInHand   net obtenu avec ce brut, dans la devise de travail — même convention que
 *                    {@link CalculateNetResponse#netInHand()}
 * @param convergenceOk false si le moteur n'a pas convergé dans la tolérance du jeu de paramètres
 */
public record CalculateGrossResponse(
        BigDecimal grossSalary,
        BigDecimal netInHand,
        boolean convergenceOk
) {}
