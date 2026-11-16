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
            package test.mismatch;
            
            public fn test() -> Int effects() {
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
            package test.nonexhaust;
            
            public enum Status { Ok(), Pending(), Error() }
            
            public fn test(s: Status) -> Int effects() {
                match (s) {
                    case Ok() => { 1 },
                    case Pending() => { 2 }
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
            package test.unknown;
            
            public fn test() -> UnknownType effects() {
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
            package test.hole;
            
            public fn calculate() -> Int effects() {
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
            package test.effect;
            
            public fn read() -> String effects(FsRead) {
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
            package test.arith;
            
            public fn test() -> Int effects() {
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
            package test.logic;
            
            public fn test() -> Bool effects() {
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
