package ngj;

import ngj.cli.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pricing example files demonstrating the AI agent workflow
 */
class PricingExampleTest {
    
    @Test
    void completeVersionCompiles() throws Exception {
        var file = getFixturePath("examples/pricing_complete.ngj");
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode, 
            "Complete version should compile successfully: " + stdout);
        var json = stdout.toString();
        assertTrue(json.contains("\"status\": \"valid\""));
    }
    
    @Test
    void holeVersionReturnsIncomplete() throws Exception {
        var file = getFixturePath("examples/pricing_hole.ngj");
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.INCOMPLETE, exitCode,
            "Hole version should return incomplete status");
        var json = stdout.toString();
        assertTrue(json.contains("\"status\": \"incomplete\""));
    }
    
    @Test
    void errorVersionReturnsInvalid() throws Exception {
        var file = getFixturePath("examples/pricing_error.ngj");
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"check", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.INVALID, exitCode,
            "Error version should return invalid status");
        var json = stdout.toString();
        assertTrue(json.contains("\"status\": \"invalid\""));
        assertTrue(json.contains("\"diagnostics\""));
    }
    
    @Test
    void canGetContextForHole() throws Exception {
        var file = getFixturePath("examples/pricing_hole.ngj");
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"context", "--format=json", "--hole=subtotalCalc", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode,
            "Should get context for hole: " + stderr);
        var json = stdout.toString();
        assertTrue(json.contains("\"hole_id\": \"subtotalCalc\""));
        assertTrue(json.contains("\"expected_type\": \"Int\""));
    }
    
    @Test
    void buildFailsForHoleVersion() throws Exception {
        var file = getFixturePath("examples/pricing_hole.ngj");
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"build", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.INCOMPLETE, exitCode,
            "Build should fail for incomplete source");
    }
    
    @Test
    void buildSucceedsForCompleteVersion() throws Exception {
        var file = getFixturePath("examples/pricing_complete.ngj");
        
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        
        int exitCode = Main.run(
            new String[]{"build", "--format=json", file.toString()},
            new PrintStream(stdout),
            new PrintStream(stderr)
        );
        
        assertEquals(ExitCode.SUCCESS, exitCode,
            "Build should succeed for complete source: " + stdout);
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
