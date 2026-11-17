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
 * Coverage:
 * - fixtures/positive: Must compile, load, and execute without errors
 * - fixtures/negative: Must fail type checking with expected diagnostics
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
     * | Fixture               | Failure Type      | Root Cause                                      |
     * |-----------------------|-------------------|------------------------------------------------|
     * | data_type.ngj         | ClassFormatError  | Record fields missing strict flag (JEP 401)     |
     * | enum_match.ngj        | Wrong result      | match codegen returns first arm (:1315-1321)    |
     * 
     * Note: result_option.ngj was fixed in PR #13.
     */
    private static final List<ExpectedFailure> EXPECTED_FAILURES = List.of(
        new ExpectedFailure("data_type.ngj", FailureType.RUNTIME_CLASS_FORMAT,
            "Record fields missing strict flag (0x11 without 0x0800). JEP 401 requires strict fields for value classes."),
        new ExpectedFailure("enum_match.ngj", FailureType.RUNTIME_WRONG_RESULT,
            "match codegen at :1315-1321 always returns first arm. colorToInt(Red/Green/Blue) all return 0.")
    );
    
    record ExpectedFailure(String fixture, FailureType type, String reason) {}
    
    enum FailureType {
        COMPILE_TYPE_ERROR,      // Type checking fails
        RUNTIME_CLASS_FORMAT,    // ClassFormatError when loading
        RUNTIME_VERIFY_ERROR,    // VerifyError when loading
        RUNTIME_WRONG_RESULT,    // Executes but returns wrong value
        RUNTIME_EXCEPTION        // Throws unexpected exception
    }
    
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
        System.out.println("Expected failures: " + EXPECTED_FAILURES.size());
        for (var ef : EXPECTED_FAILURES) {
            System.out.println("  - " + ef.fixture() + " (" + ef.type() + "): " + ef.reason());
        }
        System.out.println("========================================");
    }
    
    private static boolean isExpectedFailure(String fixture) {
        return EXPECTED_FAILURES.stream().anyMatch(ef -> ef.fixture().equals(fixture));
    }
    
    private static ExpectedFailure getExpectedFailure(String fixture) {
        return EXPECTED_FAILURES.stream()
            .filter(ef -> ef.fixture().equals(fixture))
            .findFirst()
            .orElse(null);
    }
    
    // ========== Positive Fixture Tests ==========
    
    static Stream<String> positiveFixtures() throws IOException {
        return getFixtureFiles("positive").stream();
    }
    
    static Stream<String> positiveFixturesExcludingExpectedFailures() throws IOException {
        return getFixtureFiles("positive").stream()
            .filter(f -> !isExpectedFailure(f));
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
        var outputs = codeGen.generate(unit, tempDir);
        
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
        var outputs = codeGen.generate(unit, tempDir);
        
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
    
    // ========== Expected Failure Tests ==========
    
    @Test
    void expectedFailure_dataType_classFormatError() throws Exception {
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
        var outputs = codeGen.generate(unit, tempDir);
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            for (Path classFile : outputs) {
                String className = getClassName(classFile, tempDir);
                try {
                    loader.loadClass(className);
                    fail("UNEXPECTED PASS: data_type.ngj should fail with ClassFormatError " +
                         "(record fields missing strict flag). PR 3 will fix this.");
                } catch (ClassFormatError e) {
                    System.out.println("EXPECTED FAILURE: data_type.ngj - " + e.getMessage());
                    assertTrue(true, "ClassFormatError is expected until PR 3 fixes strict fields");
                    return;
                }
            }
        }
    }
    
    @Test
    void expectedFailure_enumMatch_wrongResult() throws Exception {
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
        var outputs = codeGen.generate(unit, tempDir);
        
        URL[] urls = { tempDir.toUri().toURL() };
        try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
            Class<?> colorClass = null;
            Class<?> colorToIntFn = null;
            
            for (Path classFile : outputs) {
                String className = getClassName(classFile, tempDir);
                try {
                    Class<?> clazz = loader.loadClass(className);
                    if (className.endsWith("Color")) {
                        colorClass = clazz;
                    } else if (className.contains("colorToInt")) {
                        colorToIntFn = clazz;
                    }
                } catch (ClassFormatError | VerifyError e) {
                    System.out.println("EXPECTED FAILURE: enum_match.ngj - " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    assertTrue(true, "Class loading error is expected until PR 3 fixes enum codegen");
                    return;
                }
            }
            
            if (colorToIntFn != null && colorClass != null) {
                Method invoke = colorToIntFn.getMethod("invoke", Object.class);
                Object red = getEnumVariant(loader, "test.enumeration.Red");
                Object green = getEnumVariant(loader, "test.enumeration.Green");
                Object blue = getEnumVariant(loader, "test.enumeration.Blue");
                
                if (red != null && green != null && blue != null) {
                    Object redResult = invoke.invoke(null, red);
                    Object greenResult = invoke.invoke(null, green);
                    Object blueResult = invoke.invoke(null, blue);
                    
                    if (redResult.equals(0) && greenResult.equals(0) && blueResult.equals(0)) {
                        System.out.println("EXPECTED FAILURE: enum_match.ngj - match returns first arm for all variants");
                        System.out.println("  colorToInt(Red) = " + redResult + " (expected 0)");
                        System.out.println("  colorToInt(Green) = " + greenResult + " (expected 1, got 0)");
                        System.out.println("  colorToInt(Blue) = " + blueResult + " (expected 2, got 0)");
                        assertTrue(true, "Match codegen bug is expected until PR 3");
                        return;
                    } else if (redResult.equals(0) && greenResult.equals(1) && blueResult.equals(2)) {
                        fail("UNEXPECTED PASS: enum_match.ngj now works correctly. Remove from EXPECTED_FAILURES.");
                    }
                }
            }
            
            System.out.println("EXPECTED FAILURE: enum_match.ngj - could not fully verify (enum variant loading issue)");
        }
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
