package com.daf360.payroll.engine;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
public class IrppCalculatorService {

    private static final TypeReference<List<IrppBracketJson>> BRACKET_TYPE = new TypeReference<>() {};
    private static final int SCALE = 4;

    private final ObjectMapper objectMapper;

    public IrppCalculatorService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Deserialises the irpp_brackets JSON column and computes progressive IRPP
     * on the given taxable base.
     *
     * Expected JSON format:
     * [{"lower":0,"upper":5000,"rate":0.00},{"lower":5000,"upper":20000,"rate":0.26},...]
     * upperBound omitted (or null) means open-ended top bracket.
     */
    public BigDecimal compute(String irppBracketsJson, BigDecimal taxableBase) {
        List<IrppBracketJson> raw = deserialise(irppBracketsJson);
        List<IrppBracket> brackets = raw.stream()
                .map(b -> new IrppBracket(
                        b.lower() != null ? b.lower() : BigDecimal.ZERO,
                        b.upper(),
                        normaliseRate(b.rate())))
                .toList();

        BigDecimal total = BigDecimal.ZERO;
        for (IrppBracket bracket : brackets) {
            if (taxableBase.compareTo(bracket.lowerBound()) <= 0) break;

            BigDecimal top = bracket.upperBound() == null
                    ? taxableBase
                    : taxableBase.min(bracket.upperBound());

            BigDecimal slice = top.subtract(bracket.lowerBound())
                    .max(BigDecimal.ZERO);

            total = total.add(slice.multiply(bracket.rate()));
        }

        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Accepts both decimal (0.26) and percentage (26) notation — anything ≥ 1 is divided by 100. */
    private static BigDecimal normaliseRate(BigDecimal rate) {
        if (rate == null) return BigDecimal.ZERO;
        return rate.compareTo(BigDecimal.ONE) >= 0
                ? rate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
                : rate;
    }

    private List<IrppBracketJson> deserialise(String json) {
        try {
            return objectMapper.readValue(json, BRACKET_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid irpp_brackets JSON: " + e.getMessage(), e);
        }
    }

    private record IrppBracketJson(
            @JsonAlias("min") BigDecimal lower,
            @JsonAlias("max") BigDecimal upper,
            BigDecimal rate) {}
}
