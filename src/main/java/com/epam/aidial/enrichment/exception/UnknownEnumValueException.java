package com.epam.aidial.enrichment.exception;

import lombok.Getter;

import java.util.List;

/**
 * A value outside a closed vocabulary — a run state, a pause reason, a dead-letter lane — read from a query
 * parameter or a stored column.
 *
 * <p>Carries the permitted spellings so the web layer can name them in a {@code 400} without echoing the
 * caller's input: a request that filters on a misspelled state must be refused rather than answered with an
 * empty list, and the refusal is only useful if it says what would have been accepted.
 */
@Getter
public class UnknownEnumValueException extends IllegalArgumentException {

    private final List<String> permitted;

    public UnknownEnumValueException(List<String> permitted) {
        super("Unrecognised value; permitted values: " + String.join(", ", permitted));
        this.permitted = List.copyOf(permitted);
    }
}
