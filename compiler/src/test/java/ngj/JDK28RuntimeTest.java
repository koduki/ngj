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
                    Class<?> clazz = loader.loadClass(className);
                    assertNotNull(clazz, "Should load class: " + className);
                    
                    verifyFunctionExecution(fixture, clazz, className);
                }
            }
        }
        
        System.out.println("PASSED: " + fixture + " loaded and executed successfully on JDK " + jdkVersion);
    }
    
    private void verifyFunctionExecution(String fixture, Class<?> clazz, String className) throws Exception {
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
        
        switch (fixture) {
            case "hello.ngj" -> {
                if (className.contains("main")) {
                    Object result = invokeMethod.invoke(null);
                    assertEquals(42, result, "main() should return 42");
                    System.out.println("  Verified: main() = 42");
                }
            }
            case "arithmetic.ngj" -> {
                if (className.contains("add")) {
                    Object result = invokeMethod.invoke(null, 3, 5);
                    assertEquals(8, result, "add(3, 5) should return 8");
                    System.out.println("  Verified: add(3, 5) = 8");
                } else if (className.contains("subtract")) {
                    Object result = invokeMethod.invoke(null, 10, 4);
                    assertEquals(6, result, "subtract(10, 4) should return 6");
                    System.out.println("  Verified: subtract(10, 4) = 6");
                } else if (className.contains("multiply")) {
                    Object result = invokeMethod.invoke(null, 6, 7);
                    assertEquals(42, result, "multiply(6, 7) should return 42");
                    System.out.println("  Verified: multiply(6, 7) = 42");
                }
            }
            case "logic.ngj" -> {
                if (className.contains("isPositive")) {
                    Object result = invokeMethod.invoke(null, 5);
                    assertEquals(true, result, "isPositive(5) should return true");
                    System.out.println("  Verified: isPositive(5) = true");
                }
            }
            case "control_flow.ngj" -> {
                if (className.contains("max")) {
                    Object result = invokeMethod.invoke(null, 3, 7);
                    assertEquals(7, result, "max(3, 7) should return 7");
                    System.out.println("  Verified: max(3, 7) = 7");
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
                Class<?> clazz = loader.loadClass(className);
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
                Class<?> clazz = loader.loadClass(className);
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
            
            public fn answer() -> Int effects() {
                return 42;
            }
            """;
        
        var lexer = new Lexer(source, "target_test.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
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
    }
    
    private Object getEnumVariant(URLClassLoader loader, String className) {
        try {
            Class<?> clazz = loader.loadClass(className);
            return clazz.getConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
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
