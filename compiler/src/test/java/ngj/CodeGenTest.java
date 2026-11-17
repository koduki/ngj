package ngj;

import ngj.codegen.*;
import ngj.parser.*;
import ngj.typechecker.*;
import ngj.ast.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CodeGenTest {
    
    private Path tempDir;
    
    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ngj-codegen-test");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }
    
    @Test
    void generateSimpleFunction() throws Exception {
        var source = """
            package test.simple;
            
            public fn answer() -> Int effects() {
                42
            }
            """;
        
        var classBytes = compileAndGenerate(source);
        assertNotNull(classBytes, "Should generate class bytes");
        assertTrue(classBytes.length > 0, "Class bytes should not be empty");
        
        var clazz = loadClass("test/simple/answer$Fn", classBytes);
        assertNotNull(clazz, "Should load generated class");
        
        var method = clazz.getMethod("invoke");
        assertNotNull(method, "Should have invoke method");
        
        var result = method.invoke(null);
        assertEquals(42, result, "invoke() should return 42");
    }
    
    @Test
    void generateArithmetic() throws Exception {
        var source = """
            package test.arith;
            
            public fn addNumbers(a: Int, b: Int) -> Int effects() {
                a + b
            }
            """;
        
        var classBytes = compileAndGenerate(source);
        var clazz = loadClass("test/arith/addNumbers$Fn", classBytes);
        var method = clazz.getMethod("invoke", int.class, int.class);
        
        var result = method.invoke(null, 10, 32);
        assertEquals(42, result, "10 + 32 should equal 42");
    }
    
    @Test
    void generateRecordClass() throws Exception {
        var source = """
            package test.point;
            
            public record Point(x: Int, y: Int);
            """;
        
        var classBytes = compileAndGenerateRecord(source, "Point");
        assertNotNull(classBytes, "Should generate class bytes for record type");
        
        var clazz = loadClass("test/point/Point", classBytes);
        assertNotNull(clazz, "Should load generated Point class");
        
        var ctor = clazz.getConstructor(int.class, int.class);
        assertNotNull(ctor, "Should have constructor with two int params");
        
        var instance = ctor.newInstance(3, 4);
        var xField = clazz.getField("x");
        var yField = clazz.getField("y");
        
        assertEquals(3, xField.get(instance));
        assertEquals(4, yField.get(instance));
    }
    
    @Test
    void generateIfExpression() throws Exception {
        var source = """
            package test.cond;
            
            public fn max(a: Int, b: Int) -> Int effects() {
                if (a > b) { a } else { b }
            }
            """;
        
        var classBytes = compileAndGenerate(source);
        var clazz = loadClass("test/cond/max$Fn", classBytes);
        var method = clazz.getMethod("invoke", int.class, int.class);
        
        assertEquals(5, method.invoke(null, 5, 3));
        assertEquals(10, method.invoke(null, 3, 10));
        assertEquals(7, method.invoke(null, 7, 7));
    }
    
    @Test
    void integerOverflowThrowsArithmeticException() throws Exception {
        var source = """
            package test.overflow;
            
            public fn addOverflow() -> Int effects() {
                2147483647 + 1
            }
            """;
        
        var classBytes = compileAndGenerate(source);
        var clazz = loadClass("test/overflow/addOverflow$Fn", classBytes);
        var method = clazz.getMethod("invoke");
        
        var ex = assertThrows(InvocationTargetException.class, () -> method.invoke(null),
            "Integer overflow should throw exception");
        assertTrue(ex.getCause() instanceof ArithmeticException,
            "Cause should be ArithmeticException but was: " + ex.getCause());
    }
    
    @Test
    void shortCircuitAnd() throws Exception {
        var source = """
            package test.shortcircuit;
            
            public fn andTest(a: Bool, b: Bool) -> Bool effects() {
                a && b
            }
            """;
        
        var classBytes = compileAndGenerate(source);
        var clazz = loadClass("test/shortcircuit/andTest$Fn", classBytes);
        var method = clazz.getMethod("invoke", boolean.class, boolean.class);
        
        assertEquals(false, method.invoke(null, false, false));
        assertEquals(false, method.invoke(null, false, true));
        assertEquals(false, method.invoke(null, true, false));
        assertEquals(true, method.invoke(null, true, true));
    }
    
    @Test
    void shortCircuitOr() throws Exception {
        var source = """
            package test.shortcircuit;
            
            public fn orTest(a: Bool, b: Bool) -> Bool effects() {
                a || b
            }
            """;
        
        var classBytes = compileAndGenerate(source);
        var clazz = loadClass("test/shortcircuit/orTest$Fn", classBytes);
        var method = clazz.getMethod("invoke", boolean.class, boolean.class);
        
        assertEquals(false, method.invoke(null, false, false));
        assertEquals(true, method.invoke(null, false, true));
        assertEquals(true, method.invoke(null, true, false));
        assertEquals(true, method.invoke(null, true, true));
    }
    
    @Test
    void negation() throws Exception {
        var source = """
            package test.neg;
            
            public fn negate(n: Int) -> Int effects() {
                -n
            }
            """;
        
        var classBytes = compileAndGenerate(source);
        var clazz = loadClass("test/neg/negate$Fn", classBytes);
        var method = clazz.getMethod("invoke", int.class);
        
        assertEquals(-5, method.invoke(null, 5));
        assertEquals(10, method.invoke(null, -10));
        assertEquals(0, method.invoke(null, 0));
    }
    
    private byte[] compileAndGenerate(String source) throws Exception {
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var parseErrors = parser.getDiagnostics();
        if (!parseErrors.isEmpty()) {
            throw new ngj.parser.ParseException(parseErrors);
        }
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        if (!resolver.getDiagnostics().isEmpty()) {
            throw new RuntimeException("Resolution errors: " + resolver.getDiagnostics());
        }
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        var typeErrors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == ngj.diagnostic.Severity.ERROR && 
                        d.certainty() != ngj.diagnostic.Certainty.INCOMPLETE)
            .toList();
        if (!typeErrors.isEmpty()) {
            throw new RuntimeException("Type errors: " + typeErrors);
        }
        
        var generator = new Java25CodeGenerator();
        generator.setOutputDir(tempDir);
        var outputs = generator.generate(unit);
        
        if (outputs.isEmpty()) {
            return null;
        }
        
        return Files.readAllBytes(outputs.get(0));
    }
    
    private byte[] compileAndGenerateRecord(String source, String typeName) throws Exception {
        var lexer = new Lexer(source, "test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var generator = new Java25CodeGenerator();
        generator.setOutputDir(tempDir);
        var outputs = generator.generate(unit);
        
        for (var output : outputs) {
            if (output.toString().contains(typeName)) {
                return Files.readAllBytes(output);
            }
        }
        
        return outputs.isEmpty() ? null : Files.readAllBytes(outputs.get(0));
    }
    
    private Class<?> loadClass(String className, byte[] bytecode) throws Exception {
        var loader = new ByteArrayClassLoader(className.replace('/', '.'), bytecode);
        return loader.loadClass(className.replace('/', '.'));
    }
    
    private static class ByteArrayClassLoader extends ClassLoader {
        private final String className;
        private final byte[] bytecode;
        
        ByteArrayClassLoader(String className, byte[] bytecode) {
            this.className = className;
            this.bytecode = bytecode;
        }
        
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(className)) {
                return defineClass(name, bytecode, 0, bytecode.length);
            }
            return super.findClass(name);
        }
    }
}
