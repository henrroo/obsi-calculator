package com.kraken.obsi.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of one calculation, shaped exactly like the JSON the CLI prints: {@link Published} goes
 * to standard output, {@link Failed} to standard error.
 */
public sealed interface IndexResult {

    String INDEX = "OBSI";

    String index();

    String calculationTime();

    String status();

    @JsonPropertyOrder({"index", "calculationTime", "status", "value", "metadata"})
    record Published(String index, String calculationTime, String status, String value, Metadata metadata)
            implements IndexResult {
    }

    @JsonPropertyOrder({"utilizedDepth", "excludedExchanges"})
    record Metadata(String utilizedDepth, List<String> excludedExchanges) {

        public Metadata {
            excludedExchanges = List.copyOf(excludedExchanges);
        }
    }

    @JsonPropertyOrder({"index", "calculationTime", "status", "reason"})
    record Failed(String index, String calculationTime, String status, String reason) implements IndexResult {
    }

    /**
     * @param publishedValue    index value already rounded for publication; printed as is, so
     *                          {@code 100.00} stays {@code "100.00"}
     * @param utilizedDepth     utilized depth in XYZ; printed without trailing zeros
     * @param excludedExchanges screened-out exchanges in input order
     */
    static Published published(CalculationTime calculationTime, BigDecimal publishedValue, BigDecimal utilizedDepth,
                               List<String> excludedExchanges) {
        Objects.requireNonNull(calculationTime, "calculationTime");
        return new Published(
                INDEX,
                calculationTime.text(),
                "published",
                publishedValue.toPlainString(),
                new Metadata(utilizedDepth.stripTrailingZeros().toPlainString(), excludedExchanges));
    }

    /** @param calculationTime the input calculation time, or {@code null} if the input did not supply a valid one */
    static Failed failed(CalculationTime calculationTime, String reason) {
        return new Failed(INDEX, calculationTime == null ? null : calculationTime.text(), "failed", reason);
    }
}
