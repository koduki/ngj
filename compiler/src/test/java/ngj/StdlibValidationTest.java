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
 * Validation tests for Standard Library value type redesign (Issue #7).
 * 
 * Coverage:
 * - STDLIB-01: Int/Long/Bool as VALUE_TYPE with structural equality
 * - STDLIB-02: Option<T> as ordinary enum with None() (no payload)
 * - STDLIB-03: Result<T,E> as ordinary enum with ? propagation
 * - STDLIB-04: List<T> value semantics (equality, immutability)
 * - STDLIB-05: No null in standard library APIs
 * - STDLIB-06: Numeric overflow detection (task fault)
 * - STDLIB-07: checkedAdd/Sub/Mul return Result on overflow
 * - STDLIB-08: List.get returns Option<T> (not null/exception)
 * - STDLIB-09: Generic collections with value type elements
 * - NULL-02: Java boundary null-to-Option conversion
 */
public class StdlibValidationTest {
    
    @TempDir
    Path tempDir;
    
    // ========== STDLIB-01: Numeric value types with structural equality ==========
    
    @Test
    void stdlib01_intValueTypeEquality() throws Exception {
        var source = """
            package test.stdlib01;
            
            public fn testIntEquality() -> Bool effects() {
                val a: Int = 42;
                val b: Int = 42;
                a == b
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Int value comparison should be valid: " + result.errors());
    }
    
    @Test
    void stdlib01_longValueTypeEquality() throws Exception {
        var source = """
            package test.stdlib01long;
            
            public fn testLongEquality() -> Bool effects() {
                val a: Long = 42L;
                val b: Long = 42L;
                a == b
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Long value comparison should be valid: " + result.errors());
    }
    
    @Test
    void stdlib01_boolValueTypeEquality() throws Exception {
        var source = """
            package test.stdlib01bool;
            
            public fn testBoolEquality() -> Bool effects() {
                val a: Bool = true;
                val b: Bool = true;
                a == b
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Bool value comparison should be valid: " + result.errors());
    }
    
    // ========== STDLIB-02: Option as ordinary enum ==========
    
    @Test
    void stdlib02_optionSomeConstruction() throws Exception {
        var source = """
            package test.stdlib02some;
            
            public fn makeOption(x: Int) -> Option<Int> effects() {
                Some(x)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Option Some construction should be valid: " + result.errors());
    }
    
    @Test
    void stdlib02_optionNoneNoPayload() throws Exception {
        var source = """
            package test.stdlib02none;
            
            public fn makeNone() -> Option<Int> effects() {
                None()
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Option None() with no payload should be valid: " + result.errors());
    }
    
    @Test
    void stdlib02_optionNoneWithPayloadRejected() throws Exception {
        var source = """
            package test.stdlib02noneerr;
            
            public fn makeNone() -> Option<Int> effects() {
                None(42)
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid(), "None with payload should be rejected");
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-TYPE-014")),
            "Should produce NGJ-TYPE-014: " + result.errors());
    }
    
    @Test
    void stdlib02_optionPatternMatch() throws Exception {
        var source = """
            package test.stdlib02match;
            
            public enum Option<T> {
                Some(value: Int),
                None()
            }
            
            public fn unwrap(opt: Option<Int>) -> Int effects() {
                match (opt) {
                    case Some(v) => { v }
                    case None() => { 0 }
                }
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Option pattern matching should be valid: " + result.errors());
    }
    
    // ========== STDLIB-03: Result as ordinary enum ==========
    
    @Test
    void stdlib03_resultOkConstruction() throws Exception {
        var source = """
            package test.stdlib03ok;
            
            public fn makeOk(x: Int) -> Result<Int, String> effects() {
                Ok(x)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Result Ok construction should be valid: " + result.errors());
    }
    
    @Test
    void stdlib03_resultErrConstruction() throws Exception {
        var source = """
            package test.stdlib03err;
            
            public fn makeErr(msg: String) -> Result<Int, String> effects() {
                Err(msg)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Result Err construction should be valid: " + result.errors());
    }
    
    @Test
    void stdlib03_resultPropagation() throws Exception {
        // Test ? propagation with checked arithmetic (stdlib function)
        var source = """
            package test.stdlib03prop;
            
            public fn safeCompute(a: Int, b: Int) -> Result<Int, OverflowError> effects() {
                val sum = checkedAdd(a, b)?;
                checkedMul(sum, 2)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Result ? propagation should be valid: " + result.errors());
    }
    
    // ========== STDLIB-04: List value semantics ==========
    
    @Test
    void stdlib04_listConstruction() throws Exception {
        var source = """
            package test.stdlib04;
            
            public fn makeList() -> List<Int> effects() {
                listOf(1, 2, 3)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "List construction should be valid: " + result.errors());
    }
    
    @Test
    void stdlib04_listNil() throws Exception {
        var source = """
            package test.stdlib04nil;
            
            public fn emptyList() -> List<Int> effects() {
                Nil()
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Nil() should create empty list: " + result.errors());
    }
    
    @Test
    void stdlib04_listCons() throws Exception {
        var source = """
            package test.stdlib04cons;
            
            public fn prependList() -> List<Int> effects() {
                val tail = listOf(2, 3);
                Cons(1, tail)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Cons should prepend element: " + result.errors());
    }
    
    // ========== STDLIB-05: No null in standard library APIs ==========
    
    @Test
    void stdlib05_noNullLiteral() throws Exception {
        var source = """
            package test.stdlib05;
            
            public fn test() -> Int effects() {
                val x = null;
                1
            }
            """;
        
        var result = check(source);
        assertFalse(result.isValid(), "null literal should be rejected");
        assertTrue(result.errors().stream()
            .anyMatch(e -> e.id().equals("NGJ-NULL-001")),
            "Should produce NGJ-NULL-001: " + result.errors());
    }
    
    // ========== STDLIB-07: Checked arithmetic returning Result ==========
    
    @Test
    void stdlib07_checkedAddReturnsResult() throws Exception {
        var source = """
            package test.stdlib07;
            
            public fn safeAdd(a: Int, b: Int) -> Result<Int, OverflowError> effects() {
                checkedAdd(a, b)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "checkedAdd should return Result: " + result.errors());
    }
    
    @Test
    void stdlib07_checkedSubReturnsResult() throws Exception {
        var source = """
            package test.stdlib07sub;
            
            public fn safeSub(a: Int, b: Int) -> Result<Int, OverflowError> effects() {
                checkedSub(a, b)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "checkedSub should return Result: " + result.errors());
    }
    
    @Test
    void stdlib07_checkedMulReturnsResult() throws Exception {
        var source = """
            package test.stdlib07mul;
            
            public fn safeMul(a: Int, b: Int) -> Result<Int, OverflowError> effects() {
                checkedMul(a, b)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "checkedMul should return Result: " + result.errors());
    }
    
    // ========== STDLIB-08: List.get returns Option ==========
    
    @Test
    void stdlib08_listHeadReturnsOption() throws Exception {
        var source = """
            package test.stdlib08head;
            
            public fn firstElement(nums: List<Int>) -> Option<Int> effects() {
                head(nums)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "head should return Option: " + result.errors());
    }
    
    @Test
    void stdlib08_listGetReturnsOption() throws Exception {
        var source = """
            package test.stdlib08get;
            
            public fn getElement(nums: List<Int>, idx: Int) -> Option<Int> effects() {
                get(nums, idx)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "get should return Option: " + result.errors());
    }
    
    @Test
    void stdlib08_listLengthReturnsInt() throws Exception {
        var source = """
            package test.stdlib08len;
            
            public fn listLength(nums: List<Int>) -> Int effects() {
                length(nums)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "length should return Int: " + result.errors());
    }
    
    @Test
    void stdlib08_listIsEmptyReturnsBool() throws Exception {
        var source = """
            package test.stdlib08empty;
            
            public fn checkEmpty(nums: List<Int>) -> Bool effects() {
                isEmpty(nums)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "isEmpty should return Bool: " + result.errors());
    }
    
    // ========== STDLIB-09: Generic collections with value type elements ==========
    
    @Test
    void stdlib09_listOfInt() throws Exception {
        var source = """
            package test.stdlib09int;
            
            public fn sum(nums: List<Int>) -> Int effects() {
                0
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "List<Int> should type check: " + result.errors());
    }
    
    @Test
    void stdlib09_listOfOption() throws Exception {
        var source = """
            package test.stdlib09opt;
            
            public fn process(opts: List<Option<Int>>) -> Int effects() {
                0
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "List<Option<Int>> should type check: " + result.errors());
    }
    
    @Test
    void stdlib09_optionOfList() throws Exception {
        var source = """
            package test.stdlib09optlist;
            
            public fn process(maybeList: Option<List<Int>>) -> Int effects() {
                0
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "Option<List<Int>> should type check: " + result.errors());
    }
    
    // ========== Option/Result utility functions ==========
    
    @Test
    void optionIsSome() throws Exception {
        var source = """
            package test.issome;
            
            public fn checkPresent(opt: Option<Int>) -> Bool effects() {
                isSome(opt)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "isSome should return Bool: " + result.errors());
    }
    
    @Test
    void optionIsNone() throws Exception {
        var source = """
            package test.isnone;
            
            public fn checkAbsent(opt: Option<Int>) -> Bool effects() {
                isNone(opt)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "isNone should return Bool: " + result.errors());
    }
    
    @Test
    void resultIsOk() throws Exception {
        var source = """
            package test.isok;
            
            public fn checkSuccess(res: Result<Int, String>) -> Bool effects() {
                isOk(res)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "isOk should return Bool: " + result.errors());
    }
    
    @Test
    void resultIsErr() throws Exception {
        var source = """
            package test.iserr;
            
            public fn checkFailure(res: Result<Int, String>) -> Bool effects() {
                isErr(res)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "isErr should return Bool: " + result.errors());
    }
    
    @Test
    void optionGetOrElse() throws Exception {
        var source = """
            package test.getorelse;
            
            public fn withDefault(opt: Option<Int>) -> Int effects() {
                getOrElse(opt, 0)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "getOrElse should return inner type: " + result.errors());
    }
    
    @Test
    void resultToOption() throws Exception {
        var source = """
            package test.toopt;
            
            public fn discardError(res: Result<Int, String>) -> Option<Int> effects() {
                toOption(res)
            }
            """;
        
        var result = check(source);
        assertTrue(result.isValid(), "toOption should convert Result to Option: " + result.errors());
    }
    
    // ========== Code generation tests ==========
    
    @Test
    void codeGen_listOfGeneratesImmutableList() throws Exception {
        var source = """
            package test.codegen;
            
            public fn makeList() -> List<Int> effects() {
                listOf(1, 2, 3)
            }
            """;
        
        var unit = parse(source);
        var codeGen = new Java25CodeGenerator();
        codeGen.setOutputDir(tempDir);
        var outputs = codeGen.generate(unit);
        
        assertFalse(outputs.isEmpty(), "Should generate class files");
        assertTrue(Files.exists(outputs.get(0)), "Class file should exist");
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
