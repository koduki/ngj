package ngj;

import ngj.parser.*;
import ngj.typechecker.*;
import ngj.diagnostic.*;
import org.junit.jupiter.api.*;

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
                return n;
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "val + return at end of Int function should be valid: " + result.errors());
    }
    
    @Test
    void missingReturnOnTailExpr_invalid() throws Exception {
        var source = """
            package test.noreturn;
            
            public fn answer() -> Int effects() {
                val n = 1;
                n
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid(), "Function without return should be invalid");
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-RETURN-001")),
            "Should produce NGJ-RETURN-001: " + result.errors());
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
        assertFalse(result.isValid(), "Block ending with semicolon without return should be invalid");
    }
    
    @Test
    void adjacentBindingsWithoutSemicolon_diagnostic() throws Exception {
        var source = """
            package test.semi;
            
            public fn test() -> Int effects() {
                val a = 1
                val b = 2;
                return a + b;
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
                return if (x > 0) { 42 } else { "hello" };
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
                return None();
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
                return Some(x + 1);
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
    void returnWithoutSemicolon_diagnostic() throws Exception {
        var source = """
            package test.retsemi;
            
            public fn test() -> Int effects() {
                return 42
            }
            """;
        
        try {
            parse(source);
        } catch (ParseException e) {
            assertTrue(e.getDiagnostics().stream()
                .anyMatch(d -> d.id().equals("NGJ-PARSE-008") || 
                              d.message().toLowerCase().contains("return")));
            return;
        }
        fail("Should produce parse error for 'return' without semicolon");
    }
    
    @Test
    void earlyReturnInGuardClause_valid() throws Exception {
        var source = """
            package test.guard;
            
            public fn clampPositive(x: Int) -> Int effects() {
                if (x < 0) {
                    return 0;
                }
                return x;
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Early return in guard clause should be valid: " + result.errors());
    }
    
    @Test
    void legacyModuleSpelling_diagnostic() throws Exception {
        var source = """
            module test.legacy;
            
            public fn test() -> Int effects() { return 42; }
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
            
            pub fn test() -> Int effects() { return 42; }
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
                return x;
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
            
            public fn test() -> Int ! {} { return 42; }
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
                return 42;
            }
            """;
        
        var unit = parse(source);
        assertNotNull(unit);
    }
    
    @Test
    void forWithoutYield_bodyMustBeUnit() throws Exception {
        var source = """
            package test.forunit;
            
            public fn consume(nums: List<Int>) -> Unit effects() {
                for (x <- nums) { x }
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
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
                return total;
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "while updating local var should be valid: " + result.errors());
    }
    
    // ========== List<T> Tests ==========
    
    @Test
    void emptyList_Nil_valid() throws Exception {
        var source = """
            package test.list;
            
            public fn empty() -> List<Int> effects() {
                return Nil();
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Nil() should produce valid List<_>: " + result.errors());
    }
    
    @Test
    void listOf_withElements_valid() throws Exception {
        var source = """
            package test.list;
            
            public fn nums() -> List<Int> effects() {
                return listOf(1, 2, 3);
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "listOf(1,2,3) should produce List<Int>: " + result.errors());
    }
    
    @Test
    void listOf_emptyList_valid() throws Exception {
        var source = """
            package test.list;
            
            public fn empty() -> List<Int> effects() {
                return listOf();
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "listOf() should produce List<_>: " + result.errors());
    }
    
    @Test
    void listOf_mixedTypes_error() throws Exception {
        var source = """
            package test.list;
            
            public fn mixed() -> List<Int> effects() {
                return listOf(1, "two", 3);
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-TYPE-023")),
            "listOf with mixed types should error: " + result.errors());
    }
    
    @Test
    void forOverList_valid() throws Exception {
        var source = """
            package test.forlist;
            
            public fn process(nums: List<Int>) -> Unit effects() {
                for (x <- nums) {
                    val y = x;
                }
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "for over List<Int> should be valid: " + result.errors());
    }
    
    @Test
    void forYieldOverList_producesListType() throws Exception {
        var source = """
            package test.foryield;
            
            public fn double(nums: List<Int>) -> List<Int> effects() {
                return for (x <- nums) yield { x * 2 };
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "for-yield should produce List<Int>: " + result.errors());
    }
    
    @Test
    void forOverNonList_error() throws Exception {
        var source = """
            package test.fornonlist;
            
            public fn test(n: Int) -> Unit effects() {
                for (x <- n) { x; }
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-TYPE-026")),
            "for over non-List should error: " + result.errors());
    }
    
    // ========== Outer var capture diagnostic ==========
    
    @Test
    void forBody_outerVarCapture_error() throws Exception {
        var source = """
            package test.capture;
            
            public fn test(nums: List<Int>) -> Unit effects() {
                var total = 0;
                for (x <- nums) {
                    total = total + x;
                }
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-CAPTURE-001")),
            "Outer var capture in for body should error: " + result.errors());
    }
    
    @Test
    void forBody_localVar_valid() throws Exception {
        var source = """
            package test.localvar;
            
            public fn test(nums: List<Int>) -> Unit effects() {
                for (x <- nums) {
                    var temp = x * 2;
                    temp = temp + 1;
                }
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Local var in for body should be valid: " + result.errors());
    }
    
    // ========== ? propagation in for body ==========
    
    @Test
    void forBody_propagate_error() throws Exception {
        var source = """
            package test.forprop;
            
            public fn test(nums: List<Int>) -> Result<Unit, String> effects() {
                for (x <- nums) {
                    val r: Result<Int, String> = Ok(x);
                    val v = r?;
                    v;
                };
                return Ok(());
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-PROP-002")),
            "? in for body should error: " + result.errors());
    }
    
    @Test
    void forYield_resultWithoutPropagate_producesListOfResult() throws Exception {
        var source = """
            package test.listresult;
            
            public fn process(nums: List<Int>) -> List<Result<Int, String>> effects() {
                return for (x <- nums) yield {
                    val r: Result<Int, String> = Ok(x * 2);
                    r
                };
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), 
            "for-yield with Result body should produce List<Result<T,E>>: " + result.errors());
    }
    
    // ========== Enum match exhaustiveness ==========
    
    @Test
    void enumMatch_exhaustive_valid() throws Exception {
        // Per EBNF spec: variant = IDENT "(" [ parameters ] ")"
        // Variants and constructor patterns always have parentheses
        var source = """
            package test.exhaustive;
            
            public enum Color { Red(), Green(), Blue() }
            
            public fn name(c: Color) -> Int effects() {
                return match (c) {
                    case Red() => { 1 }
                    case Green() => { 2 }
                    case Blue() => { 3 }
                };
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Exhaustive enum match should be valid: " + result.errors());
    }
    
    @Test
    void enumMatch_nonExhaustive_error() throws Exception {
        // Per EBNF spec: variant = IDENT "(" [ parameters ] ")"
        var source = """
            package test.nonexhaustive;
            
            public enum Color { Red(), Green(), Blue() }
            
            public fn name(c: Color) -> Int effects() {
                return match (c) {
                    case Red() => { 1 }
                    case Green() => { 2 }
                };
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid());
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-MATCH-001")),
            "Non-exhaustive enum match should error: " + result.errors());
    }
    
    @Test
    void enumMatch_withWildcard_valid() throws Exception {
        // Per EBNF spec: variant = IDENT "(" [ parameters ] ")"
        var source = """
            package test.wildcard;
            
            public enum Color { Red(), Green(), Blue() }
            
            public fn name(c: Color) -> Int effects() {
                return match (c) {
                    case Red() => { 1 }
                    case _ => { 0 }
                };
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Match with wildcard should be valid: " + result.errors());
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
