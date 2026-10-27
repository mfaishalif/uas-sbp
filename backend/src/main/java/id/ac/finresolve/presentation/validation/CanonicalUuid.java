package id.ac.finresolve.presentation.validation;

import java.util.UUID;
import java.util.regex.Pattern;

/** The single canonical UUID rule shared by path, query, and request-body validation. */
public final class CanonicalUuid {

    public static final String REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private CanonicalUuid() {
    }

    public static boolean isCanonical(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    public static UUID parse(String value) {
        if (!isCanonical(value)) {
            throw new IllegalArgumentException("UUID must use the full canonical form.");
        }
        return UUID.fromString(value);
    }
}
