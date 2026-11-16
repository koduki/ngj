package ngj;

import ngj.ast.*;
import ngj.cli.ExitCode;
import ngj.cli.Main;
import ngj.diagnostic.*;
import ngj.parser.*;
import ngj.typechecker.*;
import ngj.codegen.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Main compiler facade for NGJ.
 */
public final class Compiler {
    
    private final Map<Path, CompilationUnit> parsedUnits = new HashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    
    public CheckResult check(List<Path> files) {
        diagnostics.clear();
        parsedUnits.clear();
        
        List<CompilationUnit> units = new ArrayList<>();
        
        // Phase 1: Parse all files
        for (Path file : files) {
            try {
                String source = Files.readString(file);
                var lexer = new Lexer(source, file.toString());
                var parser = new Parser(lexer);
                var unit = parser.parse();
                parsedUnits.put(file, unit);
                units.add(unit);
                diagnostics.addAll(parser.getDiagnostics());
            } catch (IOException e) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-IO-001",
                    "Cannot read file: " + e.getMessage(),
                    new SourceSpan(file.toString(), 0, 0)
                ));
            } catch (ParseException e) {
                diagnostics.addAll(e.getDiagnostics());
            }
        }
        
        if (hasErrors()) {
            return new CheckResult(CheckStatus.INVALID, diagnostics, units);
        }
        
        // Phase 2: Name resolution
        var resolver = new NameResolver();
        for (var unit : units) {
            resolver.resolve(unit);
        }
        diagnostics.addAll(resolver.getDiagnostics());
        
        if (hasErrors()) {
            return new CheckResult(CheckStatus.INVALID, diagnostics, units);
        }
        
        // Phase 3: Type checking
        var typeChecker = new TypeChecker();
        for (var unit : units) {
            typeChecker.check(unit);
        }
        diagnostics.addAll(typeChecker.getDiagnostics());
        
        // Determine status
        if (hasErrors()) {
            return new CheckResult(CheckStatus.INVALID, diagnostics, units);
        } else if (hasHoles(units)) {
            return new CheckResult(CheckStatus.INCOMPLETE, diagnostics, units);
        } else {
            return new CheckResult(CheckStatus.VALID, diagnostics, units);
        }
    }
    
    public ContextResult context(String holeId, int maxBytes) {
        // Find the hole in parsed units
        for (var entry : parsedUnits.entrySet()) {
            var unit = entry.getValue();
            var hole = findHole(unit, holeId);
            if (hole != null) {
                return buildContext(hole, maxBytes);
            }
        }
        
        return new ContextResult(null, "Hole not found: " + holeId);
    }
    
    public InspectResult inspect(String symbolId) {
        for (var entry : parsedUnits.entrySet()) {
            var unit = entry.getValue();
            var symbol = findSymbol(unit, symbolId);
            if (symbol != null) {
                return new InspectResult(symbol);
            }
        }
        
        return new InspectResult(null, "Symbol not found: " + symbolId);
    }
    
    public BuildResult build(List<Path> files, String target) {
        // First, run check
        var checkResult = check(files);
        
        if (checkResult.status() == CheckStatus.INVALID) {
            return new BuildResult(false, diagnostics, null, "Compilation errors");
        }
        
        if (checkResult.status() == CheckStatus.INCOMPLETE) {
            diagnostics.add(Diagnostic.error(
                "NGJ-BUILD-001",
                "Cannot build: source contains unresolved holes",
                null
            ));
            return new BuildResult(false, diagnostics, null, "Holes present");
        }
        
        if (!target.equals("java25")) {
            diagnostics.add(Diagnostic.error(
                "NGJ-BUILD-002",
                "Unsupported target: " + target + ". MVP only supports java25",
                null
            ));
            return new BuildResult(false, diagnostics, null, "Unsupported target");
        }
        
        // Phase 4: Code generation
        try {
            var generator = new Java25CodeGenerator();
            var outputFiles = new ArrayList<Path>();
            
            for (var unit : checkResult.units()) {
                var generatedFiles = generator.generate(unit);
                outputFiles.addAll(generatedFiles);
            }
            
            return new BuildResult(true, diagnostics, outputFiles, null);
        } catch (CodeGenException e) {
            diagnostics.add(Diagnostic.error(
                "NGJ-CODEGEN-001",
                "Code generation failed: " + e.getMessage(),
                null
            ));
            return new BuildResult(false, diagnostics, null, e.getMessage());
        }
    }
    
    private boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> 
            d.severity() == Severity.ERROR && d.certainty() != Certainty.INCOMPLETE);
    }
    
    private boolean hasHoles(List<CompilationUnit> units) {
        for (var unit : units) {
            if (containsHoles(unit)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean containsHoles(CompilationUnit unit) {
        return new HoleFinder().hasHoles(unit);
    }
    
    private HoleExpr findHole(CompilationUnit unit, String holeId) {
        return new HoleFinder().findHole(unit, holeId);
    }
    
    private SymbolInfo findSymbol(CompilationUnit unit, String symbolId) {
        return new SymbolFinder().find(unit, symbolId);
    }
    
    private ContextResult buildContext(HoleExpr hole, int maxBytes) {
        var context = new HoleContext(
            hole.name(),
            hole.expectedType(),
            hole.allowedEffects(),
            hole.bindings(),
            hole.relevantSymbols()
        );
        return new ContextResult(context, null);
    }
}
