package id.ac.finresolve.cases;

import java.util.List;
import java.util.UUID;

/**
 * One complete analyst retention command.
 *
 * <p>An omitted reference list is treated as empty. Explicit JSON {@code null} is rejected at the
 * HTTP boundary before this record is created.
 */
public record RetainCommand(
        ComplaintInput complaint,
        CaseRecord.Notes notes,
        List<UUID> referenceCaseIds) {

    public RetainCommand {
        referenceCaseIds = referenceCaseIds == null ? List.of() : List.copyOf(referenceCaseIds);
    }
}
