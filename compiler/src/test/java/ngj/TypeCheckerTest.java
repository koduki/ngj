package ngj;

import ngj.parser.*;
import ngj.typechecker.*;
import ngj.diagnostic.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class TypeCheckerTest {
    
    private CheckResult check(String source) throws Exception {
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var compiler = new Compiler();
        return compiler.check(java.util.List.of());
    }
    
    @Test
    void detectTypeMismatch() throws Exception {
        var source = """
            module test.mismatch
            
            pub fn test() -> Int ! {} {
                "hello"
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var diagnostics = typeChecker.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-TYPE-001")));
    }
    
    @Test
    void detectNonExhaustiveMatch() throws Exception {
        var source = """
            module test.nonexhaust
            
            pub enum Status { Ok, Pending, Error }
            
            pub fn test(s: Status) -> Int ! {} {
                match s {
                    Ok => 1,
                    Pending => 2
                }
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var diagnostics = typeChecker.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-MATCH-001")));
    }
    
    @Test
    void detectUnknownType() throws Exception {
        var source = """
            module test.unknown
            
            pub fn test() -> UnknownType ! {} {
                42
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var diagnostics = resolver.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-NAME-001")));
    }
    
    @Test
    void detectHoleAsIncomplete() throws Exception {
        var source = """
            module test.hole
            
            pub fn calculate() -> Int ! {} {
                ?todo
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var diagnostics = typeChecker.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-HOLE-001")));
    }
    
    @Test
    void rejectNonEmptyEffectBound() throws Exception {
        var source = """
            module test.effect
            
            pub fn read() -> String ! { FsRead } {
                "content"
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var diagnostics = typeChecker.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-EFFECT-001")));
    }
    
    @Test
    void detectArithmeticTypeMismatch() throws Exception {
        var source = """
            module test.arith
            
            pub fn test() -> Int ! {} {
                1 + "hello"
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var diagnostics = typeChecker.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().startsWith("NGJ-TYPE-")));
    }
    
    @Test
    void detectLogicTypeMismatch() throws Exception {
        var source = """
            module test.logic
            
            pub fn test() -> Bool ! {} {
                1 && true
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var diagnostics = typeChecker.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-TYPE-007")));
    }
}
