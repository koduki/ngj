package ngj.parser;

import ngj.diagnostic.Diagnostic;
import java.util.List;

public class ParseException extends Exception {
    private final List<Diagnostic> diagnostics;
    
    public ParseException(List<Diagnostic> diagnostics) {
        super(diagnostics.isEmpty() ? "Parse error" : diagnostics.get(0).message());
        this.diagnostics = diagnostics;
    }
    
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
