package com.termux.app.theme.schema;

/** One stable validation or resolution diagnostic for a UI Schema document. */
public final class YummyUiSchemaDiagnostic {
    public static final String SEVERITY_ERROR = "error";
    public static final String SEVERITY_WARNING = "warning";

    private final String code;
    private final String severity;
    private final String propertyId;
    private final String path;
    private final String message;

    public YummyUiSchemaDiagnostic(
        String code, String severity, String propertyId, String path, String message
    ) {
        this.code = requireText(code, "code");
        this.severity = requireText(severity, "severity");
        this.propertyId = propertyId;
        this.path = path;
        this.message = requireText(message, "message");
    }

    public String getCode() { return code; }
    public String getSeverity() { return severity; }
    public String getPropertyId() { return propertyId; }
    public String getPath() { return path; }
    public String getMessage() { return message; }
    public boolean isError() { return SEVERITY_ERROR.equals(severity); }
    public boolean isWarning() { return SEVERITY_WARNING.equals(severity); }

    @Override
    public String toString() {
        return "[" + severity + "/" + code + "] "
            + (path == null ? "" : path + ": ") + message;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value.trim();
    }
}
