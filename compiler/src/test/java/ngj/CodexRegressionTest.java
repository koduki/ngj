package ngj;

import ngj.cli.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CodexRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void conditionalEarlyReturnViolatingPostconditionFailsSmtVerification() throws Exception {
        Path file = tempDir.resolve("early_return_vc.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn f(x: Int) -> Int effects()
                requires (true)
                ensures (result == 0)
            {
                if (x > 0) {
                    return 1;
                }
                return 0;
            }
            """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
            new String[]{"check", "--format=json", "--diagnostic-detail=agent", "--verify=smt", "--smt-backend=jvm", file.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "Early return violating ensures(result == 0) must fail SMT verification: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void secretInitializerLiteralIsRedactedInAgentDiagnosticSnippets() throws Exception {
        Path file = tempDir.resolve("secret_snippet.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn leakTest() -> Int effects() {
                val credential: Secret<String> = "confidential-token-98765";
                return 0;
            }
            """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
            new String[]{"check", "--format=json", "--diagnostic-detail=agent", file.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertNotEquals(0, code);
        assertFalse(stdout.contains("confidential-token-98765"), "Diagnostic must redact secret literal: " + stdout);
        assertTrue(stdout.contains("Secret(****)"), "Diagnostic must mask secret literal with Secret(****): " + stdout);
    }
}
