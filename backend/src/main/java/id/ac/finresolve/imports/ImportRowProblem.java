package id.ac.finresolve.imports;

/** One safe, replayable row problem from an import report. Never contains a narrative. */
public record ImportRowProblem(int recordNumber, String field, String code, String message) {
}
