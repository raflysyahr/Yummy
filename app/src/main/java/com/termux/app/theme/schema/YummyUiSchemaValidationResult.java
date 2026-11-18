package com.termux.app.theme.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable outcome of validating one complete UI Schema document. */
public final class YummyUiSchemaValidationResult {
    private final List<YummyUiSchemaDiagnostic> errors;
    private final List<YummyUiSchemaDiagnostic> warnings;

    YummyUiSchemaValidationResult(
        List<YummyUiSchemaDiagnostic> errors,
        List<YummyUiSchemaDiagnostic> warnings
    ) {
        this.errors = immutableCopy(errors);
        this.warnings = immutableCopy(warnings);
    }

    public boolean isValid() { return errors.isEmpty(); }
    public List<YummyUiSchemaDiagnostic> getErrors() { return errors; }
    public List<YummyUiSchemaDiagnostic> getWarnings() { return warnings; }

    public List<YummyUiSchemaDiagnostic> getDiagnostics() {
        List<YummyUiSchemaDiagnostic> all = new ArrayList<>(errors);
        all.addAll(warnings);
        return Collections.unmodifiableList(all);
    }

    private static List<YummyUiSchemaDiagnostic> immutableCopy(
        List<YummyUiSchemaDiagnostic> source
    ) {
        return Collections.unmodifiableList(
            source == null ? new ArrayList<>() : new ArrayList<>(source)
        );
    }
}
