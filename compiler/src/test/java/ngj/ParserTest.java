package ngj;

import ngj.parser.*;
import ngj.ast.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {
    
    @Test
    void parseSimpleFunction() throws Exception {
        var source = """
            module test.simple
            
            pub fn main() -> Int ! {} {
                42
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        assertEquals("test.simple", unit.modulePath());
        assertEquals(1, unit.declarations().size());
        
        var fn = (FnDecl) unit.declarations().get(0);
        assertEquals("main", fn.name());
        assertTrue(fn.isPublic());
        assertEquals(0, fn.params().size());
        assertTrue(fn.effects().isEmpty());
    }
    
    @Test
    void parseDataDeclaration() throws Exception {
        var source = """
            module test.data
            
            pub data Point {
                x: Int,
                y: Int
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        assertEquals(1, unit.declarations().size());
        var data = (DataDecl) unit.declarations().get(0);
        assertEquals("Point", data.name());
        assertEquals(2, data.fields().size());
        assertEquals("x", data.fields().get(0).name());
        assertEquals("y", data.fields().get(1).name());
    }
    
    @Test
    void parseEnumDeclaration() throws Exception {
        var source = """
            module test.enum
            
            pub enum Color {
                Red,
                Green,
                Blue
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        assertEquals(1, unit.declarations().size());
        var enumDecl = (EnumDecl) unit.declarations().get(0);
        assertEquals("Color", enumDecl.name());
        assertEquals(3, enumDecl.variants().size());
        assertEquals("Red", enumDecl.variants().get(0).name());
    }
    
    @Test
    void parseImports() throws Exception {
        var source = """
            module test.imports
            
            import std.io.{read, write}
            
            pub fn test() -> Int ! {} { 0 }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        assertEquals(1, unit.imports().size());
        var imp = unit.imports().get(0);
        assertEquals("std.io", imp.modulePath());
        assertEquals(2, imp.names().size());
        assertFalse(imp.isWildcard());
    }
    
    @Test
    void parseHole() throws Exception {
        var source = """
            module test.hole
            
            pub fn calculate() -> Int ! {} {
                ?todo
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var fn = (FnDecl) unit.declarations().get(0);
        var hole = (HoleExpr) fn.body().tailExpr();
        assertEquals("todo", hole.name());
    }
    
    @Test
    void parseIfExpression() throws Exception {
        var source = """
            module test.cond
            
            pub fn max(a: Int, b: Int) -> Int ! {} {
                if a > b { a } else { b }
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var fn = (FnDecl) unit.declarations().get(0);
        var ifExpr = (IfExpr) fn.body().tailExpr();
        assertNotNull(ifExpr.condition());
        assertNotNull(ifExpr.thenBranch());
        assertNotNull(ifExpr.elseBranch());
    }
    
    @Test
    void parseMatchExpression() throws Exception {
        var source = """
            module test.match
            
            pub enum Status { Ok, Err }
            
            pub fn test(s: Status) -> Int ! {} {
                match s {
                    Ok => 1,
                    Err => 0
                }
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var fn = (FnDecl) unit.declarations().get(1);
        var matchExpr = (MatchExpr) fn.body().tailExpr();
        assertEquals(2, matchExpr.arms().size());
    }
    
    @Test
    void rejectWildcardImport() throws Exception {
        var source = """
            module test.invalid
            
            import std.*
            
            pub fn test() -> Int ! {} { 0 }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        // Should have diagnostic for wildcard import
        var diagnostics = parser.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-PARSE-001")));
    }
    
    @Test
    void rejectUserGenericTypes() throws Exception {
        var source = """
            module test.invalid
            
            pub data Box<T> {
                value: T
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var diagnostics = parser.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-PARSE-003")));
    }
}
