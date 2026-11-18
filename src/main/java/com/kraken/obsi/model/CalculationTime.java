package com.kraken.obsi.model;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Effective calculation time T of a snapshot.
 *
 * <p>The original text is kept alongside the parsed {@link Instant} so the output can echo the
 * input verbatim (e.g. {@code 12:00:00.000Z} is not rewritten as {@code 12:00:00Z}).
 *
 * @param text    the ISO-8601 UTC string exactly as supplied
 * @param instant the point in time it denotes
 */
public record CalculationTime(String text, Instant instant) {

    public CalculationTime {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(instant, "instant");
    }

    /**
     * Parses an ISO-8601 date-time that is explicitly in UTC ({@code Z} or a zero offset).
     *
     * @throws IllegalArgumentException if the text is not ISO-8601 or is not in UTC
     */
    public static CalculationTime parse(String text) {
        return new CalculationTime(text, parseUtc("calculationTime", text));
    }

    /**
     * Parses any input timestamp that must be an ISO-8601 date-time explicitly in UTC.
     *
     * @param field name used in the error message
     * @throws IllegalArgumentException if the text is not ISO-8601 or is not in UTC
     */
    public static Instant parseUtc(String field, String text) {
        Objects.requireNonNull(text, "text");
        final OffsetDateTime dateTime;
        try {
            dateTime = OffsetDateTime.parse(text);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(field + " is not an ISO-8601 date-time: " + text, e);
        }
        if (!ZoneOffset.UTC.equals(dateTime.getOffset())) {
            throw new IllegalArgumentException(field + " is not in UTC: " + text);
        }
        return dateTime.toInstant();
    }
}
