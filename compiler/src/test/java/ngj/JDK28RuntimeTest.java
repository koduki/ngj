package ngj;

import ngj.codegen.Java25CodeGenerator;
import ngj.diagnostic.Diagnostic;
import ngj.diagnostic.Severity;
import ngj.parser.Lexer;
import ngj.parser.Parser;
import ngj.typechecker.NameResolver;
import ngj.typechecker.TypeChecker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDK 28 Runtime Verification Tests.
 * 
 * This test suite validates that generated class files:
 * 1. Compile successfully (type check)
 * 2. Generate valid class files with correct version (72 for JDK 28)
 * 3. Load correctly on JDK 28 EA with --enable-preview
 * 4. Execute and produce expected results
 * 
 * JDK Requirements:
 * - Build: JDK 25+ (for java.lang.classfile API)
 * - Runtime tests: JDK 28 EA with --enable-preview
 * 
 * Expected Failures (to be fixed in PR 3):
 * See EXPECTED_FAILURES constant and PR description table.
 */
class JDK28RuntimeTest {
    
    private static final String FIXTURES_BASE = "fixtures";
    private static final int JDK_28_CLASS_VERSION = 72;
    private static final int REQUIRED_JDK_FOR_RUNTIME = 28;
    
    /**
     * Expected failures with reasons (to be fixed in PR 3).
     * 
     * All fixtures should now pass on JDK 28 runtime after PR #16 fixes:
     * - ACC_STRICT flag added to value class fields (JEP 401)
     * - match codegen fixed for enum and Option patterns
     * - for-yield codegen fixed
     * - Option/Result pattern matching fixed
     */
    private static final List<String> EXPECTED_RUNTIME_FAILURES = List.of(
        // All expected failures have been fixed in PR #16
    );
    
    @TempDir
    Path tempDir;
    
    private static int jdkVersion;
    private static int targetClassVersion;
    
    @BeforeAll
    static void checkJDKVersion() {
        jdkVersion = Runtime.version().feature();
        targetClassVersion = Integer.parseInt(
            System.getenv().getOrDefault("NGJ_RUNTIME_CLASS_VERSION", "72"));
        
        System.out.println("========================================");
        System.out.println("JDK28RuntimeTest Configuration");
        System.out.println("========================================");
        System.out.println("Runtime JDK version: " + jdkVersion);
        System.out.println("Target class file version: " + targetClassVersion);
        System.out.println("Expected runtime failures: " + EXPECTED_RUNTIME_FAILURES);
        System.out.println("========================================");
    }
    
    // ========== Positive Fixture Tests ==========
    
    static Stream<String> positiveFixtures() throws IOException {
        return getFixtureFiles("positive").stream();
    }
    
    static Stream<String> positiveFixturesExcludingExpectedFailures() throws IOException {
        return getFixtureFiles("positive").stream()
            .filter(f -> !EXPECTED_RUNTIME_FAILURES.contains(f));
    }
    
    @ParameterizedTest(name = "positive/{0} compile")
    @MethodSource("positiveFixtures")
    void positiveFixture_compilesAndTypeChecks(String fixture) throws Exception {
        String source = readFixture("positive/" + fixture);
        
        var lexer = new Lexer(source, fixture);
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var parseErrors = parser.getDiagnostics().stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        assertTrue(parseErrors.isEmpty(), 
            "Parse errors in " + fixture + ": " + parseErrors);
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        var resolveErrors = resolver.getDiagnostics().stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        assertTrue(resolveErrors.isEmpty(),
            "Name resolution errors in " + fixture + ": " + resolveErrors);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        var typeErrors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        assertTrue(typeErrors.isEmpty(),
            "Type check errors in " + fixture + ": " + typeErrors);
    }
    
    @ParameterizedTest(name = "positive/{0} codegen")
    @MethodSource("positiveFixtures")
    void positiveFixture_generatesValidClassFiles(String fixture) throws Exception {
        String source = readFixture("positive/" + fixture);
        
        var lexer = new Lexer(source, fixture);
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var codeGen = new Java25CodeGenerator();
        codeGen.setOutputDir(tempDir);
        var outputs = codeGen.generate(unit);
        
        assertFalse(outputs.isEmpty(), "Should generate at least one class file");
        
        for (Path classFile : outputs) {
            assertTrue(Files.exists(classFile), "Class file should exist: " + classFile);
            byte[] bytes = Files.readAllBytes(classFile);
            
            assertEquals((byte) 0xCA, bytes[0], "Invalid magic byte 0");
            assertEquals((byte) 0xFE, bytes[1], "Invalid magic byte 1");
            assertEquals((byte) 0xBA, bytes[2], "Invalid magic byte 2");
            assertEquals((byte) 0xBE, bytes[3], "Invalid magic byte 3");
            
            int majorVersion = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
            assertEquals(targetClassVersion, majorVersion,
                "Class file version should be " + targetClassVersion + " for: " + classFile);
        }
    }
    
    @ParameterizedTest(name = "positive/{0} runtime")
    @MethodSource("positiveFixturesExcludingExpectedFailures")
    void positiveFixture_loadsAndExecutesOnJDK28(String fixture) throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME + " (current: " + jdkVersion + ")");
        
        String source = readFixture("positive/" + fixture);
        
        var lexer = new Lexer(source, fixture);
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var codeGen = new Java25CodeGenerator();
        codeGen.setOutputDir(tempDir);
        var outputs = codeGen.generate(unit);
        
        assertFalse(outputs.isEmpty(), "Should generate class files");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            for (Path classFile : outputs) {
                String className = getClassName(classFile, tempDir);
                
                if (className.endsWith("$Fn")) {
                    Class<?> clazz = Class.forName(className, true, loader);
                    assertNotNull(clazz, "Should load class: " + className);
                    
                    verifyFunctionExecution(fixture, loader, clazz, className);
                }
            }
        }
        
        System.out.println("PASSED: " + fixture + " loaded and executed successfully on JDK " + jdkVersion);
    }
    
    private void verifyFunctionExecution(String fixture, URLClassLoader loader, Class<?> clazz, String className) throws Exception {
        Method invokeMethod = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("invoke")) {
                invokeMethod = m;
                break;
            }
        }
        
        if (invokeMethod == null) {
            System.out.println("  No invoke method found in " + className);
            return;
        }
        
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        switch (fixture) {
            case "hello.ngj" -> {
                if (simpleName.equals("main$Fn")) {
                    Object result = invokeMethod.invoke(null);
                    assertEquals(42, result, "main() should return 42");
                    System.out.println("  Verified: main() = 42");
                }
            }
            case "arithmetic.ngj" -> {
                switch (simpleName) {
                    case "add$Fn" -> assertEquals(8, invokeMethod.invoke(null, 3, 5), "add(3, 5)");
                    case "subtract$Fn" -> assertEquals(6, invokeMethod.invoke(null, 10, 4), "subtract(10, 4)");
                    case "multiply$Fn" -> assertEquals(42, invokeMethod.invoke(null, 6, 7), "multiply(6, 7)");
                    case "divide$Fn" -> assertEquals(5, invokeMethod.invoke(null, 20, 4), "divide(20, 4)");
                    case "modulo$Fn" -> assertEquals(2, invokeMethod.invoke(null, 17, 5), "modulo(17, 5)");
                    default -> fail("Unexpected function in arithmetic.ngj: " + className);
                }
            }
            case "logic.ngj" -> {
                switch (simpleName) {
                    case "andTest$Fn" -> {
                        assertEquals(true, invokeMethod.invoke(null, true, true));
                        assertEquals(false, invokeMethod.invoke(null, true, false));
                    }
                    case "orTest$Fn" -> {
                        assertEquals(true, invokeMethod.invoke(null, false, true));
                        assertEquals(false, invokeMethod.invoke(null, false, false));
                    }
                    case "notTest$Fn" -> {
                        assertEquals(false, invokeMethod.invoke(null, true));
                        assertEquals(true, invokeMethod.invoke(null, false));
                    }
                    case "complex$Fn" -> {
                        assertEquals(true, invokeMethod.invoke(null, true, true, false));
                        assertEquals(false, invokeMethod.invoke(null, false, true, true));
                    }
                    default -> fail("Unexpected function in logic.ngj: " + className);
                }
            }
            case "control_flow.ngj" -> {
                switch (simpleName) {
                    case "max$Fn" -> {
                        assertEquals(7, invokeMethod.invoke(null, 3, 7));
                        assertEquals(9, invokeMethod.invoke(null, 9, 2));
                    }
                    case "abs$Fn" -> {
                        assertEquals(5, invokeMethod.invoke(null, -5));
                        assertEquals(5, invokeMethod.invoke(null, 5));
                    }
                    default -> fail("Unexpected function in control_flow.ngj: " + className);
                }
            }
            case "data_type.ngj" -> {
                if (simpleName.equals("createPoint$Fn")) {
                    Object point = invokeMethod.invoke(null, 3, 4);
                    assertNotNull(point);
                    assertEquals(3, point.getClass().getField("x").get(point));
                    assertEquals(4, point.getClass().getField("y").get(point));
                } else if (simpleName.equals("getX$Fn")) {
                    Class<?> pointClass = Class.forName("test.data.Point", true, loader);
                    Object point = pointClass.getConstructor(int.class, int.class).newInstance(11, 22);
                    assertEquals(11, invokeMethod.invoke(null, point));
                } else {
                    fail("Unexpected function in data_type.ngj: " + className);
                }
            }
            case "enum_match.ngj" -> {
                if (simpleName.equals("colorToInt$Fn")) {
                    assertEquals(0, invokeMethod.invoke(null, getEnumVariant(loader, "test.enumeration.Color$Red")));
                    assertEquals(1, invokeMethod.invoke(null, getEnumVariant(loader, "test.enumeration.Color$Green")));
                    assertEquals(2, invokeMethod.invoke(null, getEnumVariant(loader, "test.enumeration.Color$Blue")));
                } else {
                    fail("Unexpected function in enum_match.ngj: " + className);
                }
            }
            case "option_match.ngj" -> {
                if (simpleName.equals("unwrapOrDefault$Fn")) {
                    Class<?> someClass = Class.forName("ngj.runtime.Option$Some", true, loader);
                    Class<?> noneClass = Class.forName("ngj.runtime.Option$None", true, loader);
                    Object some42 = someClass.getConstructor(Object.class).newInstance(42);
                    Object none = noneClass.getConstructor().newInstance();
                    assertEquals(42, invokeMethod.invoke(null, some42, 0));
                    assertEquals(99, invokeMethod.invoke(null, none, 99));
                } else {
                    fail("Unexpected function in option_match.ngj: " + className);
                }
            }
            case "result_option.ngj" -> {
                if (simpleName.equals("safeDivide$Fn")) {
                    Object okRes = invokeMethod.invoke(null, 10, 2);
                    assertEquals("ngj.runtime.Result$Ok", okRes.getClass().getName());
                    assertEquals(5, okRes.getClass().getField("value").get(okRes));
                    Object errRes = invokeMethod.invoke(null, 10, 0);
                    assertEquals("ngj.runtime.Result$Err", errRes.getClass().getName());
                    Object divErr = errRes.getClass().getField("error").get(errRes);
                    assertEquals("test.result.DivError", divErr.getClass().getName());
                    assertEquals("Division by zero", divErr.getClass().getField("message").get(divErr));
                } else if (simpleName.equals("doubleOrZero$Fn")) {
                    Class<?> someClass = Class.forName("ngj.runtime.Option$Some", true, loader);
                    Class<?> noneClass = Class.forName("ngj.runtime.Option$None", true, loader);
                    assertEquals(42, invokeMethod.invoke(null, someClass.getConstructor(Object.class).newInstance(21)));
                    assertEquals(0, invokeMethod.invoke(null, noneClass.getConstructor().newInstance()));
                } else {
                    fail("Unexpected function in result_option.ngj: " + className);
                }
            }
            case "for_yield.ngj" -> {
                if (simpleName.equals("doubleAll$Fn")) {
                    @SuppressWarnings("unchecked")
                    List<Integer> res = (List<Integer>) invokeMethod.invoke(null, List.of(1, 2, 3));
                    assertEquals(List.of(2, 4, 6), res);
                    assertThrows(UnsupportedOperationException.class, () -> res.add(99));
                } else {
                    fail("Unexpected function in for_yield.ngj: " + className);
                }
            }
            case "listof_call.ngj" -> {
                if (simpleName.equals("makeList$Fn")) {
                    @SuppressWarnings("unchecked")
                    List<Integer> res = (List<Integer>) invokeMethod.invoke(null);
                    assertEquals(List.of(1, 2, 3), res);
                    assertThrows(UnsupportedOperationException.class, () -> res.add(99));
                } else {
                    fail("Unexpected function in listof_call.ngj: " + className);
                }
            }
            default -> {
                if (invokeMethod.getParameterCount() == 0) {
                    Object result = invokeMethod.invoke(null);
                    assertNotNull(result, "invoke() should return a value");
                    System.out.println("  Executed: " + className + ".invoke() = " + result);
                }
            }
        }
    }
    
    // ========== Negative Fixture Tests ==========
    
    static Stream<String> negativeFixtures() throws IOException {
        return getFixtureFiles("negative").stream();
    }
    
    @ParameterizedTest(name = "negative/{0}")
    @MethodSource("negativeFixtures")
    void negativeFixture_failsWithExpectedDiagnostic(String fixture) throws Exception {
        String source = readFixture("negative/" + fixture);
        
        List<Diagnostic> allDiagnostics = new ArrayList<>();
        boolean parseExceptionThrown = false;
        
        try {
            var lexer = new Lexer(source, fixture);
            var parser = new Parser(lexer);
            var unit = parser.parse();
            allDiagnostics.addAll(parser.getDiagnostics());
            
            var resolver = new NameResolver();
            resolver.resolve(unit);
            allDiagnostics.addAll(resolver.getDiagnostics());
            
            var typeChecker = new TypeChecker();
            typeChecker.check(unit);
            allDiagnostics.addAll(typeChecker.getDiagnostics());
        } catch (ngj.parser.ParseException e) {
            parseExceptionThrown = true;
        }
        
        var errors = allDiagnostics.stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        
        switch (fixture) {
            case "non_exhaustive_match.ngj" -> {
                assertTrue(errors.stream().anyMatch(e -> e.id().equals("NGJ-MATCH-001")),
                    "Expected NGJ-MATCH-001 for non-exhaustive match: " + errors);
            }
            case "unknown_type.ngj" -> {
                assertTrue(errors.stream().anyMatch(e -> 
                    e.id().equals("NGJ-TYPE-001") || e.id().equals("NGJ-RESOLVE-001")),
                    "Expected NGJ-TYPE-001 or NGJ-RESOLVE-001 for unknown type: " + errors);
            }
            case "effect_bound.ngj" -> {
                assertTrue(errors.stream().anyMatch(e -> 
                    e.id().contains("EFFECT") || e.id().contains("PARSE")),
                    "Expected effect-related error: " + errors);
            }
            case "wildcard_import.ngj" -> {
                assertTrue(
                    errors.stream().anyMatch(e -> 
                        e.id().equals("NGJ-IMPORT-001") || 
                        e.id().contains("IMPORT") ||
                        e.id().contains("PARSE")) ||
                    parseExceptionThrown,
                    "Expected import-related error or parse error for wildcard import: " + errors);
            }
            case "user_generics.ngj" -> {
                assertTrue(
                    !errors.isEmpty() || parseExceptionThrown,
                    "Expected errors or parse exception for user-defined generics");
            }
            default -> {
                assertTrue(
                    !errors.isEmpty() || parseExceptionThrown,
                    "Expected errors for negative fixture " + fixture + " but got none");
            }
        }
    }
    
    // ========== Runtime Verification Tests ==========
    
    @Test
    void runtimeVerify_dataType_loadsSuccessfully() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = readFixture("positive/data_type.ngj");
        
        var lexer = new Lexer(source, "data_type.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var codeGen = new Java25CodeGenerator();
        codeGen.setOutputDir(tempDir);
        var outputs = codeGen.generate(unit);
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            for (Path classFile : outputs) {
                String className = getClassName(classFile, tempDir);
                Class<?> clazz = Class.forName(className, true, loader);
                assertNotNull(clazz, "Class should load successfully: " + className);
            }
        }
        System.out.println("SUCCESS: data_type.ngj loads successfully on JDK " + jdkVersion);
    }
    
    @Test
    void runtimeVerify_enumMatch_returnsCorrectResults() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = readFixture("positive/enum_match.ngj");
        
        var lexer = new Lexer(source, "enum_match.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var codeGen = new Java25CodeGenerator();
        codeGen.setOutputDir(tempDir);
        var outputs = codeGen.generate(unit);
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> colorToIntFn = null;
            
            for (Path classFile : outputs) {
                String className = getClassName(classFile, tempDir);
                Class<?> clazz = Class.forName(className, true, loader);
                if (className.contains("colorToInt")) {
                    colorToIntFn = clazz;
                }
            }
            
            assertNotNull(colorToIntFn, "colorToInt function class should be loaded");
            
            Method invoke = null;
            for (Method m : colorToIntFn.getDeclaredMethods()) {
                if (m.getName().equals("invoke")) {
                    invoke = m;
                    break;
                }
            }
            
            assertNotNull(invoke, "invoke method should exist");
            
            // Enum variants are generated as nested classes: EnumName$VariantName
            Object red = getEnumVariant(loader, "test.enumeration.Color$Red");
            Object green = getEnumVariant(loader, "test.enumeration.Color$Green");
            Object blue = getEnumVariant(loader, "test.enumeration.Color$Blue");
            
            assertNotNull(red, "Red variant should be instantiable");
            assertNotNull(green, "Green variant should be instantiable");
            assertNotNull(blue, "Blue variant should be instantiable");
            
            Object redResult = invoke.invoke(null, red);
            Object greenResult = invoke.invoke(null, green);
            Object blueResult = invoke.invoke(null, blue);
            
            System.out.println("enum_match.ngj results:");
            System.out.println("  colorToInt(Red) = " + redResult + " (expected 0)");
            System.out.println("  colorToInt(Green) = " + greenResult + " (expected 1)");
            System.out.println("  colorToInt(Blue) = " + blueResult + " (expected 2)");
            
            assertEquals(0, redResult, "colorToInt(Red) should return 0");
            assertEquals(1, greenResult, "colorToInt(Green) should return 1");
            assertEquals(2, blueResult, "colorToInt(Blue) should return 2");
        }
        System.out.println("SUCCESS: enum_match.ngj works correctly on JDK " + jdkVersion);
    }
    
    @Test
    void bareNone_isRejectedByTypeChecker() throws Exception {
        // Test that bare `return None;` is rejected by the type checker
        // NGJ-TYPE-022: 'None' must be called with parentheses: None()
        
        String source = """
            package test.none;
            
            public fn getNone() -> Option<Int> effects() {
                return None;
            }
            """;
        
        var lexer = new Lexer(source, "bare_none.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var parseErrors = parser.getDiagnostics().stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var typeErrors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        
        // Bare None should be rejected with NGJ-TYPE-022
        assertFalse(typeErrors.isEmpty(), "bare 'return None;' should produce type error");
        
        var noneError = typeErrors.stream()
            .filter(d -> d.id().equals("NGJ-TYPE-022"))
            .findFirst();
        
        assertTrue(noneError.isPresent(), 
            "Expected NGJ-TYPE-022 error for bare None, got: " + typeErrors);
        assertTrue(noneError.get().message().contains("None"),
            "Error message should mention None");
        
        System.out.println("SUCCESS: bare 'return None;' correctly rejected with " + noneError.get().id());
    }
    
    @Test
    void targetOption_producesDifferentOutput() throws Exception {
        // Test that --target option produces different outputs for different targets
        
        String source = """
            package test.target;
            
            public record Pair(a: Int, b: Int);
            
            public enum Op {
                Add(),
                Zero()
            }
            
            public fn evaluate(op: Op, p: Pair) -> Result<Int, String> effects() {
                return match (op) {
                    case Add() => { Ok(p.a + p.b) },
                    case Zero() => { Err("zero") }
                };
            }
            
            public fn answer() -> Int effects() {
                val r = evaluate(Add(), Pair(20, 22));
                return match (r) {
                    case Ok(v) => { v },
                    case Err(_) => { -1 }
                };
            }
            """;
        
        var lexer = new Lexer(source, "target_test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        assertTrue(parser.getDiagnostics().stream().noneMatch(d -> d.severity() == Severity.ERROR),
            "Unexpected parse errors: " + parser.getDiagnostics());
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        assertTrue(resolver.getDiagnostics().stream().noneMatch(d -> d.severity() == Severity.ERROR),
            "Unexpected resolve errors: " + resolver.getDiagnostics());
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        assertTrue(typeChecker.getDiagnostics().stream().noneMatch(d -> d.severity() == Severity.ERROR),
            "Unexpected type errors: " + typeChecker.getDiagnostics());
        
        // Generate with valhalla target
        var codeGen1 = new Java25CodeGenerator();
        codeGen1.setTarget("valhalla");
        Path dir1 = tempDir.resolve("valhalla");
        Files.createDirectories(dir1);
        codeGen1.setOutputDir(dir1);
        var outputs1 = codeGen1.generate(unit);
        
        // Generate with java25 target
        var codeGen2 = new Java25CodeGenerator();
        codeGen2.setTarget("java25");
        Path dir2 = tempDir.resolve("java25");
        Files.createDirectories(dir2);
        codeGen2.setOutputDir(dir2);
        var outputs2 = codeGen2.generate(unit);
        
        // Verify class file major/minor versions for both targets
        for (Path cf : outputs2) {
            byte[] bytes = Files.readAllBytes(cf);
            int minor = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
            int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
            assertEquals(69, major, "java25 target major version should be 69 for " + cf);
            assertEquals(0, minor, "java25 target minor version should be 0 for " + cf);
        }
        for (Path cf : outputs1) {
            byte[] bytes = Files.readAllBytes(cf);
            int minor = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
            int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
            assertEquals(targetClassVersion, major, "valhalla target major version mismatch for " + cf);
            assertEquals(65535, minor, "valhalla target minor version should be 65535 for " + cf);
        }
        
        // Compare outputs - they should be different
        boolean different = false;
        for (int i = 0; i < outputs1.size() && i < outputs2.size(); i++) {
            byte[] bytes1 = Files.readAllBytes(outputs1.get(i));
            byte[] bytes2 = Files.readAllBytes(outputs2.get(i));
            if (!Arrays.equals(bytes1, bytes2)) {
                different = true;
                System.out.println("Target differentiation confirmed:");
                System.out.println("  valhalla: " + bytes1.length + " bytes");
                System.out.println("  java25: " + bytes2.length + " bytes");
                break;
            }
        }
        
        assertTrue(different, "Different targets should produce different output");
        System.out.println("SUCCESS: --target option produces different output for different targets");
        
        // Verify java25 output loads and executes in a fresh JVM WITHOUT --enable-preview
        // Note: build job runs this on JDK 25, test-jdk28 job runs this on JDK 28
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        System.out.println("Executing java25 target on JDK " + jdkVersion + " without --enable-preview via " + javaBin);
        ProcessBuilder pbNoPreview = new ProcessBuilder(javaBin, "-cp", dir2.toString(), "test.target.answer$Fn");
        pbNoPreview.redirectErrorStream(true);
        Process pNoPreview = pbNoPreview.start();
        String outNoPreview = new String(pNoPreview.getInputStream().readAllBytes());
        int exitNoPreview = pNoPreview.waitFor();
        assertEquals(42, exitNoPreview,
            "java25 target should execute without --enable-preview on JDK " + jdkVersion + " and exit 42. Output:\n" + outNoPreview);
        
        // Verify valhalla target output is rejected when launched WITHOUT --enable-preview
        ProcessBuilder pbValhallaNoPreview = new ProcessBuilder(javaBin, "-cp", dir1.toString(), "test.target.answer$Fn");
        pbValhallaNoPreview.redirectErrorStream(true);
        Process pValhallaNoPreview = pbValhallaNoPreview.start();
        String outValhallaNoPreview = new String(pValhallaNoPreview.getInputStream().readAllBytes());
        int exitValhallaNoPreview = pValhallaNoPreview.waitFor();
        assertNotEquals(0, exitValhallaNoPreview,
            "valhalla target should require --enable-preview / JDK 28. Output:\n" + outValhallaNoPreview);
        
        // Also verify java25 output loads via URLClassLoader and returns 42
        URL[] urls = { dir2.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            for (Path classFile : outputs2) {
                String className = getClassName(classFile, dir2);
                try {
                    Class<?> clazz = Class.forName(className, true, loader);
                    System.out.println("  Loaded (java25 target): " + clazz.getName());
                    
                    if (className.equals("test.target.answer$Fn")) {
                        Method invoke = clazz.getMethod("invoke");
                        Object result = invoke.invoke(null);
                        assertEquals(42, result, "answer() should return 42");
                        System.out.println("  Executed answer() = " + result);
                    }
                } catch (ClassFormatError | VerifyError e) {
                    fail("java25 target output should load without --enable-preview: " + e.getMessage());
                }
            }
        }
        System.out.println("SUCCESS: java25 output loads and executes on JDK " + jdkVersion + " without --enable-preview");
    }
    
    private Object getEnumVariant(URLClassLoader loader, String className) {
        try {
            Class<?> clazz = Class.forName(className, true, loader);
            return clazz.getConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }
    
    // ========== Comprehensive Runtime Verification Tests ==========
    
    /**
     * Verify arithmetic.ngj functions return correct values.
     * Tests: add, subtract, multiply, divide, modulo
     */
    @Test
    void runtimeVerify_arithmetic_returnsCorrectResults() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = readFixture("positive/arithmetic.ngj");
        
        var lexer = new Lexer(source, "arithmetic.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var codeGen = new Java25CodeGenerator();
        codeGen.setOutputDir(tempDir);
        var outputs = codeGen.generate(unit);
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            // Load all arithmetic functions
            Class<?> addFn = loadFunction(loader, outputs, "add");
            Class<?> subtractFn = loadFunction(loader, outputs, "subtract");
            Class<?> multiplyFn = loadFunction(loader, outputs, "multiply");
            Class<?> divideFn = loadFunction(loader, outputs, "divide");
            Class<?> moduloFn = loadFunction(loader, outputs, "modulo");
            
            assertNotNull(addFn, "add function should be loaded");
            assertNotNull(subtractFn, "subtract function should be loaded");
            assertNotNull(multiplyFn, "multiply function should be loaded");
            assertNotNull(divideFn, "divide function should be loaded");
            assertNotNull(moduloFn, "modulo function should be loaded");
            
            // Test add(5, 3) = 8
            Method addInvoke = addFn.getMethod("invoke", int.class, int.class);
            assertEquals(8, addInvoke.invoke(null, 5, 3), "add(5, 3) should return 8");
            
            // Test subtract(10, 4) = 6
            Method subInvoke = subtractFn.getMethod("invoke", int.class, int.class);
            assertEquals(6, subInvoke.invoke(null, 10, 4), "subtract(10, 4) should return 6");
            
            // Test multiply(6, 7) = 42
            Method mulInvoke = multiplyFn.getMethod("invoke", int.class, int.class);
            assertEquals(42, mulInvoke.invoke(null, 6, 7), "multiply(6, 7) should return 42");
            
            // Test divide(15, 3) = 5
            Method divInvoke = divideFn.getMethod("invoke", int.class, int.class);
            assertEquals(5, divInvoke.invoke(null, 15, 3), "divide(15, 3) should return 5");
            
            // Test modulo(17, 5) = 2
            Method modInvoke = moduloFn.getMethod("invoke", int.class, int.class);
            assertEquals(2, modInvoke.invoke(null, 17, 5), "modulo(17, 5) should return 2");
            
            System.out.println("SUCCESS: All arithmetic functions return correct results");
        }
    }
    
    /**
     * Verify logic.ngj functions return correct boolean values.
     */
    @Test
    void runtimeVerify_logic_returnsCorrectResults() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = readFixture("positive/logic.ngj");
        var outputs = compileToClasses(source, "logic.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> andFn = loadFunction(loader, outputs, "andTest");
            Class<?> orFn = loadFunction(loader, outputs, "orTest");
            Class<?> notFn = loadFunction(loader, outputs, "notTest");
            Class<?> complexFn = loadFunction(loader, outputs, "complex");
            
            assertNotNull(andFn, "andTest function should be loaded");
            assertNotNull(orFn, "orTest function should be loaded");
            assertNotNull(notFn, "notTest function should be loaded");
            assertNotNull(complexFn, "complex function should be loaded");
            
            Method andInvoke = andFn.getMethod("invoke", boolean.class, boolean.class);
            assertEquals(true, andInvoke.invoke(null, true, true), "andTest(true, true) should return true");
            assertEquals(false, andInvoke.invoke(null, true, false), "andTest(true, false) should return false");
            
            Method orInvoke = orFn.getMethod("invoke", boolean.class, boolean.class);
            assertEquals(true, orInvoke.invoke(null, true, false), "orTest(true, false) should return true");
            assertEquals(false, orInvoke.invoke(null, false, false), "orTest(false, false) should return false");
            
            Method notInvoke = notFn.getMethod("invoke", boolean.class);
            assertEquals(false, notInvoke.invoke(null, true), "notTest(true) should return false");
            assertEquals(true, notInvoke.invoke(null, false), "notTest(false) should return true");
            
            Method complexInvoke = complexFn.getMethod("invoke", boolean.class, boolean.class, boolean.class);
            assertEquals(true, complexInvoke.invoke(null, true, true, false));
            assertEquals(false, complexInvoke.invoke(null, false, true, true));
            
            System.out.println("SUCCESS: All logic functions return correct results");
        }
    }
    
    /**
     * Verify option_match.ngj: unwrapOrDefault with real Option values.
     */
    @Test
    void runtimeVerify_optionMatch_returnsCorrectResults() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = readFixture("positive/option_match.ngj");
        var outputs = compileToClasses(source, "option_match.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> unwrapFn = loadFunction(loader, outputs, "unwrapOrDefault");
            assertNotNull(unwrapFn, "unwrapOrDefault function should be loaded");
            
            Class<?> optionClass = Class.forName("ngj.runtime.Option", true, loader);
            Class<?> someClass = Class.forName("ngj.runtime.Option$Some", true, loader);
            Class<?> noneClass = Class.forName("ngj.runtime.Option$None", true, loader);
            
            Object some42 = someClass.getConstructor(Object.class).newInstance(42);
            Object none = noneClass.getConstructor().newInstance();
            
            Method invoke = unwrapFn.getMethod("invoke", optionClass, int.class);
            Object result1 = invoke.invoke(null, some42, 0);
            assertEquals(42, result1, "unwrapOrDefault(Some(42), 0) should return 42");
            
            Object result2 = invoke.invoke(null, none, 99);
            assertEquals(99, result2, "unwrapOrDefault(None(), 99) should return 99");
            
            System.out.println("SUCCESS: option_match unwrapOrDefault works correctly");
        }
    }
    
    /**
     * Verify result_option.ngj: safeDivide and doubleOrZero with real values.
     */
    @Test
    void runtimeVerify_resultOption_returnsCorrectResults() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = readFixture("positive/result_option.ngj");
        var outputs = compileToClasses(source, "result_option.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> safeDivideFn = loadFunction(loader, outputs, "safeDivide");
            Class<?> doubleOrZeroFn = loadFunction(loader, outputs, "doubleOrZero");
            
            assertNotNull(safeDivideFn, "safeDivide function should be loaded");
            assertNotNull(doubleOrZeroFn, "doubleOrZero function should be loaded");
            
            Method divideInvoke = safeDivideFn.getMethod("invoke", int.class, int.class);
            Object result1 = divideInvoke.invoke(null, 10, 2);
            assertNotNull(result1, "safeDivide(10, 2) should return a Result");
            assertEquals("ngj.runtime.Result$Ok", result1.getClass().getName());
            assertEquals(5, result1.getClass().getField("value").get(result1), "safeDivide(10, 2) Ok.value should be 5");
            
            Object result2 = divideInvoke.invoke(null, 10, 0);
            assertNotNull(result2, "safeDivide(10, 0) should return a Result");
            assertEquals("ngj.runtime.Result$Err", result2.getClass().getName());
            Object errVal = result2.getClass().getField("error").get(result2);
            assertEquals("test.result.DivError", errVal.getClass().getName());
            assertEquals("Division by zero", errVal.getClass().getField("message").get(errVal));
            
            Class<?> optionClass = Class.forName("ngj.runtime.Option", true, loader);
            Class<?> someClass = Class.forName("ngj.runtime.Option$Some", true, loader);
            Class<?> noneClass = Class.forName("ngj.runtime.Option$None", true, loader);
            
            Object some21 = someClass.getConstructor(Object.class).newInstance(21);
            Method doubleInvoke = doubleOrZeroFn.getMethod("invoke", optionClass);
            Object result3 = doubleInvoke.invoke(null, some21);
            assertEquals(42, result3, "doubleOrZero(Some(21)) should return 42");
            
            Object none = noneClass.getConstructor().newInstance();
            Object result4 = doubleInvoke.invoke(null, none);
            assertEquals(0, result4, "doubleOrZero(None()) should return 0");
            
            System.out.println("SUCCESS: result_option functions work correctly");
        }
    }
    
    /**
     * Verify for_yield.ngj: doubleAll returns an immutable transformed list.
     */
    @Test
    void runtimeVerify_forYield_returnsCorrectResults() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = readFixture("positive/for_yield.ngj");
        var outputs = compileToClasses(source, "for_yield.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> doubleAllFn = loadFunction(loader, outputs, "doubleAll");
            assertNotNull(doubleAllFn, "doubleAll function should be loaded");
            
            List<Integer> input = List.of(1, 2, 3);
            Method invoke = doubleAllFn.getMethod("invoke", java.util.List.class);
            @SuppressWarnings("unchecked")
            List<Integer> result = (List<Integer>) invoke.invoke(null, input);
            
            assertNotNull(result, "doubleAll should return a list");
            assertEquals(List.of(2, 4, 6), result);
            assertThrows(UnsupportedOperationException.class, () -> result.add(99),
                "for-yield list must be unmodifiable");
            
            System.out.println("SUCCESS: for_yield doubleAll works correctly");
        }
    }
    
    /**
     * Verify listof_call.ngj: makeList returns correct immutable list.
     */
    @Test
    void runtimeVerify_listOfCall_returnsCorrectResults() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = readFixture("positive/listof_call.ngj");
        var outputs = compileToClasses(source, "listof_call.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> makeListFn = loadFunction(loader, outputs, "makeList");
            assertNotNull(makeListFn, "makeList function should be loaded");
            
            Method invoke = makeListFn.getMethod("invoke");
            @SuppressWarnings("unchecked")
            List<Integer> result = (List<Integer>) invoke.invoke(null);
            
            assertNotNull(result, "makeList should return a list");
            assertEquals(List.of(1, 2, 3), result);
            assertThrows(UnsupportedOperationException.class, () -> result.add(99),
                "listOf result must be unmodifiable");
            
            System.out.println("SUCCESS: listof_call makeList works correctly");
        }
    }
    
    /**
     * Verify if-expression with Ok/Err branches joins correctly.
     */
    @Test
    void runtimeVerify_ifWithResultBranches_works() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = """
            package test.ifresult;
            
            public fn checkPositive(n: Int) -> Result<Int, String> effects() {
                return if (n > 0) { Ok(n) } else { Err("not positive") };
            }
            """;
        
        var outputs = compileToClasses(source, "if_result.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> checkFn = loadFunction(loader, outputs, "checkPositive");
            assertNotNull(checkFn, "checkPositive function should be loaded");
            
            Method invoke = checkFn.getMethod("invoke", int.class);
            
            Object result1 = invoke.invoke(null, 5);
            assertNotNull(result1, "checkPositive(5) should return a Result");
            assertEquals("ngj.runtime.Result$Ok", result1.getClass().getName());
            assertEquals(5, result1.getClass().getField("value").get(result1));
            
            Object result2 = invoke.invoke(null, -3);
            assertNotNull(result2, "checkPositive(-3) should return a Result");
            assertEquals("ngj.runtime.Result$Err", result2.getClass().getName());
            assertEquals("not positive", result2.getClass().getField("error").get(result2));
        }
    }
    
    /**
     * Verify if-expression with Some/None branches joins correctly.
     */
    @Test
    void runtimeVerify_ifWithOptionBranches_works() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = """
            package test.ifoption;
            
            public fn findPositive(n: Int) -> Option<Int> effects() {
                return if (n > 0) { Some(n) } else { None() };
            }
            """;
        
        var outputs = compileToClasses(source, "if_option.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> findFn = loadFunction(loader, outputs, "findPositive");
            assertNotNull(findFn, "findPositive function should be loaded");
            
            Method invoke = findFn.getMethod("invoke", int.class);
            
            Object result1 = invoke.invoke(null, 42);
            assertNotNull(result1, "findPositive(42) should return an Option");
            assertEquals("ngj.runtime.Option$Some", result1.getClass().getName());
            assertEquals(42, result1.getClass().getField("value").get(result1));
            
            Object result2 = invoke.invoke(null, -1);
            assertNotNull(result2, "findPositive(-1) should return an Option");
            assertEquals("ngj.runtime.Option$None", result2.getClass().getName());
        }
    }
    
    /**
     * Verify match joining Ok/Err and Some/None directly and via inferred val bindings.
     */
    @Test
    void runtimeVerify_matchJoiningOkAndErrAndSomeNone_works() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = """
            package test.matchjoin;
            
            public fn matchResultJoin(n: Int) -> Result<Int, String> effects() {
                return match (n) {
                    case 0 => { Err("zero") },
                    case x => { Ok(x + 1) }
                };
            }
            
            public fn matchOptionJoin(n: Int) -> Option<Int> effects() {
                return match (n) {
                    case 0 => { None() },
                    case x => { Some(x * 2) }
                };
            }
            
            public fn ifThenMatchResult(n: Int) -> Int effects() {
                val r = if (n > 0) { Ok(n) } else { Err("neg") };
                return match (r) {
                    case Ok(v) => { v },
                    case Err(_) => { -1 }
                };
            }
            
            public fn matchThenMatchOption(n: Int) -> Int effects() {
                val o = match (n) {
                    case 0 => { None() },
                    case x => { Some(x * 3) }
                };
                return match (o) {
                    case Some(v) => { v },
                    case None() => { -1 }
                };
            }
            """;
        
        var outputs = compileToClasses(source, "match_join.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Method mrj = loadFunction(loader, outputs, "matchResultJoin").getMethod("invoke", int.class);
            Object r0 = mrj.invoke(null, 0);
            assertEquals("ngj.runtime.Result$Err", r0.getClass().getName());
            assertEquals("zero", r0.getClass().getField("error").get(r0));
            Object r5 = mrj.invoke(null, 5);
            assertEquals("ngj.runtime.Result$Ok", r5.getClass().getName());
            assertEquals(6, r5.getClass().getField("value").get(r5));
            
            Method moj = loadFunction(loader, outputs, "matchOptionJoin").getMethod("invoke", int.class);
            Object o0 = moj.invoke(null, 0);
            assertEquals("ngj.runtime.Option$None", o0.getClass().getName());
            Object o7 = moj.invoke(null, 7);
            assertEquals("ngj.runtime.Option$Some", o7.getClass().getName());
            assertEquals(14, o7.getClass().getField("value").get(o7));
            
            Method itmr = loadFunction(loader, outputs, "ifThenMatchResult").getMethod("invoke", int.class);
            assertEquals(10, itmr.invoke(null, 10));
            assertEquals(-1, itmr.invoke(null, -2));
            
            Method mtmo = loadFunction(loader, outputs, "matchThenMatchOption").getMethod("invoke", int.class);
            assertEquals(-1, mtmo.invoke(null, 0));
            assertEquals(21, mtmo.invoke(null, 7));
        }
    }
    
    /**
     * Verify Err(e), Ok(v), Some(v), and getOrElse return typed values cleanly
     * without ClassCastException or Stack content mismatch.
     */
    @Test
    void runtimeVerify_errOkSomeBindings_noClassCastOrVerifyError() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = """
            package test.bindings;
            
            public record CustomErr(code: Int, msg: String);
            public record Item(id: Int, label: String);
            
            public fn returnErrString(r: Result<Int, String>) -> String effects() {
                return match (r) {
                    case Ok(_) => { "ok" },
                    case Err(e) => { e }
                };
            }
            
            public fn returnErrRecord(r: Result<Int, CustomErr>) -> CustomErr effects() {
                return match (r) {
                    case Ok(_) => { CustomErr(0, "ok") },
                    case Err(e) => { e }
                };
            }
            
            public fn extractErrField(r: Result<Int, CustomErr>) -> String effects() {
                return match (r) {
                    case Ok(_) => { "ok" },
                    case Err(e) => { e.msg }
                };
            }
            
            public fn wrapErrMessage(r: Result<Item, String>) -> CustomErr effects() {
                return match (r) {
                    case Ok(item) => { CustomErr(item.id, item.label) },
                    case Err(e) => { CustomErr(500, e) }
                };
            }
            
            public fn returnOkRecord(r: Result<Item, String>) -> Item effects() {
                return match (r) {
                    case Ok(v) => { v },
                    case Err(msg) => { Item(-1, msg) }
                };
            }
            
            public fn returnSomeString(o: Option<String>) -> String effects() {
                return match (o) {
                    case Some(v) => { v },
                    case None() => { "none" }
                };
            }
            
            public fn returnSomeRecord(o: Option<Item>) -> Item effects() {
                return match (o) {
                    case Some(v) => { v },
                    case None() => { Item(0, "none") }
                };
            }
            
            public fn unwrapStrOrElse(o: Option<String>) -> String effects() {
                return getOrElse(o, "fallback");
            }
            
            public fn unwrapNoneIntOrElse() -> Int effects() {
                return getOrElse(None(), 77);
            }
            """;
        
        var outputs = compileToClasses(source, "bindings.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> resultClass = Class.forName("ngj.runtime.Result", true, loader);
            Class<?> okClass = Class.forName("ngj.runtime.Result$Ok", true, loader);
            Class<?> errClass = Class.forName("ngj.runtime.Result$Err", true, loader);
            Class<?> optionClass = Class.forName("ngj.runtime.Option", true, loader);
            Class<?> someClass = Class.forName("ngj.runtime.Option$Some", true, loader);
            Class<?> noneClass = Class.forName("ngj.runtime.Option$None", true, loader);
            Class<?> customErrClass = Class.forName("test.bindings.CustomErr", true, loader);
            Class<?> itemClass = Class.forName("test.bindings.Item", true, loader);
            
            Object ok1 = okClass.getConstructor(Object.class).newInstance(1);
            Object errBoom = errClass.getConstructor(Object.class).newInstance("boom");
            Object customErr = customErrClass.getConstructor(int.class, String.class).newInstance(404, "missing");
            Object errRec = errClass.getConstructor(Object.class).newInstance(customErr);
            Object item = itemClass.getConstructor(int.class, String.class).newInstance(7, "widget");
            Object okItem = okClass.getConstructor(Object.class).newInstance(item);
            Object none = noneClass.getConstructor().newInstance();
            Object someHello = someClass.getConstructor(Object.class).newInstance("hello");
            Object someItem = someClass.getConstructor(Object.class).newInstance(item);
            
            Method retErrStr = loadFunction(loader, outputs, "returnErrString").getMethod("invoke", resultClass);
            assertEquals("ok", retErrStr.invoke(null, ok1));
            assertEquals("boom", retErrStr.invoke(null, errBoom));
            
            Method retErrRec = loadFunction(loader, outputs, "returnErrRecord").getMethod("invoke", resultClass);
            Object outErr = retErrRec.invoke(null, errRec);
            assertEquals("test.bindings.CustomErr", outErr.getClass().getName());
            assertEquals(404, outErr.getClass().getField("code").get(outErr));
            assertEquals("missing", outErr.getClass().getField("msg").get(outErr));
            
            Method extErrField = loadFunction(loader, outputs, "extractErrField").getMethod("invoke", resultClass);
            assertEquals("missing", extErrField.invoke(null, errRec));
            assertEquals("ok", extErrField.invoke(null, ok1));
            
            Method wrapErr = loadFunction(loader, outputs, "wrapErrMessage").getMethod("invoke", resultClass);
            Object wOk = wrapErr.invoke(null, okItem);
            assertEquals(7, wOk.getClass().getField("code").get(wOk));
            assertEquals("widget", wOk.getClass().getField("msg").get(wOk));
            Object wErr = wrapErr.invoke(null, errBoom);
            assertEquals(500, wErr.getClass().getField("code").get(wErr));
            assertEquals("boom", wErr.getClass().getField("msg").get(wErr));
            
            Method retOkRec = loadFunction(loader, outputs, "returnOkRecord").getMethod("invoke", resultClass);
            Object outItem1 = retOkRec.invoke(null, okItem);
            assertEquals(7, outItem1.getClass().getField("id").get(outItem1));
            Object outItem2 = retOkRec.invoke(null, errBoom);
            assertEquals(-1, outItem2.getClass().getField("id").get(outItem2));
            assertEquals("boom", outItem2.getClass().getField("label").get(outItem2));
            
            Method retSomeStr = loadFunction(loader, outputs, "returnSomeString").getMethod("invoke", optionClass);
            assertEquals("hello", retSomeStr.invoke(null, someHello));
            assertEquals("none", retSomeStr.invoke(null, none));
            
            Method retSomeRec = loadFunction(loader, outputs, "returnSomeRecord").getMethod("invoke", optionClass);
            Object outSomeItem = retSomeRec.invoke(null, someItem);
            assertEquals(7, outSomeItem.getClass().getField("id").get(outSomeItem));
            Object outNoneItem = retSomeRec.invoke(null, none);
            assertEquals(0, outNoneItem.getClass().getField("id").get(outNoneItem));
            
            Method unwrapStr = loadFunction(loader, outputs, "unwrapStrOrElse").getMethod("invoke", optionClass);
            assertEquals("hello", unwrapStr.invoke(null, someHello));
            assertEquals("fallback", unwrapStr.invoke(null, none));
            
            Method unwrapNone = loadFunction(loader, outputs, "unwrapNoneIntOrElse").getMethod("invoke");
            assertEquals(77, unwrapNone.invoke(null));
        }
    }
    
    /**
     * Verify checkedAdd, checkedSub, toResult, and Cons build and execute cleanly.
     */
    @Test
    void runtimeVerify_checkedArithmeticAndToResultAndCons_work() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = """
            package test.stdlibrt;
            
            public fn addChecked(a: Int, b: Int) -> Result<Int, OverflowError> effects() {
                return checkedAdd(a, b);
            }
            
            public fn matchCheckedAdd(a: Int, b: Int) -> Int effects() {
                return match (checkedAdd(a, b)) {
                    case Ok(v) => { v },
                    case Err(_) => { -1 }
                };
            }
            
            public fn valCheckedSub(a: Int, b: Int) -> Int effects() {
                val r = checkedSub(a, b);
                return match (r) {
                    case Ok(v) => { v },
                    case Err(_) => { -2 }
                };
            }
            
            public fn convertToResult(opt: Option<Int>) -> Result<Int, String> effects() {
                return toResult(opt, "missing");
            }
            
            public fn matchConverted(opt: Option<Int>) -> String effects() {
                val r = toResult(opt, "missing");
                return match (r) {
                    case Ok(_) => { "present" },
                    case Err(msg) => { msg }
                };
            }
            
            public fn prependInt(x: Int, xs: List<Int>) -> List<Int> effects() {
                return Cons(x, xs);
            }
            """;
        
        var outputs = compileToClasses(source, "stdlib_rt.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> optionClass = Class.forName("ngj.runtime.Option", true, loader);
            Class<?> someClass = Class.forName("ngj.runtime.Option$Some", true, loader);
            Class<?> noneClass = Class.forName("ngj.runtime.Option$None", true, loader);
            
            Method addChecked = loadFunction(loader, outputs, "addChecked").getMethod("invoke", int.class, int.class);
            Object okSum = addChecked.invoke(null, 10, 20);
            assertEquals("ngj.runtime.Result$Ok", okSum.getClass().getName());
            assertEquals(30, okSum.getClass().getField("value").get(okSum));
            Object errSum = addChecked.invoke(null, Integer.MAX_VALUE, 1);
            assertEquals("ngj.runtime.Result$Err", errSum.getClass().getName());
            assertEquals("IntOverflow", errSum.getClass().getField("error").get(errSum));
            
            Method matchChecked = loadFunction(loader, outputs, "matchCheckedAdd").getMethod("invoke", int.class, int.class);
            assertEquals(30, matchChecked.invoke(null, 10, 20));
            assertEquals(-1, matchChecked.invoke(null, Integer.MAX_VALUE, 1));
            
            Method valSub = loadFunction(loader, outputs, "valCheckedSub").getMethod("invoke", int.class, int.class);
            assertEquals(5, valSub.invoke(null, 12, 7));
            assertEquals(-2, valSub.invoke(null, Integer.MIN_VALUE, 1));
            
            Object some7 = someClass.getConstructor(Object.class).newInstance(7);
            Object none = noneClass.getConstructor().newInstance();
            
            Method conv = loadFunction(loader, outputs, "convertToResult").getMethod("invoke", optionClass);
            Object cOk = conv.invoke(null, some7);
            assertEquals("ngj.runtime.Result$Ok", cOk.getClass().getName());
            assertEquals(7, cOk.getClass().getField("value").get(cOk));
            Object cErr = conv.invoke(null, none);
            assertEquals("ngj.runtime.Result$Err", cErr.getClass().getName());
            assertEquals("missing", cErr.getClass().getField("error").get(cErr));
            
            Method matchConv = loadFunction(loader, outputs, "matchConverted").getMethod("invoke", optionClass);
            assertEquals("present", matchConv.invoke(null, some7));
            assertEquals("missing", matchConv.invoke(null, none));
            
            Method prepend = loadFunction(loader, outputs, "prependInt").getMethod("invoke", int.class, List.class);
            @SuppressWarnings("unchecked")
            List<Integer> consed = (List<Integer>) prepend.invoke(null, 1, List.of(2, 3));
            assertEquals(List.of(1, 2, 3), consed);
            assertThrows(UnsupportedOperationException.class, () -> consed.add(4),
                "Cons must return an unmodifiable List");
        }
    }
    
    /**
     * Verify ? propagation, payload enums (qualified & unqualified), and nested patterns.
     */
    @Test
    void runtimeVerify_propagateAndPayloadEnumAndNestedPatterns_work() throws Exception {
        assumeTrue(jdkVersion >= REQUIRED_JDK_FOR_RUNTIME,
            "Runtime tests require JDK " + REQUIRED_JDK_FOR_RUNTIME);
        
        String source = """
            package test.advanced;
            
            public record Failure(detail: String);
            
            public enum Shape {
                Circle(radius: Int),
                Rect(width: Int, height: Int),
                Point()
            }
            
            public fn checkedDoubleSum(a: Int, b: Int) -> Result<Int, OverflowError> effects() {
                val sum = checkedAdd(a, b)?;
                return checkedMul(sum, 2);
            }
            
            public fn forwardMessage(r: Result<String, Failure>) -> Result<String, Failure> effects() {
                val s = r?;
                return Ok(s);
            }
            
            public fn areaQualified(s: Shape) -> Int effects() {
                return match (s) {
                    case Shape.Circle(r) => { r * r * 3 },
                    case Shape.Rect(w, h) => { w * h },
                    case Shape.Point() => { 0 }
                };
            }
            
            public fn buildAndArea(kind: Int, a: Int, b: Int) -> Int effects() {
                val shape = if (kind == 1) {
                    Shape.Circle(a)
                } else if (kind == 2) {
                    Rect(a, b)
                } else {
                    Shape.Point()
                };
                return match (shape) {
                    case Circle(r) => { r * r * 3 },
                    case Rect(w, h) => { w * h },
                    case Point() => { 0 }
                };
            }
            
            public fn unwrapNested(r: Result<Option<Int>, String>) -> Int effects() {
                return match (r) {
                    case Ok(Some(v)) => { v },
                    case Ok(None()) => { 0 },
                    case Err(_) => { -1 }
                };
            }
            """;
        
        var outputs = compileToClasses(source, "advanced.ngj", tempDir, "valhalla");
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> resultClass = Class.forName("ngj.runtime.Result", true, loader);
            Class<?> okClass = Class.forName("ngj.runtime.Result$Ok", true, loader);
            Class<?> errClass = Class.forName("ngj.runtime.Result$Err", true, loader);
            Class<?> someClass = Class.forName("ngj.runtime.Option$Some", true, loader);
            Class<?> noneClass = Class.forName("ngj.runtime.Option$None", true, loader);
            Class<?> failureClass = Class.forName("test.advanced.Failure", true, loader);
            Class<?> shapeClass = Class.forName("test.advanced.Shape", true, loader);
            
            Method cds = loadFunction(loader, outputs, "checkedDoubleSum").getMethod("invoke", int.class, int.class);
            Object ok30 = cds.invoke(null, 10, 5);
            assertEquals("ngj.runtime.Result$Ok", ok30.getClass().getName());
            assertEquals(30, ok30.getClass().getField("value").get(ok30));
            Object ovErr = cds.invoke(null, Integer.MAX_VALUE, 1);
            assertEquals("ngj.runtime.Result$Err", ovErr.getClass().getName());
            
            Method fwd = loadFunction(loader, outputs, "forwardMessage").getMethod("invoke", resultClass);
            Object fOk = fwd.invoke(null, okClass.getConstructor(Object.class).newInstance("done"));
            assertEquals("done", fOk.getClass().getField("value").get(fOk));
            Object failRec = failureClass.getConstructor(String.class).newInstance("bad");
            Object fErr = fwd.invoke(null, errClass.getConstructor(Object.class).newInstance(failRec));
            assertEquals("ngj.runtime.Result$Err", fErr.getClass().getName());
            Object outFail = fErr.getClass().getField("error").get(fErr);
            assertEquals("bad", outFail.getClass().getField("detail").get(outFail));
            
            Method areaQ = loadFunction(loader, outputs, "areaQualified").getMethod("invoke", shapeClass);
            Object circle = Class.forName("test.advanced.Shape$Circle", true, loader).getConstructor(int.class).newInstance(4);
            Object rect = Class.forName("test.advanced.Shape$Rect", true, loader).getConstructor(int.class, int.class).newInstance(3, 5);
            Object point = Class.forName("test.advanced.Shape$Point", true, loader).getConstructor().newInstance();
            assertEquals(48, areaQ.invoke(null, circle));
            assertEquals(15, areaQ.invoke(null, rect));
            assertEquals(0, areaQ.invoke(null, point));
            
            Method baa = loadFunction(loader, outputs, "buildAndArea").getMethod("invoke", int.class, int.class, int.class);
            assertEquals(27, baa.invoke(null, 1, 3, 0));
            assertEquals(20, baa.invoke(null, 2, 4, 5));
            assertEquals(0, baa.invoke(null, 3, 0, 0));
            
            Method un = loadFunction(loader, outputs, "unwrapNested").getMethod("invoke", resultClass);
            assertEquals(55, un.invoke(null, okClass.getConstructor(Object.class).newInstance(someClass.getConstructor(Object.class).newInstance(55))));
            assertEquals(0, un.invoke(null, okClass.getConstructor(Object.class).newInstance(noneClass.getConstructor().newInstance())));
            assertEquals(-1, un.invoke(null, errClass.getConstructor(Object.class).newInstance("err")));
        }
    }
    
    private List<Path> compileToClasses(String source, String fileName, Path outDir, String target) throws Exception {
        var lexer = new Lexer(source, fileName);
        var parser = new Parser(lexer);
        var unit = parser.parse();
        var parseErrors = parser.getDiagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
        assertTrue(parseErrors.isEmpty(), "Parse errors in " + fileName + ": " + parseErrors);
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        var resolveErrors = resolver.getDiagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
        assertTrue(resolveErrors.isEmpty(), "Resolve errors in " + fileName + ": " + resolveErrors);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        var typeErrors = typeChecker.getDiagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList();
        assertTrue(typeErrors.isEmpty(), "Type errors in " + fileName + ": " + typeErrors);
        
        var codeGen = new Java25CodeGenerator();
        codeGen.setTarget(target);
        codeGen.setOutputDir(outDir);
        return codeGen.generate(unit);
    }
    
    /**
     * Helper to load a function class by exact name (falling back to substring match).
     */
    private Class<?> loadFunction(URLClassLoader loader, List<Path> outputs, String functionName) {
        String exactSuffix = "." + functionName + "$Fn";
        for (Path classFile : outputs) {
            String className = getClassName(classFile, tempDir);
            if (className.endsWith(exactSuffix) || className.equals(functionName + "$Fn")) {
                try {
                    return Class.forName(className, true, loader);
                } catch (Exception e) {
                    System.out.println("Failed to load " + functionName + ": " + e.getMessage());
                }
            }
        }
        for (Path classFile : outputs) {
            String className = getClassName(classFile, tempDir);
            if (className.toLowerCase().contains(functionName.toLowerCase()) && className.endsWith("$Fn")) {
                try {
                    return Class.forName(className, true, loader);
                } catch (Exception e) {
                    System.out.println("Failed to load " + functionName + ": " + e.getMessage());
                }
            }
        }
        return null;
    }
    
    // ========== Helper Methods ==========
    
    private String readFixture(String path) throws IOException {
        var resource = getClass().getClassLoader()
            .getResourceAsStream(FIXTURES_BASE + "/" + path);
        if (resource == null) {
            throw new IOException("Fixture not found: " + path);
        }
        return new String(resource.readAllBytes());
    }
    
    private static List<String> getFixtureFiles(String subdir) throws IOException {
        var result = new ArrayList<String>();
        var url = JDK28RuntimeTest.class.getClassLoader()
            .getResource(FIXTURES_BASE + "/" + subdir);
        if (url != null) {
            var dir = Path.of(url.getPath());
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".ngj"))
                    .forEach(p -> result.add(p.getFileName().toString()));
            }
        }
        return result;
    }
    
    private String getClassName(Path classFile, Path baseDir) {
        String relative = baseDir.relativize(classFile).toString();
        return relative
            .replace(".class", "")
            .replace('/', '.')
            .replace('\\', '.');
    }
}
