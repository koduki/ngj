package ngj;

import ngj.parser.*;
import ngj.typechecker.*;
import ngj.diagnostic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Disabled;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for v0.3 syntax migration cases from docs/SYNTAX_MIGRATION.md
 */
class SyntaxMigrationTest {
    
    @Test
    void valAtEndOfIntFunction_valid() throws Exception {
        var source = """
            package test.tail;
            
            public fn answer() -> Int effects() {
                val n = 1;
                n
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "val at end of Int function should be valid: " + result.errors());
    }
    
    @Test
    void intFunctionEndingInSemicolon_invalid() throws Exception {
        var source = """
            package test.lost;
            
            public fn answer() -> Int effects() {
                1;
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid(), "Block ending with semicolon should be invalid");
    }
    
    @Test
    void adjacentBindingsWithoutSemicolon_diagnostic() throws Exception {
        var source = """
            package test.semi;
            
            public fn test() -> Int effects() {
                val a = 1
                val b = 2;
                a + b
            }
            """;
        
        try {
            parse(source);
            fail("Should produce parse error for missing semicolon");
        } catch (ParseException e) {
            assertTrue(e.getDiagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("semicolon") || 
                              d.message().toLowerCase().contains(";")));
        }
    }
    
    @Test
    void ifWithMismatchedBranchTypes_typeError() throws Exception {
        var source = """
            package test.mismatch;
            
            public fn test(x: Int) -> Int effects() {
                if (x > 0) { 42 } else { "hello" }
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
    }
    
    @Test
    void ifWithoutElseAndNonUnitBody_typeError() throws Exception {
        var source = """
            package test.noelse;
            
            public fn test(x: Int) -> Int effects() {
                if (x > 0) { 42 }
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-TYPE-003") || 
                          e.message().toLowerCase().contains("else")));
    }
    
    @Test
    void noneConstructorWithParens_valid() throws Exception {
        var source = """
            package test.none;
            
            public fn empty() -> Option<Int> effects() {
                None()
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "None() should be valid: " + result.errors());
    }
    
    @Test
    void propagateOnOptionType_error() throws Exception {
        var source = """
            package test.optprop;
            
            public fn optProp(o: Option<Int>) -> Option<Int> effects() {
                val x = o?;
                Some(x + 1)
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-TYPE-020") && 
                          e.message().contains("Option")));
    }
    
    @Test
    void holePreservesPositionAndType() throws Exception {
        var source = """
            package test.hole;
            
            public fn calc() -> Int effects() {
                ?body
            }
            """;
        
        var result = check(source);
        assertTrue(result.isIncomplete());
        var holes = result.incompleteItems();
        assertFalse(holes.isEmpty());
    }
    
    @Test
    void legacyReturnSpelling_diagnostic() throws Exception {
        var source = """
            package test.legacy;
            
            public fn test() -> Int effects() {
                return 42
            }
            """;
        
        try {
            parse(source);
        } catch (ParseException e) {
            assertTrue(e.getDiagnostics().stream()
                .anyMatch(d -> d.id().startsWith("NGJ-MIGRATE") || 
                              d.message().toLowerCase().contains("return")));
            return;
        }
        fail("Should produce migration diagnostic for 'return'");
    }
    
    @Test
    void legacyModuleSpelling_diagnostic() throws Exception {
        var source = """
            module test.legacy;
            
            public fn test() -> Int effects() { 42 }
            """;
        
        try {
            parse(source);
        } catch (ParseException e) {
        }
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        try { parser.parse(); } catch (ParseException e) {}
        
        assertTrue(parser.getDiagnostics().stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-001") ||
                          d.message().contains("package")));
    }
    
    @Test
    void legacyPubSpelling_diagnostic() throws Exception {
        var source = """
            package test.legacy;
            
            pub fn test() -> Int effects() { 42 }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        try { parser.parse(); } catch (ParseException e) {}
        
        assertTrue(parser.getDiagnostics().stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-003") ||
                          d.message().toLowerCase().contains("public")));
    }
    
    @Test
    void legacyDataSpelling_diagnostic() throws Exception {
        var source = """
            package test.legacy;
            
            data Point { x: Int, y: Int }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        try { parser.parse(); } catch (ParseException e) {}
        
        assertTrue(parser.getDiagnostics().stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-005") ||
                          d.message().toLowerCase().contains("record")));
    }
    
    @Test
    void legacyLetSpelling_diagnostic() throws Exception {
        var source = """
            package test.legacy;
            
            public fn test() -> Int effects() {
                let x = 42;
                x
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        try { parser.parse(); } catch (ParseException e) {}
        
        assertTrue(parser.getDiagnostics().stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-002") ||
                          d.message().toLowerCase().contains("val")));
    }
    
    @Test
    void legacyEffectBraces_diagnostic() throws Exception {
        var source = """
            package test.legacy;
            
            public fn test() -> Int ! {} { 42 }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        try { parser.parse(); } catch (ParseException e) {}
        
        assertTrue(parser.getDiagnostics().stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-006") ||
                          d.message().contains("effects()")));
    }
    
    @Test
    void forYieldSyntax_parsesCorrectly() throws Exception {
        var source = """
            package test.foryield;
            
            public fn test(n: Int) -> Int effects() {
                for (x <- n) yield { x * 2 };
                42
            }
            """;
        
        var unit = parse(source);
        assertNotNull(unit);
    }
    
    @Test
    @Disabled("Requires List<T> type to be implemented for full iteration check")
    void forWithoutYield_bodyMustBeUnit() throws Exception {
        var source = """
            package test.forunit;
            
            public fn consume(nums: List<Int>) -> Unit effects() {
                for (x <- nums) { x }
            }
            """;
        
        var result = check(source);
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-TYPE-018")),
            "for without yield should require Unit body: " + result.errors());
    }
    
    @Test
    void whileUpdatingLocalVar_valid() throws Exception {
        var source = """
            package test.whilevar;
            
            public fn sum(limit: Int) -> Int effects() {
                var total = 0;
                var i = 0;
                while (i < limit) {
                    total = total + i;
                    i = i + 1;
                };
                total
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "while updating local var should be valid: " + result.errors());
    }
    
    private ngj.ast.CompilationUnit parse(String source) throws ParseException {
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        if (!parser.getDiagnostics().isEmpty()) {
            throw new ParseException(parser.getDiagnostics());
        }
        return unit;
    }
    
    private CheckResult check(String source) {
        try {
            var lexer = new Lexer(source, "test.ngj");
            var parser = new Parser(lexer);
            var unit = parser.parse();
            
            if (!parser.getDiagnostics().isEmpty()) {
                return new CheckResult(false, false, parser.getDiagnostics(), java.util.List.of());
            }
            
            var resolver = new NameResolver();
            resolver.resolve(unit);
            
            var typeChecker = new TypeChecker();
            typeChecker.check(unit);
            
            var allDiagnostics = new java.util.ArrayList<Diagnostic>();
            allDiagnostics.addAll(resolver.getDiagnostics());
            allDiagnostics.addAll(typeChecker.getDiagnostics());
            
            var errors = allDiagnostics.stream()
                .filter(d -> d.severity() == Severity.ERROR && d.certainty() == Certainty.DEFINITE)
                .toList();
            
            var incomplete = allDiagnostics.stream()
                .filter(d -> d.certainty() == Certainty.INCOMPLETE)
                .toList();
            
            return new CheckResult(errors.isEmpty(), !incomplete.isEmpty(), errors, incomplete);
        } catch (ParseException e) {
            return new CheckResult(false, false, e.getDiagnostics(), java.util.List.of());
        }
    }
    
    private record CheckResult(
        boolean isValid,
        boolean isIncomplete,
        java.util.List<Diagnostic> errors,
        java.util.List<Diagnostic> incompleteItems
    ) {}
}
