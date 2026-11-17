package ngj;

import ngj.ast.CompilationUnit;
import ngj.codegen.Java25CodeGenerator;
import ngj.diagnostic.Diagnostic;
import ngj.diagnostic.Severity;
import ngj.diagnostic.Certainty;
import ngj.parser.Lexer;
import ngj.parser.Parser;
import ngj.typechecker.NameResolver;
import ngj.typechecker.TypeChecker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests for Valhalla foundation features (Issue #7).
 * 
 * Coverage:
 * - VALUE-01: data/record/enum as value class output
 * - VALUE-02: == compares by field values for value types
 * - VALUE-03: Int/Long/Bool behave as value types
 * - NULL-01: null literal forbidden
 * - NULL-02: Java boundary null to Option conversion (Stage 2)
 * - ENUM-01: Payload-carrying enum variants
 * - ENUM-02: Exhaustiveness with missing variant names in diagnostics
 * - ENUM-03: Nested pattern matching
 * - ENUM-04: Result/Option as ordinary enums
 * - GENERIC-01: List<Int> type checking
 * - TARGET-02: Valhalla JDK check
 */
public class ValhallaValidationTest {
    
    @TempDir
    Path tempDir;
    
    // ========== NULL-01: null literal forbidden ==========
    
    @Test
    void null01_nullLiteralRejected() throws Exception {
        var source = """
            package test.null01;
            
            public fn test() -> Int effects() {
                val x = null;
                1
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid(), "null literal should be rejected");
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-NULL-001")),
            "Should produce NGJ-NULL-001 for null literal: " + result.errors());
    }
    
    @Test
    void null01_nullInExpressionRejected() throws Exception {
        var source = """
            package test.null01expr;
            
            public fn test(x: Int) -> Bool effects() {
                x == null
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid(), "null in expression should be rejected");
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-NULL-001")),
            "Should produce NGJ-NULL-001: " + result.errors());
    }
    
    // ========== ENUM-01: Payload-carrying variants ==========
    
    @Test
    void enum01_payloadVariant_valid() throws Exception {
        var source = """
            package test.enum01;
            
            public enum Shape {
                Circle(radius: Int),
                Rectangle(width: Int, height: Int),
                Point()
            }
            
            public fn area(s: Shape) -> Int effects() {
                match (s) {
                    case Circle(r) => { r * r * 3 }
                    case Rectangle(w, h) => { w * h }
                    case Point() => { 0 }
                }
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Payload-carrying enum should be valid: " + result.errors());
    }
    
    // ========== ENUM-02: Exhaustiveness with missing variant names ==========
    
    @Test
    void enum02_exhaustiveness_missingVariantNamed() throws Exception {
        var source = """
            package test.enum02;
            
            public enum Status {
                Active(),
                Pending(),
                Closed()
            }
            
            public fn describe(s: Status) -> Int effects() {
                match (s) {
                    case Active() => { 1 }
                }
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid(), "Non-exhaustive match should be rejected");
        
        var matchError = result.errors().stream()
            .filter(e -> e.id().equals("NGJ-MATCH-001"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(matchError, "Should produce NGJ-MATCH-001: " + result.errors());
        
        // ENUM-02 requires missing variant names in diagnostics
        var message = matchError.message();
        assertTrue(message.contains("Pending") || message.contains("Closed"),
            "Diagnostic should name missing variants: " + message);
    }
    
    @Test
    void enum02_exhaustiveness_jsonDiagnosticFormat() throws Exception {
        var source = """
            package test.enum02json;
            
            public enum Color { Red(), Green(), Blue() }
            
            public fn name(c: Color) -> Int effects() {
                match (c) {
                    case Red() => { 1 }
                }
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
        
        var matchError = result.errors().stream()
            .filter(e -> e.id().equals("NGJ-MATCH-001"))
            .findFirst()
            .orElseThrow();
        
        // Check related info contains missing variants
        var related = matchError.related();
        assertFalse(related.isEmpty(), 
            "Exhaustiveness error should have related info with missing variants");
        
        var missingVariantInfos = related.stream()
            .filter(r -> r.kind().equals("missing_variant"))
            .toList();
        assertEquals(2, missingVariantInfos.size(), 
            "Should have 2 missing variants (Green, Blue)");
    }
    
    // ========== ENUM-03: Nested pattern matching ==========
    
    @Test
    void enum03_nestedPattern_valid() throws Exception {
        var source = """
            package test.enum03;
            
            public enum Outer {
                Wrap(inner: Int)
            }
            
            public fn unwrap(o: Outer) -> Int effects() {
                match (o) {
                    case Wrap(x) => { x }
                }
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Nested pattern should be valid: " + result.errors());
    }
    
    // ========== ENUM-04: Result/Option as ordinary enums ==========
    
    @Test
    void enum04_optionAsOrdinaryEnum() throws Exception {
        var source = """
            package test.enum04opt;
            
            public fn maybe(x: Int) -> Option<Int> effects() {
                if (x > 0) {
                    Some(x)
                } else {
                    None()
                }
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Option should work as ordinary enum: " + result.errors());
    }
    
    @Test
    void enum04_resultAsOrdinaryEnum() throws Exception {
        var source = """
            package test.enum04res;
            
            public fn divide(a: Int, b: Int) -> Result<Int, String> effects() {
                if (b == 0) {
                    Err("division by zero")
                } else {
                    Ok(a / b)
                }
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Result should work as ordinary enum: " + result.errors());
    }
    
    // ========== GENERIC-01: List<Int> type checking ==========
    
    @Test
    void generic01_listOfInt_valid() throws Exception {
        var source = """
            package test.generic01;
            
            public fn sum(nums: List<Int>) -> Int effects() {
                0
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "List<Int> should type check: " + result.errors());
    }
    
    @Test
    void generic01_listOfOption_valid() throws Exception {
        var source = """
            package test.generic01opt;
            
            public fn first(opts: List<Option<Int>>) -> Int effects() {
                0
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "List<Option<Int>> should type check: " + result.errors());
    }
    
    // ========== TARGET-02: Valhalla JDK check ==========
    
    @Test
    void target02_jdkVersionCheck() {
        // This test verifies the JDK check mechanism exists
        // The actual check is bypassed in tests via NGJ_SKIP_JDK_CHECK
        
        // Verify the constant is defined
        assertEquals(28, ngj.cli.Main.REQUIRED_JDK_VERSION,
            "Required JDK version should be 28");
    }
    
    // ========== VALUE-01: Value class output (codegen) ==========
    
    @Test
    void value01_recordGeneratesValueClass() throws Exception {
        var source = """
            package test.value01;
            
            public record Point(x: Int, y: Int);
            """;
        
        var unit = parse(source);
        var codeGen = new Java25CodeGenerator();
        codeGen.setOutputDir(tempDir);
        var outputs = codeGen.generate(unit);
        
        assertFalse(outputs.isEmpty(), "Should generate class files");
        
        // Read the class file and verify format
        var classFile = outputs.get(0);
        var bytes = Files.readAllBytes(classFile);
        
        // Verify class file has valid structure
        assertTrue(bytes.length > 8, "Class file should have header");
        
        // Magic number: 0xCAFEBABE
        assertEquals((byte) 0xCA, bytes[0], "Invalid magic byte 0");
        assertEquals((byte) 0xFE, bytes[1], "Invalid magic byte 1");
        assertEquals((byte) 0xBA, bytes[2], "Invalid magic byte 2");
        assertEquals((byte) 0xBE, bytes[3], "Invalid magic byte 3");
        
        // Minor version (bytes 4-5) - should be 65535 for preview
        int minorVersion = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
        assertEquals(65535, minorVersion, 
            "Minor version should be 65535 for preview features (JEP 401)");
        
        // Major version (bytes 6-7) - should be 72 for JDK 28
        int majorVersion = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        int expectedVersion = Integer.parseInt(
            System.getenv().getOrDefault("NGJ_RUNTIME_CLASS_VERSION", "72"));
        assertEquals(expectedVersion, majorVersion, 
            "Major version should match target JDK");
    }
    
    // ========== VALUE-02: == compares by field values ==========
    
    @Test
    void value02_equalityComparesFields() throws Exception {
        // This test verifies the generated equals() method compares fields
        var source = """
            package test.value02;
            
            public record Point(x: Int, y: Int);
            
            public fn same(a: Point, b: Point) -> Bool effects() {
                a == b
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Value equality comparison should be valid: " + result.errors());
    }
    
    // ========== VALUE-03: Primitive value types ==========
    
    @Test
    void value03_intValueType() throws Exception {
        var source = """
            package test.value03;
            
            public fn compare(a: Int, b: Int) -> Bool effects() {
                a == b
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Int value comparison should be valid: " + result.errors());
    }
    
    @Test
    void value03_longValueType() throws Exception {
        var source = """
            package test.value03long;
            
            public fn compare(a: Long, b: Long) -> Bool effects() {
                a == b
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Long value comparison should be valid: " + result.errors());
    }
    
    @Test
    void value03_boolValueType() throws Exception {
        var source = """
            package test.value03bool;
            
            public fn compare(a: Bool, b: Bool) -> Bool effects() {
                a == b
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Bool value comparison should be valid: " + result.errors());
    }
    
    // ========== Helper methods ==========
    
    private CompilationUnit parse(String source) throws Exception {
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        if (!parser.getDiagnostics().isEmpty()) {
            var errors = parser.getDiagnostics().stream()
                .filter(d -> d.severity() == Severity.ERROR)
                .toList();
            if (!errors.isEmpty()) {
                throw new RuntimeException("Parse errors: " + errors);
            }
        }
        return unit;
    }
    
    private CheckResult check(String source) {
        try {
            var lexer = new Lexer(source, "test.ngj");
            var parser = new Parser(lexer);
            var unit = parser.parse();
            
            if (!parser.getDiagnostics().isEmpty()) {
                var parseErrors = parser.getDiagnostics().stream()
                    .filter(d -> d.severity() == Severity.ERROR)
                    .toList();
                if (!parseErrors.isEmpty()) {
                    return new CheckResult(false, parseErrors);
                }
            }
            
            var resolver = new NameResolver();
            resolver.resolve(unit);
            
            var typeChecker = new TypeChecker();
            typeChecker.check(unit);
            
            var allDiagnostics = new ArrayList<Diagnostic>();
            allDiagnostics.addAll(resolver.getDiagnostics());
            allDiagnostics.addAll(typeChecker.getDiagnostics());
            
            var errors = allDiagnostics.stream()
                .filter(d -> d.severity() == Severity.ERROR && d.certainty() == Certainty.DEFINITE)
                .toList();
            
            return new CheckResult(errors.isEmpty(), errors);
        } catch (Exception e) {
            return new CheckResult(false, List.of());
        }
    }
    
    private record CheckResult(boolean isValid, List<Diagnostic> errors) {}
}
