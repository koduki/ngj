package ngj;

import ngj.parser.*;
import ngj.ast.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {
    
    @Test
    void parseSimpleFunction() throws Exception {
        var source = """
            package test.simple;
            
            public fn main() -> Int effects() {
                return 42;
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
        assertEquals(1, fn.body().statements().size());
        assertInstanceOf(ReturnStmt.class, fn.body().statements().get(0));
    }
    
    @Test
    void parseRecordDeclaration() throws Exception {
        var source = """
            package test.data;
            
            public record Point(x: Int, y: Int);
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        assertEquals(1, unit.declarations().size());
        var record = (RecordDecl) unit.declarations().get(0);
        assertEquals("Point", record.name());
        assertEquals(2, record.params().size());
        assertEquals("x", record.params().get(0).name());
        assertEquals("y", record.params().get(1).name());
    }
    
    @Test
    void parseEnumDeclaration() throws Exception {
        var source = """
            package test.enumeration;
            
            public enum Color {
                Red(),
                Green(),
                Blue()
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
            package test.imports;
            
            import std.io.read;
            import std.io.write;
            
            public fn test() -> Int effects() { return 0; }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        assertEquals(2, unit.imports().size());
        assertEquals("std.io", unit.imports().get(0).modulePath());
        assertEquals("read", unit.imports().get(0).names().get(0));
    }
    
    @Test
    void parseHole() throws Exception {
        var source = """
            package test.hole;
            
            public fn calculate() -> Int effects() {
                return ?todo;
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var fn = (FnDecl) unit.declarations().get(0);
        var retStmt = (ReturnStmt) fn.body().statements().get(0);
        var hole = (HoleExpr) retStmt.value();
        assertEquals("todo", hole.name());
    }
    
    @Test
    void parseIfExpression() throws Exception {
        var source = """
            package test.cond;
            
            public fn max(a: Int, b: Int) -> Int effects() {
                return if (a > b) { a } else { b };
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var fn = (FnDecl) unit.declarations().get(0);
        var retStmt = (ReturnStmt) fn.body().statements().get(0);
        var ifExpr = (IfExpr) retStmt.value();
        assertNotNull(ifExpr.condition());
        assertNotNull(ifExpr.thenBranch());
        assertNotNull(ifExpr.elseBranch());
    }
    
    @Test
    void parseMatchExpression() throws Exception {
        var source = """
            package test.match;
            
            public enum Status { Ok(), Err() }
            
            public fn test(s: Status) -> Int effects() {
                return match (s) {
                    case Ok() => { 1 },
                    case Err() => { 0 }
                };
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var fn = (FnDecl) unit.declarations().get(1);
        var retStmt = (ReturnStmt) fn.body().statements().get(0);
        var matchExpr = (MatchExpr) retStmt.value();
        assertEquals(2, matchExpr.arms().size());
    }
    
    @Test
    void parseValStatement() throws Exception {
        var source = """
            package test.binding;
            
            public fn test() -> Int effects() {
                val x: Int = 42;
                return x;
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var fn = (FnDecl) unit.declarations().get(0);
        assertEquals(2, fn.body().statements().size());
        var valStmt = (ValStmt) fn.body().statements().get(0);
        assertEquals("x", valStmt.name());
    }
    
    @Test
    void parseVarAndAssignment() throws Exception {
        var source = """
            package test.mutable;
            
            public fn test() -> Int effects() {
                var x: Int = 0;
                x = 42;
                return x;
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var fn = (FnDecl) unit.declarations().get(0);
        assertEquals(3, fn.body().statements().size());
        var varStmt = (VarStmt) fn.body().statements().get(0);
        assertEquals("x", varStmt.name());
        var assignStmt = (AssignStmt) fn.body().statements().get(1);
        assertEquals("x", assignStmt.name());
    }
    
    @Test
    void rejectWildcardImport() throws Exception {
        var source = """
            package test.invalid;
            
            import std.*;
            
            public fn test() -> Int effects() { 0 }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var diagnostics = parser.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-PARSE-001")));
    }
    
    @Test
    void detectLegacyModuleSyntax() throws Exception {
        var source = """
            module test.legacy;
            
            pub fn test() -> Int effects() { 0 }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        try {
            parser.parse();
        } catch (ParseException e) {
        }
        
        var diagnostics = parser.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-001") || d.id().equals("NGJ-MIGRATE-003")));
    }
    
    @Test
    void detectLegacyPubSyntax() throws Exception {
        var source = """
            package test.legacy;
            
            pub fn test() -> Int effects() { 0 }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var diagnostics = parser.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-003")));
    }
    
    @Test
    void detectLegacyEffectSyntax() throws Exception {
        var source = """
            package test.legacy;
            
            public fn test() -> Int ! {} { 0 }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var diagnostics = parser.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-004")));
    }
    
    @Test
    void detectLegacyDataSyntax() throws Exception {
        var source = """
            package test.legacy;
            
            public data Point {
                x: Int,
                y: Int
            }
            """;
        
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var diagnostics = parser.getDiagnostics();
        assertTrue(diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-MIGRATE-005")));
    }
}
