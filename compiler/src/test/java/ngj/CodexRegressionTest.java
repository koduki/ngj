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

    @Test
    void protocolTransitionEffectsAreEnforcedInCaller() throws Exception {
        Path file = tempDir.resolve("proto_effect.ngj");
        Files.writeString(file, """
            package test.codex;

            protocol PaymentFlow {
                initial Start;
                states Start, Done;
                final Done;
                transition charge(Start) -> Done effects(PaymentWrite);
            }

            public fn pureCaller(consume tok: PaymentFlow<Start>) -> PaymentFlow<Done> effects() {
                return PaymentFlow.charge(consume tok);
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
        assertEquals(1, code, "Calling PaymentWrite transition from effects() must fail with NGJ-EFFECT-001: " + stdout);
        assertTrue(stdout.contains("NGJ-EFFECT-001"), "Expected NGJ-EFFECT-001: " + stdout);
    }

    @Test
    void crossProtocolTokenTransitionIsRejected() throws Exception {
        Path file = tempDir.resolve("cross_proto.ngj");
        Files.writeString(file, """
            package test.codex;

            @must_resolve
            protocol A {
                initial Start;
                states Start, Done;
                final Done;
                transition finish(Start) -> Done effects();
            }

            @must_resolve
            protocol B {
                initial Start;
                states Start, Done;
                final Done;
                transition finish(Start) -> Done effects();
            }

            public fn bad(consume a: A<Start>) -> B<Done> effects() {
                return B.finish(consume a);
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
        assertEquals(1, code, "Passing A<Start> to B.finish must fail: " + stdout);
        assertTrue(stdout.contains("NGJ-PROTO-004") || stdout.contains("NGJ-PROTO-002"), "Expected protocol error: " + stdout);
    }

    @Test
    void protocolTransitionWithoutExplicitConsumeInvalidatesSourceToken() throws Exception {
        Path file = tempDir.resolve("proto_implicit_consume.ngj");
        Files.writeString(file, """
            package test.codex;

            protocol A {
                initial Start;
                states Start, Done;
                final Done;
                transition finish(Start) -> Done effects();
            }

            public fn bad(consume a: A<Start>) -> A<Done> effects() {
                val d1 = A.finish(a);
                val d2 = A.finish(a);
                return d2;
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
        assertEquals(1, code, "Second transition on same token must fail: " + stdout);
        assertTrue(stdout.contains("NGJ-PROTO-004") || stdout.contains("NGJ-CAP-002") || stdout.contains("NGJ-PROTO-002"),
            "Expected protocol/capability reuse error: " + stdout);
    }

    @Test
    void budgetSpendRevokesReceiverOwnership() throws Exception {
        Path file = tempDir.resolve("budget_double_spend.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(consume b: Budget<ApiQuota>) -> Unit effects() {
                val b1: Budget<ApiQuota> = b.spend(1L);
                val b2: Budget<ApiQuota> = b.spend(1L);
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
        assertEquals(1, code, "Successive b.spend(1L) must fail with NGJ-CAP-002: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-002"), "Expected NGJ-CAP-002: " + stdout);
    }

    @Test
    void conditionalExpressionPreservesUntrustedTaint() throws Exception {
        Path file = tempDir.resolve("cond_taint.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(flag: Untrusted<Option<Int>>) -> Unit effects(PaymentWrite) {
                val selected = if (true) { flag } else { flag };
                if (isSome(selected)) {
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
        assertEquals(1, code, "Untrusted flag flowing through if-expr must trigger NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void tupleDestructurePropagatesUntrustedTaint() throws Exception {
        Path file = tempDir.resolve("tuple_taint.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(flag: Untrusted<Option<Int>>) -> Unit effects(PaymentWrite) {
                val (selected, other) = (isSome(flag), 0);
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
        assertEquals(1, code, "Tuple destructured taint must trigger NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void whileLoopRechecksGuardAfterBodyTaint() throws Exception {
        Path file = tempDir.resolve("while_taint.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(flag: Untrusted<Option<Int>>) -> Unit effects(PaymentWrite) {
                var selected: Bool = true;
                while (selected) {
                    pay();
                    selected = isSome(flag);
                };
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
        assertEquals(1, code, "Loop body tainting guard must trigger NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void falseInvariantIsRejectedBeforeAssuming() throws Exception {
        Path file = tempDir.resolve("false_invariant.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn positive(x: Int) -> Int effects()
                requires (x > 0)
                ensures (result > 0)
            {
                return x;
            }

            public fn bad() -> Int effects() {
                invariant (false);
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
        assertEquals(1, code, "False invariant must fail verification: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void conditionalExpressionWithLocalBindingIsModeledInVc() throws Exception {
        Path file = tempDir.resolve("cond_local_vc.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(x: Int) -> Int effects()
                requires (true)
                ensures (result == 0)
            {
                val y: Int = 0;
                return if (x > 0) {
                    val y: Int = 1;
                    y
                } else {
                    0
                };
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
        assertEquals(1, code, "If-expr with local val y = 1 must fail ensures(result == 0): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void protocolTransitionWithPrivilegedEffectUnderUntrustedPcIsRejected() throws Exception {
        Path file = tempDir.resolve("proto_untrusted_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            protocol PaymentFlow {
                initial Start;
                states Start, Done;
                final Done;
                transition charge(Start) -> Done effects(PaymentWrite);
            }

            public fn bad(flag: Untrusted<Option<Int>>, consume a: PaymentFlow<Start>) -> PaymentFlow<Done> effects(PaymentWrite) {
                if (isSome(flag)) {
                    return PaymentFlow.charge(consume a);
                }
                return PaymentFlow.charge(consume a);
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
        assertEquals(1, code, "Protocol transition with PaymentWrite under Untrusted PC must fail with NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void boundaryCodecRejectsOverflowingIntBeforeNarrowing() {
        assertThrows(IllegalArgumentException.class, () -> ngj.runtime.BoundaryCodec.requireInt(4294967297L));
        assertEquals(8443, ngj.runtime.BoundaryCodec.requireInt(8443L));
    }

    @Test
    void forLoopBodyUnderUntrustedPcIsRejected() throws Exception {
        Path file = tempDir.resolve("for_untrusted_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(flag: Untrusted<Option<Int>>, xs: List<Int>) -> Unit effects(PaymentWrite) {
                if (isSome(flag)) {
                    for (x <- xs) {
                        pay();
                    };
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
        assertEquals(1, code, "for-loop calling pay() under Untrusted PC must fail with NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void nestedBlockMutationUpdatesPreconditionContext() throws Exception {
        Path file = tempDir.resolve("block_mutation_pre.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn positive(x: Int) -> Int effects()
                requires (x > 0)
                ensures (result > 0)
            {
                return x;
            }

            public fn bad() -> Int effects() {
                var y: Int = 1;
                {
                    y = 0;
                };
                return positive(y);
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
        assertEquals(1, code, "Nested block assigning y = 0 must invalidate y > 0 at positive(y): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void nestedBlockMutationFailsPostconditionVc() throws Exception {
        Path file = tempDir.resolve("block_mutation_post.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad() -> Int effects()
                requires (true)
                ensures (result == 1)
            {
                var y: Int = 1;
                {
                    y = 0;
                };
                return y;
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
        assertEquals(1, code, "Nested block assigning y = 0 must fail ensures(result == 1): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void specPredicateConditionalBodySubstitutesParams() throws Exception {
        Path file = tempDir.resolve("spec_cond_subst.ngj");
        Files.writeString(file, """
            package test.codex;

            public spec predicate isZero(x: MathInt) {
                if (true) { x == 0 } else { false }
            }

            public fn bad(x: Int) -> Int effects()
                requires (x == 0)
                ensures (isZero(result))
            {
                return 1;
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
        assertEquals(1, code, "isZero(result) must substitute x->result inside if-branch and fail for return 1: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void conditionalBudgetAliasRevokesOwnership() throws Exception {
        Path file = tempDir.resolve("cond_budget_alias.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(consume b: Budget<Fuel>) -> Unit effects() {
                val c: Budget<Fuel> = if (true) { b } else { b };
                val b1: Budget<Fuel> = b.spend(1L);
                val c1: Budget<Fuel> = c.spend(1L);
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
        assertEquals(1, code, "Spending both b and conditional alias c must fail with NGJ-CAP-002: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-002"), "Expected NGJ-CAP-002: " + stdout);
    }

    @Test
    void specPredicateInliningAvoidsVariableCapture() throws Exception {
        Path file = tempDir.resolve("spec_capture_avoid.ngj");
        Files.writeString(file, """
            package test.codex;

            public spec predicate zero(a: MathInt) {
                val x: MathInt = 0;
                a == x
            }

            public fn bad(x: Int) -> Int effects()
                requires (x == 1)
                ensures (zero(x))
            {
                return 1;
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
        assertEquals(1, code, "Inlining zero(x) must not capture caller x with local val x = 0: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void branchAssignedBudgetAliasRevokesOwnership() throws Exception {
        Path file = tempDir.resolve("branch_alias_budget.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(consume other: Budget<Fuel>, consume b: Budget<Fuel>) -> Unit effects() {
                var c: Budget<Fuel> = other;
                if (true) {
                    c = b;
                }
                val b1: Budget<Fuel> = b.spend(1L);
                val c1: Budget<Fuel> = c.spend(1L);
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
        assertEquals(1, code, "Branch-assigned alias c = b must be invalidated when b is spent: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-002"), "Expected NGJ-CAP-002: " + stdout);
    }

    @Test
    void nestedBlockEarlyReturnFailsPostconditionVc() throws Exception {
        Path file = tempDir.resolve("nested_block_return.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad() -> Int effects()
                requires (true)
                ensures (result == 0)
            {
                {
                    return 1;
                };
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
        assertEquals(1, code, "Nested block early return 1 must fail ensures(result == 0): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void shortCircuitAndOrPropagatesUntrustedPc() throws Exception {
        Path file = tempDir.resolve("short_circuit_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn pay() -> Bool effects(PaymentWrite) {
                return true;
            }

            public fn bad(flag: Untrusted<Option<Int>>) -> Bool effects(PaymentWrite) {
                val selected: Bool = isSome(flag) && pay();
                return selected;
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
        assertEquals(1, code, "Short-circuit && with Untrusted left operand must reject pay(): " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void tupleDestructureShadowingUpdatesProofContext() throws Exception {
        Path file = tempDir.resolve("tuple_destructure_pre.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn positive(x: Int) -> Int effects()
                requires (x > 0)
                ensures (result > 0)
            {
                return x;
            }

            public fn bad(x: Int) -> Int effects()
                requires (x == 1)
            {
                val (x: Int, other: Int) = (0, 0);
                return positive(x);
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
        assertEquals(1, code, "Tuple destructuring shadowing x = 0 must fail positive(x): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void tupleDestructureShadowingUpdatesPostconditionVc() throws Exception {
        Path file = tempDir.resolve("tuple_destructure_post.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(x: Int) -> Int effects()
                requires (x == 1)
                ensures (result == 1)
            {
                val (x: Int, other: Int) = (0, 0);
                return x;
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
        assertEquals(1, code, "Tuple destructuring shadowing x = 0 must fail ensures(result == 1): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void tupleConstructionAndListOfExecuteWithoutIncompatibleClassChangeError() throws Exception {
        String source = """
            package test.codex.tupleexec;

            public fn firstOfTuple() -> Int effects() {
                val (x: Int, other: Int) = (42, 7);
                val xs: List<Int> = listOf(1, 2);
                val empty: List<Int> = Nil();
                return x;
            }
            """;
        var lexer = new ngj.parser.Lexer(source, "tupleexec.ngj");
        var parser = new ngj.parser.Parser(lexer, "tupleexec.ngj");
        var unit = parser.parse();
        var resolver = new ngj.typechecker.NameResolver();
        resolver.resolve(unit);
        var tc = new ngj.typechecker.TypeChecker();
        tc.check(unit);

        var gen = new ngj.codegen.Java25CodeGenerator();
        Path outDir = tempDir.resolve("tupleexec_out");
        Files.createDirectories(outDir);
        gen.setOutputDir(outDir);
        var outputs = gen.generate(unit);
        Path fnClassPath = outputs.stream()
            .filter(p -> p.toString().endsWith("firstOfTuple$Fn.class"))
            .findFirst()
            .orElseThrow();
        byte[] fnBytes = Files.readAllBytes(fnClassPath);

        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("test.codex.tupleexec.firstOfTuple$Fn")) {
                    return defineClass(name, fnBytes, 0, fnBytes.length);
                }
                return super.findClass(name);
            }
        };
        Class<?> clazz = loader.loadClass("test.codex.tupleexec.firstOfTuple$Fn");
        Object result = clazz.getMethod("invoke").invoke(null);
        assertEquals(42, result);
    }

    @Test
    void whileConditionPreconditionIsRecheckedAgainstLoopHeaderState() throws Exception {
        Path file = tempDir.resolve("while_cond_pre.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn positive(x: Int) -> Bool effects()
                requires (x > 0)
            {
                return x == 1;
            }

            public fn bad() -> Unit effects() {
                var y: Int = 1;
                while (positive(y)) {
                    y = 0;
                };
                return ();
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
        assertEquals(1, code, "while (positive(y)) { y = 0; } must fail loop-header precondition check: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void forLoopVariableShadowsOuterProofBinding() throws Exception {
        Path file = tempDir.resolve("for_shadow_pre.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn positive(x: Int) -> Int effects()
                requires (x > 0)
                ensures (result > 0)
            {
                return x;
            }

            public fn bad(x: Int, xs: List<Int>) -> Unit effects()
                requires (x == 1)
            {
                for (x <- xs) {
                    val ignored: Int = positive(x);
                };
                return ();
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
        assertEquals(1, code, "for (x <- xs) must shadow outer x == 1 and fail positive(x): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void matchPatternBindingShadowsOuterProofBinding() throws Exception {
        Path file = tempDir.resolve("match_shadow_pre.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn positive(x: Int) -> Int effects()
                requires (x > 0)
                ensures (result > 0)
            {
                return x;
            }

            public fn bad(x: Int, opt: Option<Int>) -> Int effects()
                requires (x == 1)
            {
                return match (opt) {
                    case Some(x) => { positive(x) }
                    case None() => { 1 }
                };
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
        assertEquals(1, code, "match Some(x) must shadow outer x == 1 and fail positive(x): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void nestedBoundarySchemaRecordsVerifyAndRoundTripWithoutVerifyError() throws Exception {
        String source = """
            package test.codex.nestedschema;

            @boundary_schema
            public record Inner(value: Int[1..100]);

            @boundary_schema
            public record Outer(inner: Inner);
            """;
        var lexer = new ngj.parser.Lexer(source, "nestedschema.ngj");
        var parser = new ngj.parser.Parser(lexer, "nestedschema.ngj");
        var unit = parser.parse();
        var resolver = new ngj.typechecker.NameResolver();
        resolver.resolve(unit);
        var tc = new ngj.typechecker.TypeChecker();
        tc.check(unit);

        var gen = new ngj.codegen.Java25CodeGenerator();
        Path outDir = tempDir.resolve("nestedschema_out");
        Files.createDirectories(outDir);
        gen.setOutputDir(outDir);
        var outputs = gen.generate(unit);

        java.util.Map<String, byte[]> classMap = new java.util.LinkedHashMap<>();
        for (Path p : outputs) {
            byte[] raw = Files.readAllBytes(p);
            byte[] patched = raw.clone();
            // Clear preview minor version (65535 -> 0) and clamp major to host JDK 25 (69) for host verification
            patched[4] = 0;
            patched[5] = 0;
            patched[6] = 0;
            patched[7] = 69;
            var cm = java.lang.classfile.ClassFile.of().parse(raw);
            String fqcn = cm.thisClass().asInternalName().replace('/', '.');
            classMap.put(fqcn, patched);
        }

        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classMap.get(name);
                if (b != null) {
                    return defineClass(name, b, 0, b.length);
                }
                return super.findClass(name);
            }
        };
        Class<?> outerClass = loader.loadClass("test.codex.nestedschema.Outer");
        Object okRes = outerClass.getMethod("decodeJson", String.class).invoke(null, "{\"inner\":{\"value\":42}}");
        assertTrue(okRes.getClass().getSimpleName().endsWith("$Ok"), "Expected Outer$Ok, got: " + okRes.getClass());
        Object outerObj = okRes.getClass().getField("value").get(okRes);

        String json = (String) outerClass.getMethod("toJson").invoke(outerObj);
        assertEquals("{\"inner\":{\"value\":42}}", json);

        byte[] bin = (byte[]) outerClass.getMethod("encodeBinary").invoke(outerObj);
        Object binOkRes = outerClass.getMethod("decodeBinary", byte[].class).invoke(null, (Object) bin);
        assertTrue(binOkRes.getClass().getSimpleName().endsWith("$Ok"), "Expected Outer$Ok from binary decode");
        Object binOuterObj = binOkRes.getClass().getField("value").get(binOkRes);
        assertEquals(outerObj, binOuterObj);

        Object badRes = outerClass.getMethod("decodeJson", String.class).invoke(null, "{\"inner\":{\"value\":0}}");
        assertTrue(badRes.getClass().getSimpleName().endsWith("$Err"), "Out-of-range nested Inner value=0 must return Err");
    }

    @Test
    void nonlocalReturnInsideValInitializerFailsPostconditionVc() throws Exception {
        Path file = tempDir.resolve("nonlocal_return_val_init.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(x: Int) -> Int effects()
                ensures (result == 0)
            {
                val ignored: Int = if (x > 0) {
                    return 1;
                } else {
                    0
                };
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
        assertEquals(1, code, "Nonlocal return inside val initializer must not satisfy ensures(result == 0): " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void assumeAfterEarlyReturnIfDoesNotLeakIntoThenBranch() throws Exception {
        Path file = tempDir.resolve("assume_after_early_return.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(x: Int) -> Int effects()
                ensures (result == 0)
            {
                if (x > 0) {
                    return 1;
                }
                assume (x == 0);
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
        assertEquals(1, code, "assume(x == 0) after early return must not leak into x > 0 branch: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void typeAliasForUntrustedEnforcesSelectivePcGuard() throws Exception {
        Path file = tempDir.resolve("alias_untrusted_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            public type Input = Untrusted<Option<Int>>;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(flag: Input) -> Unit effects(PaymentWrite) {
                if (isSome(flag)) {
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
        assertEquals(1, code, "Type alias for Untrusted must trigger NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void genericTypeAliasForUntrustedEnforcesSelectivePcGuard() throws Exception {
        Path file = tempDir.resolve("generic_alias_untrusted_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            public type Id<T> = T;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(flag: Id<Untrusted<Option<Int>>>) -> Unit effects(PaymentWrite) {
                if (isSome(flag)) {
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
        assertEquals(1, code, "Generic type alias for Untrusted must trigger NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void typeAliasForReadArenaRejectedAsUpdate() throws Exception {
        Path file = tempDir.resolve("alias_read_to_update.ngj");
        Files.writeString(file, """
            package test.codex;

            public type ReadArena = read Arena;
            public type MutArena = update Arena;

            public fn mutate(a: MutArena) -> Unit effects() {
                return ();
            }

            public fn bad(a: ReadArena) -> Unit effects() {
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
        assertEquals(1, code, "Passing ReadArena alias to MutArena alias must fail with NGJ-SEP-001: " + stdout);
        assertTrue(stdout.contains("NGJ-SEP-001"), "Expected NGJ-SEP-001: " + stdout);
    }

    @Test
    void typeAliasForMathIntRejectedInExecutableCode() throws Exception {
        Path file = tempDir.resolve("alias_math_int.ngj");
        Files.writeString(file, """
            package test.codex;

            public type GhostInt = MathInt;

            public fn bad(x: GhostInt) -> Int effects() {
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
        assertEquals(1, code, "Type alias for MathInt in executable code must fail with NGJ-PROOF-002: " + stdout);
        assertTrue(stdout.contains("NGJ-PROOF-002"), "Expected NGJ-PROOF-002: " + stdout);
    }

    @Test
    void recordWithUntrustedFieldEnforcesSelectivePcGuard() throws Exception {
        Path file = tempDir.resolve("record_untrusted_field_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            public record Input(flag: Untrusted<Option<Int>>);

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(input: Input) -> Unit effects(PaymentWrite) {
                if (isSome(input.flag)) {
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
        assertEquals(1, code, "Record field of Untrusted type must trigger NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void nestedRecordAndAliasWithUntrustedFieldEnforcesSelectivePcGuard() throws Exception {
        Path file = tempDir.resolve("nested_record_alias_untrusted_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            public type FlagAlias = Untrusted<Option<Int>>;
            public record Inner(flag: FlagAlias);
            public record Outer(inner: Inner);

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(outer: Outer) -> Unit effects(PaymentWrite) {
                if (isSome(outer.inner.flag)) {
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
        assertEquals(1, code, "Nested record field of aliased Untrusted type must trigger NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void recordWithSafeFieldAllowsPrivilegedCall() throws Exception {
        Path file = tempDir.resolve("record_safe_sibling_field.ngj");
        Files.writeString(file, """
            package test.codex;

            public record MixedInput(flag: Untrusted<Option<Int>>, safe: Bool);

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn ok(input: MixedInput) -> Unit effects(PaymentWrite) {
                if (input.safe) {
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
        assertEquals(0, code, "Safe sibling field on record with an Untrusted field must still pass: " + stdout);
    }

    @Test
    void secretInitializerThroughTypeAliasIsRedactedInDiagnostics() throws Exception {
        Path file = tempDir.resolve("secret_alias_redaction.ngj");
        Files.writeString(file, """
            package test.codex;

            public type Hidden = Secret<String>;

            public fn bad() -> Unit effects() {
                val value: Hidden = "confidential-value-98765";
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
        assertEquals(1, code, "Type mismatch on Hidden = String must fail: " + stdout);
        assertFalse(stdout.contains("confidential-value-98765"),
            "Diagnostic must not leak confidential literal declared through Secret alias: " + stdout);
        assertTrue(stdout.contains("Secret(****)"),
            "Diagnostic must mask aliased Secret literal with Secret(****): " + stdout);
    }

    @Test
    void secretIntInitializerThroughGenericTypeAliasIsRedactedInDiagnostics() throws Exception {
        Path file = tempDir.resolve("secret_generic_alias_int_redaction.ngj");
        Files.writeString(file, """
            package test.codex;

            public type BoxedSecret<T> = Secret<T>;

            public fn bad() -> Unit effects() {
                val pin: BoxedSecret<Int> = 9876543;
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
        assertEquals(1, code, "Type mismatch on BoxedSecret<Int> = Int must fail: " + stdout);
        assertFalse(stdout.contains("9876543"),
            "Diagnostic must not leak secret integer literal declared through generic Secret alias: " + stdout);
        assertTrue(stdout.contains("Secret(****)"),
            "Diagnostic must mask aliased Secret int literal with Secret(****): " + stdout);
    }

    @Test
    void recordWithSecretFieldRejectedInDirectBinaryOp() throws Exception {
        Path file = tempDir.resolve("record_secret_field_op.ngj");
        Files.writeString(file, """
            package test.codex;

            public record Holder(tok: Secret<Int>);

            public fn bad(h: Holder) -> Int effects() {
                return h.tok + 1;
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
        assertEquals(1, code, "Direct binary op on record Secret field must fail with NGJ-CAP-003: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-003"), "Expected NGJ-CAP-003: " + stdout);
    }

    @Test
    void certifySpecRejectsAssumeInsideIfExpressionStatement() throws Exception {
        Path file = tempDir.resolve("certify_nested_if_assume.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad() -> Int effects()
                ensures (result == 0)
            {
                if (true) {
                    assume (false);
                }
                return 1;
            }
            """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
            new String[]{"certify-spec", "--format=json", "--symbol=bad", file.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "assume(false) inside if statement must fail certify-spec: " + stdout);
        assertTrue(stdout.contains("\"certified\": false"), "Expected certified=false: " + stdout);
        assertTrue(stdout.contains("NGJ-CERT-ASSUME-PRESENT"), "Expected NGJ-CERT-ASSUME-PRESENT: " + stdout);
    }

    @Test
    void certifySpecRejectsAssumeInsideValInitializerBlock() throws Exception {
        Path file = tempDir.resolve("certify_val_block_assume.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad() -> Int effects()
                ensures (result == 0)
            {
                val ignored: Int = {
                    assume (false);
                    0
                };
                return 1;
            }
            """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
            new String[]{"certify-spec", "--format=json", "--symbol=bad", file.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "assume(false) inside val block initializer must fail certify-spec: " + stdout);
        assertTrue(stdout.contains("\"certified\": false"), "Expected certified=false: " + stdout);
        assertTrue(stdout.contains("NGJ-CERT-ASSUME-PRESENT"), "Expected NGJ-CERT-ASSUME-PRESENT: " + stdout);
    }

    @Test
    void certifySpecRejectsInvalidSourceWithEffectError() throws Exception {
        Path file = tempDir.resolve("certify_effect_error.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad() -> Int effects()
                ensures (result == 0)
            {
                pay();
                return 0;
            }
            """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
            new String[]{"certify-spec", "--format=json", "--symbol=bad", file.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "certify-spec must fail when initial compiler check has NGJ-EFFECT-001: " + stdout);
        assertTrue(stdout.contains("\"certified\": false"), "Expected certified=false: " + stdout);
        assertTrue(stdout.contains("NGJ-EFFECT-001"), "Expected NGJ-EFFECT-001 in certify-spec diagnostics: " + stdout);
    }

    @Test
    void certifySpecRejectsInvalidSourceWithTypeError() throws Exception {
        Path file = tempDir.resolve("certify_type_error.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(x: Int) -> Int effects()
                ensures (result == 0)
            {
                val wrong: Int = "not-an-int";
                return 0;
            }
            """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
            new String[]{"certify-spec", "--format=json", "--symbol=bad", file.toString()},
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, code, "certify-spec must fail when initial compiler check has type errors: " + stdout);
        assertTrue(stdout.contains("\"certified\": false"), "Expected certified=false: " + stdout);
        assertTrue(stdout.contains("NGJ-TYPE-"), "Expected NGJ-TYPE-* diagnostic in certify-spec output: " + stdout);
    }

    @Test
    void inferredLongLocalPreservesLongMinValueNegationOverflow() throws Exception {
        Path file = tempDir.resolve("inferred_long_neg.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(x: Long) -> Long effects()
                requires (x == -9223372036854775807L - 1L)
                ensures (result > 0L)
            {
                val y = x;
                return -y;
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
        assertEquals(1, code, "Inferred Long val y = x; return -y; must model Long.MIN_VALUE overflow: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void inferredLongLocalInCallSitePreconditionPreservesLongOverflow() throws Exception {
        Path file = tempDir.resolve("inferred_long_callsite_neg.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn positiveLong(n: Long) -> Long effects()
                requires (n > 0L)
                ensures (result > 0L)
            {
                return n;
            }

            public fn bad(x: Long) -> Long effects()
                requires (x == -9223372036854775807L - 1L)
            {
                val y = x;
                return positiveLong(-y);
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
        assertEquals(1, code, "Inferred Long val y = x; positiveLong(-y) must fail precondition: " + stdout);
        assertTrue(stdout.contains("NGJ-VERIFY-001"), "Expected NGJ-VERIFY-001: " + stdout);
    }

    @Test
    void forLoopMustResolveProtocolAbandonRejected() throws Exception {
        Path file = tempDir.resolve("for_proto_abandon.ngj");
        Files.writeString(file, """
            package test.codex;

            @must_resolve
            protocol A {
                initial Start;
                states Start, Done;
                final Done;
                transition finish(Start) -> Done effects();
            }

            public fn bad(xs: List<Int>) -> Unit effects() {
                for (x <- xs) {
                    val a = A.start();
                };
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
        assertEquals(1, code, "Unresolved @must_resolve token inside for loop must fail with NGJ-PROTO-002: " + stdout);
        assertTrue(stdout.contains("NGJ-PROTO-002"), "Expected NGJ-PROTO-002: " + stdout);
    }

    @Test
    void whileLoopMustResolveProtocolAbandonRejected() throws Exception {
        Path file = tempDir.resolve("while_proto_abandon.ngj");
        Files.writeString(file, """
            package test.codex;

            @must_resolve
            protocol A {
                initial Start;
                states Start, Done;
                final Done;
                transition finish(Start) -> Done effects();
            }

            public fn bad(flag: Bool) -> Unit effects() {
                while (flag) {
                    val a = A.start();
                };
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
        assertEquals(1, code, "Unresolved @must_resolve token inside while loop must fail with NGJ-PROTO-002: " + stdout);
        assertTrue(stdout.contains("NGJ-PROTO-002"), "Expected NGJ-PROTO-002: " + stdout);
    }

    @Test
    void ifBranchMustResolveProtocolAbandonRejected() throws Exception {
        Path file = tempDir.resolve("if_branch_proto_abandon.ngj");
        Files.writeString(file, """
            package test.codex;

            @must_resolve
            protocol A {
                initial Start;
                states Start, Done;
                final Done;
                transition finish(Start) -> Done effects();
            }

            public fn bad(flag: Bool) -> Unit effects() {
                if (flag) {
                    val a = A.start();
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
        assertEquals(1, code, "Unresolved @must_resolve token inside if branch must fail with NGJ-PROTO-002: " + stdout);
        assertTrue(stdout.contains("NGJ-PROTO-002"), "Expected NGJ-PROTO-002: " + stdout);
    }

    @Test
    void discardedMustResolveProtocolExprStmtRejected() throws Exception {
        Path file = tempDir.resolve("discarded_proto_exprstmt.ngj");
        Files.writeString(file, """
            package test.codex;

            @must_resolve
            protocol A {
                initial Start;
                states Start, Done;
                final Done;
                transition finish(Start) -> Done effects();
            }

            public fn bad() -> Unit effects() {
                A.start();
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
        assertEquals(1, code, "Discarded @must_resolve token from A.start() must fail with NGJ-PROTO-002: " + stdout);
        assertTrue(stdout.contains("NGJ-PROTO-002"), "Expected NGJ-PROTO-002: " + stdout);
    }

    @Test
    void tupleVariableDestructurePreservesBudgetAliasRevocation() throws Exception {
        Path file = tempDir.resolve("tuple_var_budget_alias.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(b: Budget<Fuel>) -> Budget<Fuel> effects() {
                val pair = (0, b);
                val (n: Int, c: Budget<Fuel>) = pair;
                val b2: Budget<Fuel> = b.spend(1L);
                val c2: Budget<Fuel> = c.spend(1L);
                return c2;
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
        assertEquals(1, code, "Destructuring tuple variable holding budget must preserve alias revocation: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-002"), "Expected NGJ-CAP-002: " + stdout);
    }

    @Test
    void conditionalAndNestedTupleDestructurePreservesBudgetAliasRevocation() throws Exception {
        Path file = tempDir.resolve("nested_cond_tuple_budget_alias.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(flag: Bool, b1: Budget<Fuel>, b2: Budget<Fuel>) -> Budget<Fuel> effects() {
                val nested = if (flag) { ((0, b1), 1) } else { ((0, b2), 1) };
                val (inner, tag) = nested;
                val (n: Int, c: Budget<Fuel>) = inner;
                val x: Budget<Fuel> = b1.spend(1L);
                val y: Budget<Fuel> = c.spend(1L);
                return y;
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
        assertEquals(1, code, "Conditional nested tuple destructure must preserve alias revocation: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-002"), "Expected NGJ-CAP-002: " + stdout);
    }

    @Test
    void tupleVariableDestructurePreservesReadCapabilityMode() throws Exception {
        Path file = tempDir.resolve("tuple_var_read_cap.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn mutate(a: update Arena) -> Unit effects() {
                return ();
            }

            public fn bad(r: read Arena) -> Unit effects() {
                val pair = (0, r);
                val (n: Int, a: Arena) = pair;
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
        assertEquals(1, code, "Destructuring read Arena through tuple variable must reject update call: " + stdout);
        assertTrue(stdout.contains("NGJ-SEP-001"), "Expected NGJ-SEP-001: " + stdout);
    }

    @Test
    void destructuredBudgetSplitCompilesVerifiesAndExecutesWithoutVerifyError() throws Exception {
        String source = """
            package test.codex.budgetdestructure;

            public fn splitAndReturnLeft(b: Budget<Fuel>) -> Budget<Fuel> effects() {
                val (left: Budget<Fuel>, right: Budget<Fuel>) = b.split(1L);
                return left;
            }

            public fn splitAndSpendLeft(b: Budget<Fuel>) -> Budget<Fuel> effects() {
                val (left: Budget<Fuel>, right: Budget<Fuel>) = b.split(3L);
                return left.spend(1L);
            }
            """;
        var lexer = new ngj.parser.Lexer(source, "budgetdestructure.ngj");
        var parser = new ngj.parser.Parser(lexer, "budgetdestructure.ngj");
        var unit = parser.parse();
        var resolver = new ngj.typechecker.NameResolver();
        resolver.resolve(unit);
        var tc = new ngj.typechecker.TypeChecker();
        tc.check(unit);

        var gen = new ngj.codegen.Java25CodeGenerator();
        Path outDir = tempDir.resolve("budgetdestructure_out");
        Files.createDirectories(outDir);
        gen.setOutputDir(outDir);
        var outputs = gen.generate(unit);

        java.util.Map<String, byte[]> classMap = new java.util.LinkedHashMap<>();
        for (Path p : outputs) {
            byte[] raw = Files.readAllBytes(p);
            byte[] patched = raw.clone();
            patched[4] = 0;
            patched[5] = 0;
            patched[6] = 0;
            patched[7] = 69;
            var cm = java.lang.classfile.ClassFile.of().parse(raw);
            classMap.put(cm.thisClass().asInternalName().replace('/', '.'), patched);
        }

        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classMap.get(name);
                if (b != null) return defineClass(name, b, 0, b.length);
                return super.findClass(name);
            }
        };

        Class<?> fn1 = loader.loadClass("test.codex.budgetdestructure.splitAndReturnLeft$Fn");
        ngj.runtime.Budget res1 = (ngj.runtime.Budget) fn1.getMethod("invoke", ngj.runtime.Budget.class)
            .invoke(null, new ngj.runtime.Budget("Fuel", 10L));
        assertEquals(1L, res1.remaining());

        Class<?> fn2 = loader.loadClass("test.codex.budgetdestructure.splitAndSpendLeft$Fn");
        ngj.runtime.Budget res2 = (ngj.runtime.Budget) fn2.getMethod("invoke", ngj.runtime.Budget.class)
            .invoke(null, new ngj.runtime.Budget("Fuel", 10L));
        assertEquals(2L, res2.remaining());
    }

    @Test
    void destructuredTupleSecretAndStringVerifyAndExecuteWithoutVerifyError() throws Exception {
        String source = """
            package test.codex.secretdestructure;

            public fn extractSecret() -> Secret<String> effects() {
                val pair = ("label", Secret.of("hidden"));
                val (label: String, sec: Secret<String>) = pair;
                return sec;
            }
            """;
        var lexer = new ngj.parser.Lexer(source, "secretdestructure.ngj");
        var parser = new ngj.parser.Parser(lexer, "secretdestructure.ngj");
        var unit = parser.parse();
        var resolver = new ngj.typechecker.NameResolver();
        resolver.resolve(unit);
        var tc = new ngj.typechecker.TypeChecker();
        tc.check(unit);

        var gen = new ngj.codegen.Java25CodeGenerator();
        Path outDir = tempDir.resolve("secretdestructure_out");
        Files.createDirectories(outDir);
        gen.setOutputDir(outDir);
        var outputs = gen.generate(unit);

        java.util.Map<String, byte[]> classMap = new java.util.LinkedHashMap<>();
        for (Path p : outputs) {
            byte[] raw = Files.readAllBytes(p);
            byte[] patched = raw.clone();
            patched[4] = 0;
            patched[5] = 0;
            patched[6] = 0;
            patched[7] = 69;
            var cm = java.lang.classfile.ClassFile.of().parse(raw);
            classMap.put(cm.thisClass().asInternalName().replace('/', '.'), patched);
        }

        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classMap.get(name);
                if (b != null) return defineClass(name, b, 0, b.length);
                return super.findClass(name);
            }
        };

        Class<?> fn = loader.loadClass("test.codex.secretdestructure.extractSecret$Fn");
        ngj.runtime.SecretValue sec = (ngj.runtime.SecretValue) fn.getMethod("invoke").invoke(null);
        assertEquals("Secret(****)", sec.toString());
    }

    @Test
    void loopLocalAliasCannotConsumeOuterBudget() throws Exception {
        Path file = tempDir.resolve("loop_alias_outer_budget.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(b: Budget<Fuel>, xs: List<Int>) -> Unit effects() {
                for (x <- xs) {
                    val c: Budget<Fuel> = b;
                    val next: Budget<Fuel> = c.spend(1L);
                };
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
        assertEquals(1, code, "Loop-local alias c = b must not bypass outer-loop consume check: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-002"), "Expected NGJ-CAP-002: " + stdout);
    }

    @Test
    void loopLocalNestedIfAliasCannotConsumeOuterBudget() throws Exception {
        Path file = tempDir.resolve("while_if_alias_outer_budget.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(flag: Bool, inner: Bool, b: Budget<Fuel>) -> Unit effects() {
                while (flag) {
                    if (inner) {
                        val c: Budget<Fuel> = b;
                        val next: Budget<Fuel> = c.spend(1L);
                    }
                };
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
        assertEquals(1, code, "Nested if alias inside while loop must not consume outer budget: " + stdout);
        assertTrue(stdout.contains("NGJ-CAP-002"), "Expected NGJ-CAP-002: " + stdout);
    }

    @Test
    void callbackWithPrivilegedEffectRejectedUnderUntrustedPc() throws Exception {
        Path file = tempDir.resolve("callback_untrusted_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(flag: Untrusted<Option<Int>>, action: () -> Unit effects(PaymentWrite)) -> Unit effects(PaymentWrite) {
                if (isSome(flag)) {
                    action();
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
        assertEquals(1, code, "Callback with PaymentWrite under Untrusted PC must fail with NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void lambdaVariableWithPrivilegedEffectRejectedUnderUntrustedPc() throws Exception {
        Path file = tempDir.resolve("lambda_var_untrusted_pc.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn pay() -> Unit effects(PaymentWrite) {
                return ();
            }

            public fn bad(flag: Untrusted<Option<Int>>) -> Unit effects(PaymentWrite) {
                val action = (n: Int) => { pay(); };
                if (isSome(flag)) {
                    action(1);
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
        assertEquals(1, code, "Lambda variable invoking pay() under Untrusted PC must fail with NGJ-IFC-PC-001: " + stdout);
        assertTrue(stdout.contains("NGJ-IFC-PC-001"), "Expected NGJ-IFC-PC-001: " + stdout);
    }

    @Test
    void callbackWithUndeclaredEffectRejectedByCapabilityChecker() throws Exception {
        Path file = tempDir.resolve("callback_undeclared_effect.ngj");
        Files.writeString(file, """
            package test.codex;

            public fn bad(action: () -> Unit effects(PaymentWrite)) -> Unit effects() {
                action();
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
        assertEquals(1, code, "Invoking callback with PaymentWrite inside effects() must fail with NGJ-EFFECT-001: " + stdout);
        assertTrue(stdout.contains("NGJ-EFFECT-001"), "Expected NGJ-EFFECT-001: " + stdout);
    }
}
