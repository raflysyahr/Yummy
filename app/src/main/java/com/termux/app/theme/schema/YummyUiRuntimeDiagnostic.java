package com.termux.app.theme.schema;

/** Runtime-only diagnostic produced while applying a resolved property to an Android View. */
public final class YummyUiRuntimeDiagnostic {
    private final String code;
    private final String propertyId;
    private final String message;
    private final long generation;

    YummyUiRuntimeDiagnostic(String code, String propertyId, String message, long generation) {
        this.code = code;
        this.propertyId = propertyId;
        this.message = message;
        this.generation = generation;
    }

    public String getCode() { return code; }
    public String getPropertyId() { return propertyId; }
    public String getMessage() { return message; }
    public long getGeneration() { return generation; }

    @Override
    public String toString() {
        return code + " [generation " + generation + "] " + propertyId + ": " + message;
    }
}
