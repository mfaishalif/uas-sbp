package id.ac.finresolve.domain.cases;

/** Capacity rule shared by both import and analyst retention. */
public final class CaseCapacity {

    /** Shared demo capacity for imported and manually retained cases. */
    public static final int MAX_CASES = 5_000;

    private CaseCapacity() {
    }

    public static boolean wouldExceed(long currentCount, int additionalCases) {
        if (currentCount < 0 || additionalCases < 0) {
            throw new IllegalArgumentException("case counts must not be negative");
        }
        return currentCount + additionalCases > MAX_CASES;
    }
}
