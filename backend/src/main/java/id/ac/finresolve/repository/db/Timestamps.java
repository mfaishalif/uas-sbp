package id.ac.finresolve.repository.db;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;

/**
 * Fixed-width UTC timestamp text used by SQLite.
 *
 * <p>SQLite stores these values as text. Always rendering nine fractional digits keeps
 * lexicographic order identical to chronological order inside the same second.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder()
            .appendInstant(9)
            .toFormatter(Locale.ROOT);

    private Timestamps() {
    }

    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }

    public static Instant parse(String text) {
        return Instant.parse(text);
    }
}
