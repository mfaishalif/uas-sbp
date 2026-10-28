package id.ac.finresolve.domain.text;

import id.ac.finresolve.domain.error.BusinessException.Problem;
import java.util.List;

/**
 * Shared Unicode scalar validation for every client-supplied text value.
 *
 * <p>U+0000 is rejected in any position because SQLite string functions stop at NUL, which would
 * turn an otherwise valid request into a persistence failure. Unpaired UTF-16 surrogates are not
 * Unicode scalar values and cannot be represented faithfully in UTF-8, so they are rejected before
 * normalization or persistence. Valid surrogate pairs, tabs, newlines, and other controls are
 * intentionally left to the field-specific rules.
 */
public final class UnicodeText {

    public static final String INVALID_FORMAT_MESSAGE =
            "Nilai teks mengandung karakter yang tidak didukung.";

    private UnicodeText() {
    }

    /** Returns whether every UTF-16 unit is part of a scalar value and no NUL is present. */
    public static boolean isWellFormed(String value) {
        if (value == null) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (unit == '\u0000') {
                return false;
            }
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            }
        }
        return true;
    }

    /** Adds one safe INVALID_FORMAT problem when the value is not valid Unicode text. */
    public static void addProblemIfInvalid(
            String value, String field, List<Problem> problems) {
        if (!isWellFormed(value)) {
            problems.add(new Problem(field, "INVALID_FORMAT", INVALID_FORMAT_MESSAGE));
        }
    }

    /** Enforces the same rule for a materialized domain record. */
    public static void requireWellFormed(String value, String field) {
        if (!isWellFormed(value)) {
            throw new IllegalArgumentException(field + " contains an unsupported Unicode character.");
        }
    }
}
