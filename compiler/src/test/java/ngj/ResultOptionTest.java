package ngj;

import ngj.parser.*;
import ngj.typechecker.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class ResultOptionTest {
    
    @Test
    void okWithCorrectArgument() throws Exception {
        var source = """
            module test.ok
            
            pub fn test() -> Result<Int, String> ! {} {
                Ok(42)
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        // Should have no errors (Ok returns Result<Int, _> which is compatible)
        var errors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == ngj.diagnostic.Severity.ERROR)
            .toList();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }
    
    @Test
    void errWithCorrectArgument() throws Exception {
        var source = """
            module test.err
            
            pub fn test() -> Result<Int, String> ! {} {
                Err("error")
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
            module test.some
            
            pub fn test() -> Option<Int> ! {} {
                Some(42)
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
            module test.none
            
            pub fn test() -> Option<Int> ! {} {
                None
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
            module test.prop
            
            pub fn inner() -> Result<Int, String> ! {} {
                Ok(1)
            }
            
            pub fn outer() -> Result<Int, String> ! {} {
                let x = inner()?
                Ok(x + 1)
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
        // Test that ? operator on non-Result/Option type is rejected
        var source = """
            module test.prop
            
            pub fn outer() -> Result<Int, String> ! {} {
                let x: Int = 42
                x?
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
