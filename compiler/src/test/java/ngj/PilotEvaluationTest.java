package ngj;

import ngj.cli.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pilot evaluation fixtures.
 * These verify the AI agent workflow scenarios.
 */
class PilotEvaluationTest {
    
    @Test
    void simpleFillHasOneHole() throws Exception {
        var file = getFixturePath("pilot/simple_fill.ngj");
        
        var stdout = new ByteArrayOutputStream();
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(new ByteArrayOutputStream())
        );
        
        assertEquals(ExitCode.INCOMPLETE, exitCode);
        var json = stdout.toString();
        // Should have exactly one hole diagnostic
        assertTrue(json.contains("?result"));
    }
    
    @Test
    void simpleFillContextHasParameters() throws Exception {
        var file = getFixturePath("pilot/simple_fill.ngj");
        
        var stdout = new ByteArrayOutputStream();
        int exitCode = Main.run(
            new String[]{"context", "--format=json", "--hole=result", file.toString()},
            new PrintStream(stdout),
            new PrintStream(new ByteArrayOutputStream())
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"expected_type\": \"Int\""));
        // Should show available bindings x and y
        assertTrue(json.contains("\"bindings\""));
    }
    
    @Test
    void multiHoleHasMultipleHoles() throws Exception {
        var file = getFixturePath("pilot/multi_hole.ngj");
        
        var stdout = new ByteArrayOutputStream();
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(new ByteArrayOutputStream())
        );
        
        assertEquals(ExitCode.INCOMPLETE, exitCode);
        var json = stdout.toString();
        // Should have three holes
        assertTrue(json.contains("?doubleImpl"));
        assertTrue(json.contains("?quadrupleImpl"));
        assertTrue(json.contains("?isEvenImpl"));
    }
    
    @Test
    void matchCompleteDetectsNonExhaustive() throws Exception {
        var file = getFixturePath("pilot/match_complete.ngj");
        
        var stdout = new ByteArrayOutputStream();
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(new ByteArrayOutputStream())
        );
        
        // The match is non-exhaustive (missing Blue case), so it's invalid
        // The hole makes it incomplete too, but invalid takes precedence
        assertEquals(ExitCode.INVALID, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("Non-exhaustive match"));
    }
    
    @Test
    void dataConstructReturnsExpectedType() throws Exception {
        var file = getFixturePath("pilot/data_construct.ngj");
        
        var stdout = new ByteArrayOutputStream();
        int exitCode = Main.run(
            new String[]{"context", "--format=json", "--hole=originPoint", file.toString()},
            new PrintStream(stdout),
            new PrintStream(new ByteArrayOutputStream())
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"expected_type\": \"Point\""));
    }
    
    @Test
    void rectangleHoleExpectsRectangle() throws Exception {
        var file = getFixturePath("pilot/data_construct.ngj");
        
        var stdout = new ByteArrayOutputStream();
        int exitCode = Main.run(
            new String[]{"context", "--format=json", "--hole=rectangle", file.toString()},
            new PrintStream(stdout),
            new PrintStream(new ByteArrayOutputStream())
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode);
        var json = stdout.toString();
        assertTrue(json.contains("\"expected_type\": \"Rectangle\""));
        // Should have origin and corner in scope
        assertTrue(json.contains("origin"));
        assertTrue(json.contains("corner"));
    }
    
    private Path getFixturePath(String name) {
        var url = getClass().getClassLoader().getResource("fixtures/" + name);
        assertNotNull(url, "Fixture not found: " + name);
        try {
            return Path.of(url.toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
