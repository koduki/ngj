package ngj;

import ngj.parser.*;
import ngj.typechecker.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class ResultOptionTest {
    
    @Test
    void okWithCorrectArgument() throws Exception {
        var source = """
            package test.ok;
            
            public fn test() -> Result<Int, String> effects() {
                return Ok(42);
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var errors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == ngj.diagnostic.Severity.ERROR)
            .toList();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }
    
    @Test
    void errWithCorrectArgument() throws Exception {
        var source = """
            package test.err;
            
            public fn test() -> Result<Int, String> effects() {
                return Err("error");
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var errors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == ngj.diagnostic.Severity.ERROR)
            .toList();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }
    
    @Test
    void someWithCorrectArgument() throws Exception {
        var source = """
            package test.some;
            
            public fn test() -> Option<Int> effects() {
                return Some(42);
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var errors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == ngj.diagnostic.Severity.ERROR)
            .toList();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }
    
    @Test
    void noneWithNoArguments() throws Exception {
        var source = """
            package test.none;
            
            public fn test() -> Option<Int> effects() {
                return None();
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var errors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == ngj.diagnostic.Severity.ERROR)
            .toList();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }
    
    @Test
    void propagateWithSameErrorType() throws Exception {
        var source = """
            package test.prop;
            
            public fn inner() -> Result<Int, String> effects() {
                return Ok(1);
            }
            
            public fn outer() -> Result<Int, String> effects() {
                val x = inner()?;
                return Ok(x + 1);
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var propErrors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.id().equals("NGJ-PROP-001"))
            .toList();
        assertTrue(propErrors.isEmpty(), "Should allow propagate with same error type");
    }
    
    @Test
    void propagateOnNonResultType() throws Exception {
        var source = """
            package test.prop;
            
            public fn outer() -> Result<Int, String> effects() {
                val x: Int = 42;
                return x?;
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var propErrors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.id().equals("NGJ-TYPE-013"))
            .toList();
        assertFalse(propErrors.isEmpty(), "Should reject ? on non-Result/Option type");
    }
}
