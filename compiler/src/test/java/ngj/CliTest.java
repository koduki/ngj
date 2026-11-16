package ngj;

import ngj.cli.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    
    private Path tempDir;
    
    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ngj-test");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        // Clean up temp files
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }
    
    @Test
    void helpCommand() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"--help"},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        assertTrue(stdout.toString().contains("ngjc"));
    }
    
    @Test
    void versionCommand() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"--version"},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        assertTrue(stdout.toString().contains("0.1.0-mvp"));
    }
    
    @Test
    void unknownCommand() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"unknown"},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.BAD_INPUT, exitCode);
        assertTrue(stderr.toString().contains("Unknown command"));
    }
    
    @Test
    void checkNoFiles() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check"},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.BAD_INPUT, exitCode);
        assertTrue(stderr.toString().contains("No input files"));
    }
    
    @Test
    void checkValidFile() throws Exception {
        var source = """
            module test.valid
            
            pub fn main() -> Int ! {} {
                42
            }
            """;
        var file = tempDir.resolve("valid.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        assertTrue(stdout.toString().contains("valid"));
    }
    
    @Test
    void checkWithJsonFormat() throws Exception {
        var source = """
            module test.json
            
            pub fn main() -> Int ! {} { 42 }
            """;
        var file = tempDir.resolve("json.ngj");
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
        assertTrue(json.contains("\"schema_version\""));
        assertTrue(json.contains("\"compiler_version\""));
        assertTrue(json.contains("\"status\": \"valid\""));
    }
    
    @Test
    void checkInvalidFile() throws Exception {
        var source = """
            module test.invalid
            
            pub fn test() -> UnknownType ! {} {
                42
            }
            """;
        var file = tempDir.resolve("invalid.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.INVALID, exitCode);
    }
    
    @Test
    void checkIncompleteFile() throws Exception {
        var source = """
            module test.incomplete
            
            pub fn calculate() -> Int ! {} {
                ?todo
            }
            """;
        var file = tempDir.resolve("incomplete.ngj");
        Files.writeString(file, source);
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.INCOMPLETE, exitCode);
    }
    
    @Test
    void contextMissingHole() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"context"},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.BAD_INPUT, exitCode);
        assertTrue(stderr.toString().contains("--hole"));
    }
    
    @Test
    void inspectMissingSymbol() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"inspect"},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.BAD_INPUT, exitCode);
        assertTrue(stderr.toString().contains("--symbol"));
    }
    
    @Test
    void buildNoFiles() {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"build"},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.BAD_INPUT, exitCode);
    }
}
