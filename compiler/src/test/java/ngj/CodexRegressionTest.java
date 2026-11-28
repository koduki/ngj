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

    @Test
    void mutableLocalReassignmentViolatingPostconditionFailsSmtVerification() throws Exception {
        Path file = tempDir.resolve("mutable_ssa_vc.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn f(x: Int) -> Int effects()
                requires (x == 0)
                ensures (result == 0)
            {
                var y: Int = x;
                val z: Int = y;
                y = 1;
                return y - z;
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
        assertEquals(1, code, "Mutable local violating ensures(result == 0) must fail SMT verification: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void nonIntervalRefinementPredicateRejectsViolatingConstant() throws Exception {
        Path file = tempDir.resolve("even_refinement.ngj");
        Files.writeString(file, """
            package test.codex;

            public type Even = {v: Int where v % 2 == 0};

            public fn bad() -> Even effects() {
                return 3;
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
        assertEquals(1, code, "Returning 3 for Even must fail with NGJ-REF-001: " + stdout);
        assertTrue(stdout.contains("NGJ-REF-001"), "Expected NGJ-REF-001: " + stdout);
    }

    @Test
    void dependentRangeBoundRejectsOverapproximatedWidestInterval() throws Exception {
        Path file = tempDir.resolve("dependent_bound.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn requireBound(limit: Int[1..100], idx: Int[0..<limit]) -> Int effects() {
                return idx;
            }

            public fn badCaller(limit: Int[1..100]) -> Int effects() {
                return requireBound(limit, 99);
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
        assertEquals(1, code, "Passing 99 when limit could be 1 must fail with NGJ-REF-001: " + stdout);
        assertTrue(stdout.contains("NGJ-REF-001"), "Expected NGJ-REF-001: " + stdout);
    }

    @Test
    void readCapabilityPassedToUpdateParameterIsRejected() throws Exception {
        Path file = tempDir.resolve("read_to_update.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn mutate(a: update Arena) -> Unit effects() {
                return ();
            }

            public fn bad(a: read Arena) -> Unit effects() {
                mutate(a);
                return ();
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
        assertEquals(1, code, "Passing read Arena to update Arena must fail with NGJ-SEP-001: " + stdout);
        assertTrue(stdout.contains("NGJ-SEP-001"), "Expected NGJ-SEP-001: " + stdout);
    }

    @Test
    void transitiveAliasConsumeIsRejected() throws Exception {
        Path file = tempDir.resolve("transitive_alias.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(consume a: Arena) -> Unit effects() {
                val b = a;
                val c = b;
                Arena.close(consume a);
                Arena.close(consume c);
                return ();
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
        assertEquals(1, code, "Double consume via transitive alias must fail with NGJ-CAP-002: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-002"), "Expected NGJ-CAP-002: " + stdout);
    }

    @Test
    void calleePreconditionViolationAtCallSiteIsRejected() throws Exception {
        Path file = tempDir.resolve("callee_precondition.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn positive(x: Int) -> Int effects()
                requires (x > 0)
                ensures (result > 0)
            {
                return x;
            }

            public fn badCaller() -> Int effects() {
                return positive(0);
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
        assertEquals(1, code, "Calling positive(0) must fail callee requires(x > 0): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void executableIntNegationModelsMinValueOverflow() throws Exception {
        Path file = tempDir.resolve("neg_overflow.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn negPos(x: Int) -> Int effects()
                requires (x < 0)
                ensures (result > 0)
            {
                return -x;
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
        assertEquals(1, code, "-Int.MIN_VALUE overflows to negative, so ensures(result > 0) must fail: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void branchAssignmentTaintMergesIntoEnclosingScope() throws Exception {
        Path file = tempDir.resolve("branch_taint_merge.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(flag: Untrusted<Option<Int>>) -> Unit effects(PaymentWrite) {
                var selected: Bool = false;
                if (true) {
                    selected = isSome(flag);
                }
                if (selected) {
                    pay();
                }
                return ();
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
        assertEquals(1, code, "Tainted assignment inside if branch must persist after branch and reject pay(): " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }
}
