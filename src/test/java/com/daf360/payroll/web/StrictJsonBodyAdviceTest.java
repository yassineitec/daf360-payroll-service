package com.daf360.payroll.web;

import com.daf360.payroll.modules.simulation.dto.CohortFilterRequest;
import com.daf360.payroll.modules.simulation.dto.SimulationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A4 — unknown fields on a @StrictJson body are a 400, the old shape still passes. */
class StrictJsonBodyAdviceTest {

    private final StrictJsonBodyAdvice advice = new StrictJsonBodyAdvice(new ObjectMapper());

    private void check(String json) {
        advice.checkFields(json.getBytes(StandardCharsets.UTF_8), CohortFilterRequest.class);
    }

    @Test
    void unknownField_rejected() {
        assertThatThrownBy(() -> check("{\"paysId\":179,\"entity\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entity");
    }

    @Test
    void oldShape_withEntiteNull_accepted() {
        assertThatCode(() -> check("{\"paysId\":179,\"grade\":null,\"discipline\":null,\"contractType\":null,"
                + "\"entite\":null,\"modifierType\":\"PCT\",\"modifierValue\":5}")).doesNotThrowAnyException();
    }

    @Test
    void onlyStrictRecordsAreSupported() {
        assertThat(advice.supports(null, CohortFilterRequest.class, null)).isTrue();
        assertThat(advice.supports(null, SimulationRequest.class, null)).isFalse();
    }
}
