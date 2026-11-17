package ngj;

import ngj.cli.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Agent-facing CLI commands as specified in AGENT_TOOLING.md
 */
class AgentApiTest {
    
    private Path tempDir;
    
    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ngj-agent-test");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }
    
    @Test
    void checkReturnsValidStatus() throws Exception {
        var source = """
            package test.valid;
            
            public fn answer() -> Int effects() {
                return 42;
            }
            """;
        var file = tempDir.resolve("valid.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"status\": \"valid\""));
        assertTrue(json.contains("\"schema_version\""));
        assertTrue(json.contains("\"compiler_version\""));
        assertTrue(json.contains("\"source_revision\""));
        assertTrue(json.contains("\"target_profile\""));
    }
    
    @Test
    void checkReturnsInvalidForErrors() throws Exception {
        var source = """
            package test.invalid;
            
            public fn test() -> UnknownType effects() {
                return 42;
            }
            """;
        var file = tempDir.resolve("invalid.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.INVALID, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"status\": \"invalid\""));
        assertTrue(json.contains("\"diagnostics\""));
    }
    
    @Test
    void checkReturnsIncompleteForHoles() throws Exception {
        var source = """
            package test.incomplete;
            
            public fn calculate() -> Int effects() {
                ?todo
            }
            """;
        var file = tempDir.resolve("incomplete.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.INCOMPLETE, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"status\": \"incomplete\""));
    }
    
    @Test
    void revisionMismatchReturnsError() throws Exception {
        var source = """
            package test.valid;
            
            public fn answer() -> Int effects() { return 42; }
            """;
        var file = tempDir.resolve("valid.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", "--format=json", "--revision=sha256:invalid", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.BAD_INPUT, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"error\": \"revision_mismatch\""));
    }
    
    @Test
    void contextReturnsHoleInfo() throws Exception {
        var source = """
            package test.ctx;
            
            public fn calculate(x: Int, y: Int) -> Int effects() {
                ?implementation
            }
            """;
        var file = tempDir.resolve("ctx.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"context", "--format=json", "--hole=implementation", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"hole_id\": \"implementation\""));
        assertTrue(json.contains("\"expected_type\""));
        assertTrue(json.contains("\"bindings\""));
    }
    
    @Test
    void contextReturnsErrorForMissingHole() throws Exception {
        var source = """
            package test.noctx;
            
            public fn answer() -> Int effects() { return 42; }
            """;
        var file = tempDir.resolve("noctx.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"context", "--format=json", "--hole=nonexistent", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.BAD_INPUT, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"error\""));
    }
    
    @Test
    void inspectReturnsSymbolInfo() throws Exception {
        var source = """
            package test.sym;
            
            public fn myFunction(x: Int) -> Int effects() {
                return x + 1;
            }
            """;
        var file = tempDir.resolve("sym.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"inspect", "--format=json", "--symbol=myFunction", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"name\": \"myFunction\""));
        assertTrue(json.contains("\"kind\": \"function\""));
        assertTrue(json.contains("\"visibility\""));
    }
    
    @Test
    void buildRejectsIncompleteSource() throws Exception {
        var source = """
            package test.incomplete;
            
            public fn calculate() -> Int effects() {
                ?todo
            }
            """;
        var file = tempDir.resolve("incomplete.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"build", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.INCOMPLETE, exitCode);
    }
    
    @Test
    void buildSucceedsForValidSource() throws Exception {
        var source = """
            package test.buildok;
            
            public fn answer() -> Int effects() {
                return 42;
            }
            """;
        var file = tempDir.resolve("buildok.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"build", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"success\": true"));
        assertTrue(json.contains("\"output_files\""));
    }
    
    @Test
    void exitCodeZeroForValidCheck() throws Exception {
        var source = """
            package test.exitcode;
            
            public fn main() -> Int effects() { return 0; }
            """;
        var file = tempDir.resolve("exitcode.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(0, exitCode, "Exit code 0 for valid source");
    }
    
    @Test
    void exitCodeOneForInvalid() throws Exception {
        var source = """
            package test.exitcode;
            
            public fn test() -> BadType effects() { return 42; }
            """;
        var file = tempDir.resolve("exitcode.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(1, exitCode, "Exit code 1 for invalid source");
    }
    
    @Test
    void exitCodeTwoForIncomplete() throws Exception {
        var source = """
            package test.exitcode;
            
            public fn test() -> Int effects() { ?hole }
            """;
        var file = tempDir.resolve("exitcode.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(2, exitCode, "Exit code 2 for incomplete source");
    }
    
    @Test
    void exitCodeThreeForBadInput() throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check"},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(3, exitCode, "Exit code 3 for bad input (no files)");
    }
}
