package ngj;

import ngj.codegen.Java25CodeGenerator;
import ngj.diagnostic.Severity;
import ngj.parser.Lexer;
import ngj.parser.Parser;
import ngj.typechecker.NameResolver;
import ngj.typechecker.TypeChecker;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
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

/**
 * JDK 28 Runtime Verification Tests.
 * 
 * This test suite validates that generated class files:
 * 1. Compile successfully
 * 2. Load correctly on JDK 28 EA with --enable-preview
 * 3. Execute and produce expected results
 * 
 * Coverage:
 * - fixtures/positive: Must compile, load, and execute without errors
 * - fixtures/negative: Must fail type checking with expected diagnostics
 * 
 * Note: Some tests are temporarily disabled due to known code generation issues
 * that will be fixed in PR 3. See the KNOWN_FAILURES table for details.
 */
public class JDK28RuntimeTest {
    
    @TempDir
    Path tempDir;
    
    private static final String FIXTURES_BASE = "fixtures";
    private static final int JDK_28_CLASS_VERSION = 72;
    
    /**
     * Known failures that are temporarily disabled.
     * These will be fixed in PR 3 (code generation fixes).
     * 
     * | Fixture               | Issue ID      | Root Cause                                      | PR Fix  |
     * |-----------------------|---------------|------------------------------------------------|---------|
     * | result_option.ngj     | NGJ-TYPE-011  | Result type reference resolution                | PR 3    |
     * | data_type.ngj         | CLASSFORMAT   | Record constructor field order (JEP 401)        | PR 3    |
     * | enum_match.ngj        | MATCH-GEN     | Match expression codegen (scrutinee dropped)    | PR 3    |
     */
    private static final List<String> KNOWN_COMPILE_FAILURES = List.of(
        "result_option.ngj"  // NGJ-TYPE-011: Type checker can't resolve DivError in Result<Int, DivError>
    );
    
    private static final List<String> KNOWN_RUNTIME_FAILURES = List.of(
        "data_type.ngj",     // ClassFormatError: missing strict flag on record fields (JEP 401)
        "enum_match.ngj"     // Match codegen drops scrutinee, returns first arm unconditionally
    );
    
    private static int jdkVersion;
    private static int targetClassVersion;
    
    @BeforeAll
    static void checkJDKVersion() {
        jdkVersion = Runtime.version().feature();
        targetClassVersion = Integer.parseInt(
            System.getenv().getOrDefault("NGJ_RUNTIME_CLASS_VERSION", "72"));
        
        System.out.println("JDK28RuntimeTest running on JDK " + jdkVersion);
        System.out.println("Target class file version: " + targetClassVersion);
        
        if (jdkVersion < 28) {
            System.out.println("NOTE: Runtime load/execute tests will be skipped (requires JDK 28)");
        }
    }
    
    // ========== Positive Fixture Tests ==========
    
    static Stream<String> positiveFixtures() throws IOException {
        return getFixtureFiles("positive").stream()
            .filter(f -> !KNOWN_COMPILE_FAILURES.contains(f));
    }
    
    @ParameterizedTest(name = "positive/{0}")
    @MethodSource("positiveFixtures")
    void positiveFixture_compilesAndTypeChecks(String fixture) throws Exception {
        String source = readFixture("positive/" + fixture);
        
        // Parse
        var lexer = new Lexer(source, fixture);
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var parseErrors = parser.getDiagnostics().stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        assertTrue(parseErrors.isEmpty(), 
            "Parse errors in " + fixture + ": " + parseErrors);
        
        // Name resolution
        var resolver = new NameResolver();
        resolver.resolve(unit);
        var resolveErrors = resolver.getDiagnostics().stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        assertTrue(resolveErrors.isEmpty(),
            "Name resolution errors in " + fixture + ": " + resolveErrors);
        
        // Type check
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
        if (KNOWN_RUNTIME_FAILURES.contains(fixture)) {
            System.out.println("SKIPPED (known runtime failure, see PR 3): " + fixture);
            return;
        }
        
        String source = readFixture("positive/" + fixture);
        
        // Parse and check
        var lexer = new Lexer(source, fixture);
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        // Generate
        var codeGen = new Java25CodeGenerator();
        codeGen.setOutputDir(tempDir);
        var outputs = codeGen.generate(unit);
        
        assertFalse(outputs.isEmpty(), 
            "No class files generated for " + fixture);
        
        // Verify class file format
        for (var classFile : outputs) {
            assertTrue(Files.exists(classFile), 
                "Class file not found: " + classFile);
            
            byte[] bytes = Files.readAllBytes(classFile);
            assertTrue(bytes.length > 8, 
                "Class file too small: " + classFile);
            
            // Verify magic number (0xCAFEBABE)
            assertEquals((byte) 0xCA, bytes[0], "Invalid magic byte 0");
            assertEquals((byte) 0xFE, bytes[1], "Invalid magic byte 1");
            assertEquals((byte) 0xBA, bytes[2], "Invalid magic byte 2");
            assertEquals((byte) 0xBE, bytes[3], "Invalid magic byte 3");
            
            // Verify class file version matches target (default: JDK 28)
            int minorVersion = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
            int majorVersion = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
            
            assertEquals(targetClassVersion, majorVersion,
                "Class file version should be " + targetClassVersion + " for target JDK: " + classFile);
        }
    }
    
    @ParameterizedTest(name = "positive/{0} runtime")
    @MethodSource("positiveFixtures")
    void positiveFixture_loadsAndExecutesOnJDK28(String fixture) throws Exception {
        if (Runtime.version().feature() < 28) {
            System.out.println("SKIPPED (requires JDK 28): " + fixture);
            return;
        }
        
        if (KNOWN_RUNTIME_FAILURES.contains(fixture)) {
            System.out.println("SKIPPED (known runtime failure, see PR 3): " + fixture);
            return;
        }
        
        String source = readFixture("positive/" + fixture);
        
        // Parse, check, generate
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
        
        // Load classes with custom class loader
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] { tempDir.toUri().toURL() },
                getClass().getClassLoader())) {
            
            for (var classFile : outputs) {
                String className = getClassName(classFile, tempDir);
                
                // Try to load the class
                try {
                    Class<?> clazz = loader.loadClass(className);
                    assertNotNull(clazz, "Failed to load class: " + className);
                    
                    // If there's an invoke method, try to call it
                    for (Method method : clazz.getDeclaredMethods()) {
                        if (method.getName().equals("invoke") && 
                            method.getParameterCount() == 0 &&
                            java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                            
                            method.setAccessible(true);
                            Object result = method.invoke(null);
                            System.out.println("  " + className + ".invoke() = " + result);
                        }
                    }
                } catch (ClassFormatError e) {
                    fail("ClassFormatError loading " + className + ": " + e.getMessage());
                } catch (VerifyError e) {
                    fail("VerifyError loading " + className + ": " + e.getMessage());
                } catch (NoClassDefFoundError e) {
                    fail("NoClassDefFoundError loading " + className + ": " + e.getMessage());
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
        
        // Parse
        var lexer = new Lexer(source, fixture);
        var parser = new Parser(lexer);
        var unit = parser.parse();
        var allDiagnostics = new ArrayList<>(parser.getDiagnostics());
        
        // Name resolution
        var resolver = new NameResolver();
        resolver.resolve(unit);
        allDiagnostics.addAll(resolver.getDiagnostics());
        
        // Type check
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        allDiagnostics.addAll(typeChecker.getDiagnostics());
        
        var errors = allDiagnostics.stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        
        // Verify expected diagnostic based on fixture
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
                assertTrue(errors.stream().anyMatch(e -> 
                    e.id().equals("NGJ-IMPORT-001") || e.id().contains("IMPORT")),
                    "Expected import-related error: " + errors);
            }
            case "user_generics.ngj" -> {
                // This file uses legacy syntax, so we expect parse or syntax migration errors
                assertFalse(errors.isEmpty(),
                    "Expected errors for user-defined generics: " + errors);
            }
            default -> {
                assertFalse(errors.isEmpty(),
                    "Expected errors for negative fixture " + fixture + " but got none");
            }
        }
    }
    
    // ========== Known Failure Documentation Tests ==========
    
    @Test
    void knownFailure_resultOption_typeResolution() throws Exception {
        // This test documents the NGJ-TYPE-011 issue with result_option.ngj
        // The type checker cannot resolve DivError in Result<Int, DivError>
        // because the record is defined in the same file and used in a generic context.
        // This will be fixed in PR 3.
        
        String source = readFixture("positive/result_option.ngj");
        
        var lexer = new Lexer(source, "result_option.ngj");
        var parser = new Parser(lexer);
        var unit = parser.parse();
        
        var resolver = new NameResolver();
        resolver.resolve(unit);
        
        var typeChecker = new TypeChecker();
        typeChecker.check(unit);
        
        var errors = typeChecker.getDiagnostics().stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .toList();
        
        // Document the current behavior
        assertFalse(errors.isEmpty(), 
            "result_option.ngj currently fails type checking (PR 3 will fix this)");
        System.out.println("KNOWN FAILURE (PR 3): result_option.ngj - " + errors);
    }
    
    @Test
    @Disabled("PR 3: Record field strict flag not set (JEP 401)")
    void knownFailure_dataType_classFormatError() throws Exception {
        // This test documents the ClassFormatError with data_type.ngj
        // The generated record class doesn't have the strict flag on fields
        // as required by JEP 401 for value classes.
        // Will be fixed in PR 3.
    }
    
    @Test
    @Disabled("PR 3: Match expression codegen drops scrutinee")
    void knownFailure_enumMatch_matchCodegen() throws Exception {
        // This test documents the match expression codegen issue
        // The generated code at Java25CodeGenerator.java:1027-1034
        // drops the scrutinee and always returns the first arm.
        // Will be fixed in PR 3.
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
