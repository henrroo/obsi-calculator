package com.kraken.obsi.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.kraken.obsi.model.Book;
import com.kraken.obsi.model.CalculationTime;
import com.kraken.obsi.model.ExchangeBook;
import com.kraken.obsi.model.IndexResult;
import com.kraken.obsi.model.PriceLevel;
import com.kraken.obsi.model.Snapshot;
import com.kraken.obsi.model.UnreadableBook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Reads input snapshots and writes calculation results as JSON.
 *
 * <p>Parsing is deliberately two-tiered, following section 2 of the methodology:
 * <ul>
 *   <li>Malformed top-level input (invalid JSON, missing or mistyped {@code calculationTime} or
 *       {@code books}) is an input error and raises {@link SnapshotParseException}.</li>
 *   <li>Problems inside a book are not errors. An unreadable book becomes an
 *       {@link UnreadableBook} for the screener to exclude, and a price/size entry that is not
 *       a pair of JSON numbers is discarded. Non-positive values are kept for the screener.</li>
 * </ul>
 * Numbers are read as exact {@code BigDecimal}s; no value ever passes through {@code double}.
 *
 * <p>The optional top-level {@code unlockEvents} field of the {@code --new-ped} extension
 * (proposal.md) is read only by {@link #withUnlockEvents()}. The default parser ignores it like any
 * other unknown field, so baseline results never depend on it.
 */
public final class SnapshotParser {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private final boolean readUnlockEvents;

    /** Parser for the baseline methodology input. */
    public SnapshotParser() {
        this(false);
    }

    private SnapshotParser(boolean readUnlockEvents) {
        this.readUnlockEvents = readUnlockEvents;
    }

    /** Parser for the {@code --new-ped} input, which may carry {@code unlockEvents}. */
    public static SnapshotParser withUnlockEvents() {
        return new SnapshotParser(true);
    }

    public Snapshot parse(Path file) throws SnapshotParseException {
        final String json;
        try {
            json = Files.readString(file);
        } catch (IOException e) {
            throw new SnapshotParseException("cannot read input file: " + file, null, e);
        }
        return parse(json);
    }

    public Snapshot parse(String json) throws SnapshotParseException {
        final JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new SnapshotParseException("input is not valid JSON", null, e);
        }
        if (!(root instanceof ObjectNode)) {
            throw new SnapshotParseException("input must be a JSON object", null);
        }

        CalculationTime calculationTime = parseCalculationTime(root.get("calculationTime"));

        List<Book> books = switch (root.get("books")) {
            case ArrayNode array -> stream(array).map(SnapshotParser::parseBook).toList();
            case null -> throw new SnapshotParseException("missing required field: books", calculationTime);
            default -> throw new SnapshotParseException("books must be an array", calculationTime);
        };
        List<Instant> unlockEvents = readUnlockEvents
                ? parseUnlockEvents(root.get("unlockEvents"), calculationTime)
                : List.of();
        return new Snapshot(calculationTime, books, unlockEvents);
    }

    /**
     * {@code unlockEvents} is optional: absent, {@code null} or empty all mean no unlock. When it is
     * present, every element must be an ISO-8601 UTC string. A malformed entry is an input error
     * rather than being skipped, because silently dropping an unlock would apply a looser PED limit.
     */
    private static List<Instant> parseUnlockEvents(JsonNode node, CalculationTime calculationTime)
            throws SnapshotParseException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!(node instanceof ArrayNode events)) {
            throw new SnapshotParseException("unlockEvents must be an array of ISO-8601 UTC strings", calculationTime);
        }
        List<Instant> instants = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            String field = "unlockEvents[" + i + "]";
            if (!(events.get(i) instanceof TextNode text)) {
                throw new SnapshotParseException(field + " must be a string", calculationTime);
            }
            try {
                instants.add(CalculationTime.parseUtc(field, text.textValue()));
            } catch (IllegalArgumentException e) {
                throw new SnapshotParseException(e.getMessage(), calculationTime, e);
            }
        }
        return instants;
    }

    /** Serializes a result as a single-line JSON object. */
    public String serialize(IndexResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // Results are plain records of strings and lists; this cannot happen.
            throw new UncheckedIOException(e);
        }
    }

    private static CalculationTime parseCalculationTime(JsonNode node) throws SnapshotParseException {
        return switch (node) {
            case TextNode text -> {
                try {
                    yield CalculationTime.parse(text.textValue());
                } catch (IllegalArgumentException e) {
                    throw new SnapshotParseException(e.getMessage(), null, e);
                }
            }
            case null -> throw new SnapshotParseException("missing required field: calculationTime", null);
            default -> throw new SnapshotParseException("calculationTime must be a string", null);
        };
    }

    private static Book parseBook(JsonNode node) {
        String exchange = node.get("exchange") instanceof TextNode name && !name.textValue().isBlank()
                ? name.textValue()
                : null;
        if (exchange != null
                && node.get("bids") instanceof ArrayNode bids
                && node.get("asks") instanceof ArrayNode asks) {
            return new ExchangeBook(exchange, parseLevels(bids), parseLevels(asks));
        }
        return new UnreadableBook(exchange);
    }

    private static List<PriceLevel> parseLevels(ArrayNode entries) {
        return stream(entries).map(SnapshotParser::parseLevel).flatMap(Optional::stream).toList();
    }

    /**
     * A level is a two-element array of JSON numbers; anything else cannot be represented and is
     * discarded here. The sign check is left to {@code OrderBookScreener}.
     */
    private static Optional<PriceLevel> parseLevel(JsonNode entry) {
        if (entry instanceof ArrayNode pair
                && pair.size() == 2
                && pair.get(0) instanceof NumericNode price
                && pair.get(1) instanceof NumericNode size) {
            return Optional.of(new PriceLevel(price.decimalValue(), size.decimalValue()));
        }
        return Optional.empty();
    }

    private static java.util.stream.Stream<JsonNode> stream(ArrayNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
