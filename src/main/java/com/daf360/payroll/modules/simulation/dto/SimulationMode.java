package com.daf360.payroll.modules.simulation.dto;

/**
 * Direction of the payroll simulation.
 *
 * <ul>
 *   <li>{@code NET_TO_BRUT} — classic mode: user supplies a target net and the engine
 *       converges to the gross that yields exactly that net (Newton-Raphson / bisection).
 *       {@code inputNet} is required; {@code inputGross} is ignored.</li>
 *   <li>{@code BRUT_TO_NET} — budgeting / verification mode: user supplies the gross
 *       directly; the engine makes a single top-down pass to compute the resulting net,
 *       IRPP, charges, and loaded cost (no iteration, convergenceOk is always true).
 *       {@code inputGross} is required; {@code inputNet} is ignored.</li>
 * </ul>
 *
 * <p>When {@code mode} is omitted in a request it defaults to {@code NET_TO_BRUT} for
 * backward compatibility.</p>
 */
public enum SimulationMode {
    NET_TO_BRUT,
    BRUT_TO_NET
}
