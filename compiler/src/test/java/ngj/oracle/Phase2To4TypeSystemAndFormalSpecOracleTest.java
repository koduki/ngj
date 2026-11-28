package ngj.oracle;

import ngj.cli.ExitCode;
import ngj.cli.Main;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Clean-room spec-derived black-box E2E and contract oracle suite for NGJ Phase 2-4
 * (CAP-02, SEP-01, REF-01, DIAG-01, GATE-01, PROTO-01, BUDGET-01, IFC-03, PROOF-01, CERT-01).
 *
 * Strictly derived from:
 * - docs/specs/phase2-3-4-type-system-protocol-proofs/spec.md
 * - docs/specs/phase2-3-4-type-system-protocol-proofs/acceptance.json
 * - docs/SPECIFICATION.md, docs/EBNF.md, docs/AGENT_TOOLING.md, docs/VALIDATION.md, docs/STDLIB.md
 */
public class Phase2To4TypeSystemAndFormalSpecOracleTest {

    private static final int ACC_IDENTITY = 0x0020;
    private static final int PREVIEW_MINOR_VERSION = 65535;
    private static final Pattern SHA256_REV_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private Path tempDir;
    private final Set<Path> filesToCleanup = new HashSet<>();

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ngj-phase2-4-oracle-");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Path p : filesToCleanup) {
            Files.deleteIfExists(p);
        }
        filesToCleanup.clear();
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        }
    }

    // =========================================================================
    // [AC-1] CAP-02: consume + caps(...) valid usage, NGJ-CAP-002, NGJ-CAP-001
    // =========================================================================

    @Test
    @DisplayName("[AC-1] Accept valid consume + caps(...) usage across exclusive branches")
    void ac1_acceptsValidConsumeAndCaps() throws Exception {
        String source = """
            package oracle.ac1.pos;

            public fn sliceBorrowed(
                raw: read Bytes<caps(a)>,
                offset: Long[0L..raw.len()],
                len: Long[0L..raw.len() - offset]
            ) -> Bytes<caps(a)> effects() {
                return raw.sliceExact(offset, len);
            }

            public fn closeInBranches(flag: Bool, consume a: Arena, consume b: Arena) -> Unit effects() {
                if (flag) {
                    Arena.close(consume a);
                    Arena.close(consume b);
                } else {
                    Arena.close(consume b);
                    Arena.close(consume a);
                }
            }
            """;

        CliRun res = runCli("check", "--format=json", writeFile("ac1_pos.ngj", source).toString());
        assertEquals(ExitCode.SUCCESS, res.exitCode, "Expected valid consume + caps usage: " + res.stdout + "\n" + res.stderr);
        Map<String, Object> json = parseJsonObject(res.stdout);
        assertEquals("valid", json.get("status"));
    }

    @Test
    @DisplayName("[AC-1] Reject stale use, alias use, and branch-merge consumption with NGJ-CAP-002")
    void ac1_rejectsStaleAliasAndBranchMergeConsumption() throws Exception {
        String staleDirect = """
            package oracle.ac1.stale;

            public fn doubleClose(consume a: Arena) -> Unit effects() {
                Arena.close(consume a);
                Arena.close(consume a);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac1_stale.ngj", staleDirect), "NGJ-CAP-002");

        String staleAlias = """
            package oracle.ac1.alias;

            public fn aliasAfterConsume(consume a: Arena) -> Unit effects() {
                val alias = a;
                Arena.close(consume a);
                Arena.close(consume alias);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac1_alias.ngj", staleAlias), "NGJ-CAP-002");

        String branchMerge = """
            package oracle.ac1.merge;

            public fn partialBranchConsume(flag: Bool, consume a: Arena) -> Unit effects() {
                if (flag) {
                    Arena.close(consume a);
                }
                Arena.close(consume a);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac1_merge.ngj", branchMerge), "NGJ-CAP-002");
    }

    @Test
    @DisplayName("[AC-1] Reject escaping local view with NGJ-CAP-001")
    void ac1_rejectsEscapingLocalView() throws Exception {
        String escapingView = """
            package oracle.ac1.escape;

            public fn leakOwnedView(consume owner: OwnedBytes) -> Bytes<caps(owner)> effects() {
                val localView = owner.asBytes();
                return localView;
            }
            """;

        assertCheckInvalidWithDiagnostic(writeFile("ac1_escape.ngj", escapingView), "NGJ-CAP-001");
    }

    // =========================================================================
    // [AC-2] CAP-02: Secret.map/List.map purity, expose NGJ-CAP-003,
    //        Safe Mode NGJ-SAFE-001, unallowlisted @trusted_pure NGJ-CAP-004
    // =========================================================================

    @Test
    @DisplayName("[AC-2] Accept pure Secret.map and List.map closures")
    void ac2_acceptsPureSecretMapAndListMap() throws Exception {
        String source = """
            package oracle.ac2.pos;

            public fn mapPureSecret(sec: Secret<Int>, nums: List<Int>) -> List<Int> effects() {
                val masked: Secret<Int> = Secret.map(sec, (n: Int) => { n + 1 });
                val doubled: List<Int> = List.map(nums, (x: Int) => { x * 2 });
                return doubled;
            }
            """;

        CliRun res = runCli("check", "--format=json", writeFile("ac2_pos.ngj", source).toString());
        assertEquals(ExitCode.SUCCESS, res.exitCode, "Pure Secret.map/List.map should succeed: " + res.stdout);
        Map<String, Object> json = parseJsonObject(res.stdout);
        assertEquals("valid", json.get("status"));
    }

    @Test
    @DisplayName("[AC-2] Reject capability capture, effectful closures, direct Secret exposure, Safe Mode violations, and unallowlisted @trusted_pure")
    void ac2_rejectsImpureClosuresExposeSafeModeAndTrustedPure() throws Exception {
        String capCaptureSecret = """
            package oracle.ac2.capsecret;

            public fn badSecretCapture(sec: Secret<Int>, raw: read Bytes<caps(a)>) -> Secret<Long> effects() {
                return Secret.map(sec, (n: Int) => { raw.len() + n.toLong() });
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac2_capsecret.ngj", capCaptureSecret), "NGJ-CAP-003");

        String capCaptureList = """
            package oracle.ac2.caplist;

            public fn badListCapture(nums: List<Int>, raw: read Bytes<caps(a)>) -> List<Long> effects() {
                return List.map(nums, (n: Int) => { raw.len() + n.toLong() });
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac2_caplist.ngj", capCaptureList), "NGJ-CAP-003");

        String effectfulSecretMap = """
            package oracle.ac2.effsecret;

            public fn audit(n: Int) -> Int effects(DbWrite) {
                return n;
            }

            public fn badEffectfulSecret(sec: Secret<Int>) -> Secret<Int> effects(DbWrite) {
                return Secret.map(sec, (n: Int) => { audit(n) });
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac2_effsecret.ngj", effectfulSecretMap), "NGJ-CAP-003");

        String directExpose = """
            package oracle.ac2.expose;

            public fn leakSecret(sec: Secret<String>) -> String effects() {
                return Secret.expose(sec);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac2_expose.ngj", directExpose), "NGJ-CAP-003");

        String safeModeViolation = """
            package oracle.ac2.safemode;

            @unchecked
            public fn bypassChecks(x: Int) -> Int effects() {
                return x;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac2_safemode.ngj", safeModeViolation), "NGJ-SAFE-001");

        String unallowlistedTrustedPure = """
            package oracle.ac2.trustedpure;

            @trusted_pure
            public fn forgedPure(x: Int) -> Int effects() {
                return x + 1;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac2_trustedpure.ngj", unallowlistedTrustedPure), "NGJ-CAP-004");
    }

    // =========================================================================
    // [AC-3] CAP-02: Effect subtyping (pure inside DbRead) & NGJ-EFFECT-001
    // =========================================================================

    @Test
    @DisplayName("[AC-3] Allow pure body inside effects(DbRead) and reject effect exceedance with NGJ-EFFECT-001")
    void ac3_effectSubtypingAndExceedance() throws Exception {
        String valid = """
            package oracle.ac3.pos;

            public fn pureInDbRead(x: Int) -> Int effects(DbRead) {
                return x + 1;
            }
            """;
        CliRun posRes = runCli("check", "--format=json", writeFile("ac3_pos.ngj", valid).toString());
        assertEquals(ExitCode.SUCCESS, posRes.exitCode, "Pure body inside effects(DbRead) must succeed: " + posRes.stdout);

        String exceedance = """
            package oracle.ac3.neg;

            public fn writeRow(x: Int) -> Int effects(DbWrite) {
                return x;
            }

            public fn readOnlyCaller(x: Int) -> Int effects(DbRead) {
                return writeRow(x);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac3_neg.ngj", exceedance), "NGJ-EFFECT-001");
    }

    // =========================================================================
    // [AC-4] SEP-01: Concurrent read captures, update conflict, close with
    //        live view / unjoined fork (NGJ-SEP-001), orphan fork (NGJ-SEP-002)
    // =========================================================================

    @Test
    @DisplayName("[AC-4] Accept concurrent read captures across forks; reject update conflict, live-view/unjoined close (NGJ-SEP-001), and orphan fork (NGJ-SEP-002)")
    void ac4_structuredConcurrencySeparation() throws Exception {
        String validReads = """
            package oracle.ac4.pos;

            public fn parallelReads(raw: read Bytes<caps(a)>) -> Result<Int, ScopeError> effects() {
                return scope {
                    val t1 = fork { raw.len() };
                    val t2 = fork { raw.len() };
                    val r1 = t1.join();
                    val r2 = t2.join();
                    match (r1) {
                        case Ok(_) => {
                            match (r2) {
                                case Ok(_) => { 2 },
                                case Err(_) => { 1 }
                            }
                        },
                        case Err(_) => { 0 }
                    }
                };
            }
            """;
        CliRun posRes = runCli("check", "--format=json", writeFile("ac4_pos.ngj", validReads).toString());
        assertEquals(ExitCode.SUCCESS, posRes.exitCode, "Concurrent read captures in scope/fork must pass: " + posRes.stdout);

        String concurrentUpdateConflict = """
            package oracle.ac4.updateconflict;

            public fn touchArena(a: update Arena) -> Int effects() {
                return 1;
            }

            public fn conflictingForks(a: update Arena) -> Result<Int, ScopeError> effects() {
                return scope {
                    val t1 = fork { touchArena(a) };
                    val t2 = fork { touchArena(a) };
                    val r1 = t1.join();
                    val r2 = t2.join();
                    1
                };
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac4_updateconflict.ngj", concurrentUpdateConflict), "NGJ-SEP-001");

        String closeWithLiveView = """
            package oracle.ac4.liveview;

            public fn closeWhileViewAlive(consume arena: Arena, view: Bytes<caps(arena)>) -> Long effects() {
                Arena.close(consume arena);
                return view.len();
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac4_liveview.ngj", closeWithLiveView), "NGJ-SEP-001");

        String closeWithUnjoinedFork = """
            package oracle.ac4.unjoined;

            public fn readArena(a: read Arena) -> Int effects() {
                return 1;
            }

            public fn closeBeforeJoin(consume arena: Arena) -> Result<Int, ScopeError> effects() {
                return scope {
                    val t = fork { readArena(arena) };
                    Arena.close(consume arena);
                    val r = t.join();
                    1
                };
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac4_unjoined.ngj", closeWithUnjoinedFork), "NGJ-SEP-001");

        String orphanFork = """
            package oracle.ac4.orphan;

            public fn orphanTask() -> Int effects() {
                val t = fork { 42 };
                return 0;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac4_orphan.ngj", orphanFork), "NGJ-SEP-002");
    }

    // =========================================================================
    // [AC-5] REF-01: Refinements, ranges, type aliases, dependent bounds,
    //        NGJ-REF-002, NGJ-TYPE-026, NGJ-REF-001
    // =========================================================================

    @Test
    @DisplayName("[AC-5] Accept ranges, refinements, type aliases, dependent bounds; reject impure predicates (NGJ-REF-002), cyclic aliases (NGJ-TYPE-026), and unproven bounds (NGJ-REF-001)")
    void ac5_refinementTypesAliasesAndRejections() throws Exception {
        String valid = """
            package oracle.ac5.pos;

            public type Port = Int[1..65535];
            public type NonNegLong = Long[0L..];
            public type EvenPos = {v: Int where v > 0 && v % 2 == 0};

            public fn useRanges(
                p: Port,
                halfOpen: Int[0..<10],
                nonNeg: Int[0..],
                lng: Long[10L..20L],
                even: EvenPos,
                shorthand: Int where shorthand > 0
            ) -> NonNegLong effects() {
                val checkHalf: Int[0..<10] = 9;
                val checkLow: Int[0..] = 0;
                val checkLong: Long[10L..20L] = 15L;
                return lng;
            }

            public fn sliceIdx(limit: Int[1..100], idx: Int[0..<limit]) -> Int[0..<limit] effects() {
                return idx;
            }

            public fn readPayload(
                raw: read Bytes<caps(a)>,
                offset: Long[0L..raw.len()],
                len: Long[0L..raw.len() - offset]
            ) -> Bytes<caps(a)> effects() {
                return raw.sliceExact(offset, len);
            }
            """;
        CliRun posRes = runCli("check", "--format=json", writeFile("ac5_pos.ngj", valid).toString());
        assertEquals(ExitCode.SUCCESS, posRes.exitCode, "All Phase 2 refinement/range constructs should pass: " + posRes.stdout);

        String impureCallPredicate = """
            package oracle.ac5.impure;

            public fn nowTick() -> Int effects(Clock) {
                return 1;
            }

            public fn badRefinement(x: {v: Int where v > nowTick()}) -> Int effects() {
                return x;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac5_impure.ngj", impureCallPredicate), "NGJ-REF-002");

        String impureVarPredicate = """
            package oracle.ac5.impurevar;

            public fn badVarRefinement() -> Int effects() {
                var threshold = 0;
                val x: {v: Int where v > threshold} = 5;
                return x;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac5_impurevar.ngj", impureVarPredicate), "NGJ-REF-002");

        String cyclicAlias = """
            package oracle.ac5.cycle;

            public type Alpha = Beta;
            public type Beta = Alpha;

            public fn id(x: Int) -> Int effects() {
                return x;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac5_cycle.ngj", cyclicAlias), "NGJ-TYPE-026");

        String unprovenHalfOpen = """
            package oracle.ac5.unproven;

            public fn outOfHalfOpen() -> Int[0..<10] effects() {
                return 10;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac5_unproven.ngj", unprovenHalfOpen), "NGJ-REF-001");

        String unprovenDependent = """
            package oracle.ac5.depfail;

            public fn requireLt(limit: Int[1..100], idx: Int[0..<limit]) -> Int effects() {
                return idx;
            }

            public fn badCall(limit: Int[1..100]) -> Int effects() {
                return requireLt(limit, limit);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac5_depfail.ngj", unprovenDependent), "NGJ-REF-001");
    }

    // =========================================================================
    // [AC-6] REF-01: Tier 1 deterministic solver
    // =========================================================================

    @Test
    @DisplayName("[AC-6] Tier 1 solver accepts selfified val, E-graph congruence, guard/match narrowing, and monotonic while loops; rejects violations with NGJ-REF-001")
    void ac6_tier1SolverFeaturesAndRejections() throws Exception {
        String valid = """
            package oracle.ac6.pos;

            public enum Bounded {
                Pos(v: Int[1..100]),
                Zero()
            }

            public fn step(n: Int) -> Int effects() {
                return n + 1;
            }

            public fn testSelfification(x: Int) -> {v: Int where v == x} effects() {
                val alias = x;
                return alias;
            }

            public fn testCongruence(x: Int, y: Int) -> {v: Int where v == step(y)} effects() {
                if (x == y) {
                    val res = step(x);
                    return res;
                } else {
                    return step(y);
                }
            }

            public fn testIfAndEarlyReturnGuard(x: Int) -> Int[1..65535] effects() {
                if (x < 1) {
                    return 80;
                }
                if (x <= 65535) {
                    return x;
                } else {
                    return 443;
                }
            }

            public fn testMatchNarrowing(b: Bounded) -> Int[0..100] effects() {
                return match (b) {
                    case Pos(v) => { v },
                    case Zero() => { 0 }
                };
            }

            public fn testMonotonicWhile(limit: Int[0..1000]) -> Int[0..1000] effects() {
                var i = 0;
                while (i < limit) {
                    val idx: Int[0..<limit] = i;
                    i = idx + 1;
                };
                return i;
            }
            """;
        CliRun posRes = runCli("check", "--format=json", writeFile("ac6_pos.ngj", valid).toString());
        assertEquals(ExitCode.SUCCESS, posRes.exitCode, "Tier 1 solver positive cases must pass: " + posRes.stdout);

        String nonMonotonicLoop = """
            package oracle.ac6.nonmono;

            public fn badLoop(limit: Int[1..100]) -> Int effects() {
                var i = 0;
                while (i < limit) {
                    val idx: Int[0..<limit] = i;
                    i = idx - 1;
                };
                return i;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac6_nonmono.ngj", nonMonotonicLoop), "NGJ-REF-001");

        String violatedGuard = """
            package oracle.ac6.badguard;

            public fn badGuard(x: Int) -> Int[1..100] effects() {
                if (x >= 0 && x <= 100) {
                    return x;
                }
                return 1;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac6_badguard.ngj", violatedGuard), "NGJ-REF-001");
    }

    // =========================================================================
    // [AC-7] REF-01: refine::<T>(expr) compile + runtime Ok/Err execution,
    //        Valhalla record bytecode (no ACC_IDENTITY, minor 65535, <init>)
    // =========================================================================

    @Test
    @DisplayName("[AC-7] refine::<T>(expr) runtime Ok/Err execution and Valhalla record .class without ACC_IDENTITY, minor 65535, and <init> invariant")
    void ac7_refineRuntimeAndValhallaBytecodeInvariant() throws Exception {
        String source = """
            package oracle.ac7.refine;

            public type Port = Int[1..65535];

            public record Endpoint(host: String, port: Port);

            public fn parsePort(raw: Int) -> Result<Port, RefinementError> effects() {
                return refine::<Port>(raw);
            }

            public fn tryPort(raw: Int) -> Int effects() {
                return match (parsePort(raw)) {
                    case Ok(p) => { p },
                    case Err(_) => { -1 }
                };
            }

            public fn makeEndpoint(host: String, port: Port) -> Endpoint effects() {
                return Endpoint(host, port);
            }
            """;

        Path ngjFile = writeFile("ac7_refine.ngj", source);
        CliRun buildRes = runCli("build", "--format=json", "--target=valhalla", ngjFile.toString());
        assertEquals(ExitCode.SUCCESS, buildRes.exitCode, "Build must succeed: " + buildRes.stdout + "\n" + buildRes.stderr);

        Map<String, Object> buildJson = parseJsonObject(buildRes.stdout);
        assertEquals(Boolean.TRUE, buildJson.get("success"));
        List<Path> classPaths = extractOutputPaths(buildJson);
        assertFalse(classPaths.isEmpty(), "Expected generated class files in build output");

        // Locate Endpoint.class and inspect Valhalla class-file format
        Path endpointClassPath = classPaths.stream()
            .filter(p -> p.getFileName().toString().equals("Endpoint.class"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing Endpoint.class in " + classPaths));

        byte[] endpointBytes = Files.readAllBytes(endpointClassPath);
        ClassModel endpointModel = ClassFile.of().parse(endpointBytes);
        assertEquals(PREVIEW_MINOR_VERSION, endpointModel.minorVersion(),
            "Valhalla record .class must set preview minor version 65535");
        assertEquals(0, endpointModel.flags().flagsMask() & ACC_IDENTITY,
            "Valhalla record .class must omit ACC_IDENTITY (0x0020)");

        // Load generated classes and execute refine::<Port>(expr) + Endpoint.<init>
        GeneratedClassLoader loader = loadGeneratedClasses(classPaths);

        Class<?> tryPortFn = loader.loadClass("oracle.ac7.refine.tryPort$Fn");
        Method invokeTryPort = tryPortFn.getMethod("invoke", int.class);
        assertEquals(8080, invokeTryPort.invoke(null, 8080), "refine::<Port>(8080) must return Ok(8080)");
        assertEquals(1, invokeTryPort.invoke(null, 1), "refine::<Port>(1) must return Ok(1)");
        assertEquals(65535, invokeTryPort.invoke(null, 65535), "refine::<Port>(65535) must return Ok(65535)");
        assertEquals(-1, invokeTryPort.invoke(null, 0), "refine::<Port>(0) must return Err");
        assertEquals(-1, invokeTryPort.invoke(null, -42), "refine::<Port>(-42) must return Err");
        assertEquals(-1, invokeTryPort.invoke(null, 65536), "refine::<Port>(65536) must return Err");

        Class<?> endpointClass = loader.loadClass("oracle.ac7.refine.Endpoint");
        Constructor<?> ctor = endpointClass.getConstructor(String.class, int.class);
        Object validEp = ctor.newInstance("localhost", 8080);
        assertNotNull(validEp);
        assertEquals("localhost", endpointClass.getField("host").get(validEp));
        assertEquals(8080, endpointClass.getField("port").get(validEp));

        InvocationTargetException lowEx = assertThrows(InvocationTargetException.class,
            () -> ctor.newInstance("localhost", 0),
            "Endpoint.<init> must enforce Port refinement invariant at runtime for 0");
        assertNotNull(lowEx.getCause());

        InvocationTargetException highEx = assertThrows(InvocationTargetException.class,
            () -> ctor.newInstance("localhost", 70000),
            "Endpoint.<init> must enforce Port refinement invariant at runtime for 70000");
        assertNotNull(highEx.getCause());
    }

    // =========================================================================
    // [AC-8] DIAG-01: ngj.diagnostics/2, human vs agent parity, UTF-8 byte spans,
    //        expr_snippet, derivation_trace, solver_diagnosis, Secret(****), requires_review
    // =========================================================================

    @Test
    @DisplayName("[AC-8] check emits ngj.diagnostics/2 with human/agent parity, UTF-8 byte spans, expr_snippet, derivation_trace, solver_diagnosis, Secret(****), and requires_review")
    void ac8_structuredDiagnosticsHumanAndAgent() throws Exception {
        // Part 1: Multi-byte UTF-8 comment + effect exceedance -> exact UTF-8 spans + requires_review
        String effectSource = """
            package oracle.ac8.eff;

            // 日本語コメント 🚀 multi-byte UTF-8 prefix
            public fn dbInsert() -> Int effects(DbWrite) {
                return 1;
            }

            public fn readPath() -> Int effects(DbRead) {
                return dbInsert();
            }
            """;
        Path effectFile = writeFile("ac8_eff.ngj", effectSource);

        CliRun humanText = runCli("check", "--diagnostic-detail=human", effectFile.toString());
        assertEquals(ExitCode.INVALID, humanText.exitCode);
        assertTrue(humanText.stdout.contains("NGJ-EFFECT-001") || humanText.stderr.contains("NGJ-EFFECT-001"),
            "Human output must emit NGJ-EFFECT-001");

        CliRun agentJsonRun = runCli("check", "--format=json", "--diagnostic-detail=agent", effectFile.toString());
        assertEquals(ExitCode.INVALID, agentJsonRun.exitCode);

        Map<String, Object> agentRoot = parseJsonObject(agentJsonRun.stdout);
        assertEquals("ngj.diagnostics/2", agentRoot.get("schema_version"));
        assertEquals("0.3.0-valhalla", agentRoot.get("compiler_version"));
        assertEquals("agent", agentRoot.get("diagnostic_detail"));
        assertEquals("invalid", agentRoot.get("status"));

        List<Map<String, Object>> diags = asObjectList(agentRoot.get("diagnostics"));
        assertFalse(diags.isEmpty());

        Map<String, Object> effDiag = diags.stream()
            .filter(d -> "NGJ-EFFECT-001".equals(d.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing NGJ-EFFECT-001 in " + diags));

        // UTF-8 byte span verification (must NOT match UTF-16 char offset!)
        byte[] sourceUtf8 = effectSource.getBytes(StandardCharsets.UTF_8);
        String targetCall = "dbInsert()";
        int expectedStartUtf8 = indexOfBytes(sourceUtf8, targetCall.getBytes(StandardCharsets.UTF_8),
            effectSource.indexOf("readPath"));
        assertTrue(expectedStartUtf8 > effectSource.indexOf(targetCall, effectSource.indexOf("readPath")),
            "Test setup sanity: UTF-8 byte offset must exceed UTF-16 char index due to Japanese/emoji comment");

        Map<String, Object> span = asObject(effDiag.get("primary_span"));
        int startByte = asInt(span.get("start_byte"));
        int endByte = asInt(span.get("end_byte"));
        assertTrue(startByte >= expectedStartUtf8 - 10 && startByte <= expectedStartUtf8 + 20,
            "start_byte should reflect UTF-8 offset near " + expectedStartUtf8 + ", got " + startByte);
        assertTrue(endByte > startByte && endByte <= sourceUtf8.length,
            "end_byte must be a valid UTF-8 half-open span: [" + startByte + ", " + endByte + ")");
        String slicedUtf8 = new String(Arrays.copyOfRange(sourceUtf8, startByte, endByte), StandardCharsets.UTF_8);
        assertTrue(slicedUtf8.contains("dbInsert") || slicedUtf8.contains("return"),
            "UTF-8 slice at primary_span should point to offending expression, got: " + slicedUtf8);

        assertNotNull(effDiag.get("expr_snippet"), "agent mode must emit expr_snippet");
        assertFalse(String.valueOf(effDiag.get("expr_snippet")).isBlank());

        List<Map<String, Object>> trace = asObjectList(effDiag.get("derivation_trace"));
        assertFalse(trace.isEmpty(), "agent mode must emit non-empty derivation_trace");

        List<Map<String, Object>> suggestions = asObjectList(effDiag.get("suggestions"));
        assertTrue(suggestions.stream().anyMatch(s -> "requires_review".equals(s.get("applicability"))),
            "Authority-expanding/review hints must have applicability=requires_review: " + suggestions);

        // Part 2: Secret(****) masking + solver_diagnosis on contract/refinement counterexample
        String secretContract = """
            package oracle.ac8.secret;

            public fn checkSecretContract(x: Int, apiSecret: Secret<Int>) -> Int effects()
                requires (x >= 0)
                ensures (result > 100)
            {
                return x + 1;
            }
            """;
        Path secretFile = writeFile("ac8_secret.ngj", secretContract);
        CliRun smtAgentRun = runCli("check", "--format=json", "--diagnostic-detail=agent", "--verify=smt", secretFile.toString());
        assertEquals(ExitCode.INVALID, smtAgentRun.exitCode);
        Map<String, Object> smtRoot = parseJsonObject(smtAgentRun.stdout);
        assertEquals("ngj.diagnostics/2", smtRoot.get("schema_version"));
        List<Map<String, Object>> smtDiags = asObjectList(smtRoot.get("diagnostics"));
        assertFalse(smtDiags.isEmpty());

        Map<String, Object> smtDiag = smtDiags.get(0);
        assertNotNull(smtDiag.get("solver_diagnosis"), "agent mode must emit solver_diagnosis on contract failure");
        Map<String, Object> solverDiag = asObject(smtDiag.get("solver_diagnosis"));
        assertEquals("counterexample_found", solverDiag.get("failure_class"));
        assertNotNull(solverDiag.get("suggested_hint_anchor"));
        assertNotNull(solverDiag.get("counterexample_state"));
        assertTrue(smtAgentRun.stdout.contains("Secret(****)"),
            "Counterexample involving Secret<T> must mask secret as Secret(****): " + smtAgentRun.stdout);

        // Verify human vs agent matching diagnostic IDs on the same source
        CliRun smtHumanJson = runCli("check", "--format=json", "--diagnostic-detail=human", "--verify=smt", secretFile.toString());
        assertEquals(ExitCode.INVALID, smtHumanJson.exitCode);
        List<Map<String, Object>> humanDiags = asObjectList(parseJsonObject(smtHumanJson.stdout).get("diagnostics"));
        assertEquals(
            smtDiags.stream().map(d -> String.valueOf(d.get("id"))).toList(),
            humanDiags.stream().map(d -> String.valueOf(d.get("id"))).toList(),
            "Human and agent diagnostic IDs must match"
        );
    }

    // =========================================================================
    // [AC-9] DIAG-01: ngj.context/2, capability_mode, capture_lifetime,
    //        v0.3 signatures, non-hardcoded digests, --max-bytes truncation
    // =========================================================================

    @Test
    @DisplayName("[AC-9] context emits ngj.context/2 with capability_mode, capture_lifetime, v0.3 signatures, dynamic digests, and --max-bytes truncation")
    void ac9_contextSchemaDigestsAndTruncation() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            package oracle.ac9.ctx;

            public record Packet(size: Long[0L..]);

            public fn helperAlpha(a: Int, b: Int) -> Int effects() { return a + b; }
            public fn helperBeta(raw: read Bytes<caps(a)>, offset: Long[0L..raw.len()], len: Long[0L..raw.len() - offset]) -> Bytes<caps(a)> effects() {
                return raw.sliceExact(offset, len);
            }
            """);
        for (int i = 0; i < 16; i++) {
            sb.append(String.format(
                "public fn auxiliarySymbol%02d(argOne: Int, argTwo: Long, argThree: String) -> Int effects() { return argOne; }\n",
                i
            ));
        }
        sb.append("""
            public fn parseFrame(arena: read Arena, raw: read Bytes<caps(arena)>) -> Bytes<caps(arena)> effects() {
                ?parse_body
            }
            """);

        Path file1 = writeFile("ac9_ctx1.ngj", sb.toString());
        Path file2 = writeFile("ac9_ctx2.ngj", sb + "\n// extra comment changing digest\n");

        CliRun fullRun = runCli("context", "--format=json", "--hole=parse_body", "--max-bytes=32000", file1.toString());
        assertEquals(ExitCode.SUCCESS, fullRun.exitCode, "context must succeed: " + fullRun.stderr);

        Map<String, Object> ctx1 = parseJsonObject(fullRun.stdout);
        assertEquals("ngj.context/2", ctx1.get("schema_version"));
        assertEquals("0.3.0-valhalla", ctx1.get("compiler_version"));
        assertEquals("parse_body", ctx1.get("hole_id"));
        assertEquals(Boolean.FALSE, ctx1.get("truncated"));
        assertEquals(0, asInt(ctx1.get("omitted_symbol_count")));

        String rev1 = String.valueOf(ctx1.get("source_revision"));
        String polDefault = String.valueOf(ctx1.get("policy_digest"));
        assertTrue(SHA256_REV_PATTERN.matcher(rev1).matches(), "Non-hardcoded sha256 source_revision required, got: " + rev1);
        assertTrue(SHA256_REV_PATTERN.matcher(polDefault).matches(), "Non-hardcoded sha256 policy_digest required, got: " + polDefault);
        assertNotEquals("sha256:current", rev1);
        assertNotEquals("sha256:mvp-pure-v1", polDefault);

        // Verify source_revision changes with file content and policy_digest changes with profile
        CliRun runFile2 = runCli("context", "--format=json", "--hole=parse_body", file2.toString());
        assertEquals(ExitCode.SUCCESS, runFile2.exitCode);
        String rev2 = String.valueOf(parseJsonObject(runFile2.stdout).get("source_revision"));
        assertNotEquals(rev1, rev2, "source_revision must reflect actual file content");

        CliRun runRestricted = runCli("context", "--format=json", "--profile=restricted", "--hole=parse_body", file1.toString());
        assertEquals(ExitCode.SUCCESS, runRestricted.exitCode);
        String polRestricted = String.valueOf(parseJsonObject(runRestricted.stdout).get("policy_digest"));
        assertNotEquals(polDefault, polRestricted, "policy_digest must reflect active profile/policy");

        // capability_mode on bindings
        List<Map<String, Object>> bindings = asObjectList(ctx1.get("bindings"));
        Map<String, Object> rawBinding = bindings.stream()
            .filter(b -> "raw".equals(b.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing 'raw' binding in " + bindings));
        assertEquals("read", rawBinding.get("capability_mode"));

        // capture_lifetime obligation
        List<Map<String, Object>> obligations = asObjectList(ctx1.get("obligations"));
        assertTrue(obligations.stream().anyMatch(o -> "capture_lifetime".equals(o.get("kind"))),
            "Expected capture_lifetime obligation in context, got: " + obligations);

        // v0.3 signatures in relevant_symbols
        List<Map<String, Object>> symbols = asObjectList(ctx1.get("relevant_symbols"));
        assertFalse(symbols.isEmpty());
        for (Map<String, Object> sym : symbols) {
            String sig = String.valueOf(sym.get("signature"));
            assertTrue(sig.contains("effects(") && sig.contains("->"),
                "Relevant symbol must use v0.3 signature: " + sig);
            assertFalse(sig.contains("! {") || sig.startsWith("pub "),
                "Relevant symbol must not use v0.2 syntax: " + sig);
        }

        // --max-bytes truncation
        int fullBytes = fullRun.stdout.getBytes(StandardCharsets.UTF_8).length;
        int tightBudget = Math.max(450, fullBytes / 2);
        assertTrue(tightBudget < fullBytes, "Budget must be strictly less than full context size");

        CliRun truncRun = runCli("context", "--format=json", "--hole=parse_body", "--max-bytes=" + tightBudget, file1.toString());
        assertEquals(ExitCode.SUCCESS, truncRun.exitCode, "Truncated context must still succeed: " + truncRun.stderr);
        assertTrue(truncRun.stdout.getBytes(StandardCharsets.UTF_8).length <= tightBudget,
            "context stdout bytes (" + truncRun.stdout.getBytes(StandardCharsets.UTF_8).length + ") must obey --max-bytes=" + tightBudget);

        Map<String, Object> truncJson = parseJsonObject(truncRun.stdout);
        assertEquals(Boolean.TRUE, truncJson.get("truncated"));
        assertTrue(asInt(truncJson.get("omitted_symbol_count")) > 0,
            "omitted_symbol_count must be > 0 when truncated");
    }

    // =========================================================================
    // [AC-10] GATE-01: obligations enumeration & --profile=restricted
    // =========================================================================

    @Test
    @DisplayName("[AC-10] obligations enumerates holes/refinements/capture obligations and --profile=restricted enforces Safe Mode")
    void ac10_obligationsAndRestrictedProfile() throws Exception {
        String sourceWithObligations = """
            package oracle.ac10.obl;

            public type Port = Int[1..65535];

            public fn openPort(arena: read Arena, raw: read Bytes<caps(arena)>, p: Port) -> Bytes<caps(arena)> effects() {
                val slice: Bytes<caps(arena)> = raw;
                val body: Int = ?missing_port_logic;
                return slice;
            }
            """;
        Path oblFile = writeFile("ac10_obl.ngj", sourceWithObligations);

        CliRun oblRun = runCli("obligations", "--format=json", oblFile.toString());
        assertEquals(ExitCode.SUCCESS, oblRun.exitCode, "obligations command must succeed: " + oblRun.stderr);
        Map<String, Object> oblJson = parseJsonObject(oblRun.stdout);
        assertNotNull(oblJson.get("schema_version"));
        assertEquals("0.3.0-valhalla", oblJson.get("compiler_version"));

        List<Map<String, Object>> obligations = asObjectList(oblJson.get("obligations"));
        assertFalse(obligations.isEmpty(), "obligations list must be non-empty");
        String oblDump = oblRun.stdout.toLowerCase();
        assertTrue(oblDump.contains("missing_port_logic") || oblDump.contains("hole"),
            "obligations must enumerate typed holes: " + oblRun.stdout);
        assertTrue(oblDump.contains("refinement") || oblDump.contains("port") || oblDump.contains("1..65535"),
            "obligations must enumerate refinement obligations: " + oblRun.stdout);
        assertTrue(oblDump.contains("capture") || oblDump.contains("arena") || oblDump.contains("lifetime"),
            "obligations must enumerate capture/lifetime obligations: " + oblRun.stdout);

        String validRestricted = """
            package oracle.ac10.restricted.ok;

            public fn cleanAdd(a: Int, b: Int) -> Int effects() {
                return a + b;
            }
            """;
        CliRun restrictedOk = runCli("check", "--format=json", "--profile=restricted",
            writeFile("ac10_restricted_ok.ngj", validRestricted).toString());
        assertEquals(ExitCode.SUCCESS, restrictedOk.exitCode);
        Map<String, Object> restrictedJson = parseJsonObject(restrictedOk.stdout);
        assertEquals("valhalla/restricted", restrictedJson.get("target_profile"));

        String unsafeInRestricted = """
            package oracle.ac10.restricted.bad;

            @unchecked
            public fn escapeHatch(x: Int) -> Int effects() {
                return x;
            }
            """;
        CliRun restrictedBad = runCli("check", "--format=json", "--profile=restricted",
            writeFile("ac10_restricted_bad.ngj", unsafeInRestricted).toString());
        assertEquals(ExitCode.INVALID, restrictedBad.exitCode);
        Map<String, Object> badJson = parseJsonObject(restrictedBad.stdout);
        assertEquals("valhalla/restricted", badJson.get("target_profile"));
        assertTrue(hasDiagnostic(badJson, "NGJ-SAFE-001"),
            "restricted profile must reject Safe Mode violation with NGJ-SAFE-001: " + restrictedBad.stdout);
    }

    // =========================================================================
    // [AC-11] GATE-01: check-patch --policy=strict-gates & all 5 gates
    // =========================================================================

    @Test
    @DisplayName("[AC-11] check-patch --policy=strict-gates checks base revision, preserves disk files, accepts valid patches, and rejects all 5 strict gates")
    void ac11_checkPatchStrictGates() throws Exception {
        String baseMain = """
            package oracle.ac11.demo;

            public spec fn doubleSpec(x: Int) -> Int {
                x * 2
            }

            fn internalCalc(x: Int) -> Int effects() {
                return x * 2;
            }

            public fn compute(x: Int) -> Int effects()
                requires (x >= 0 && x <= 1000)
                ensures (result == doubleSpec(x))
            {
                return internalCalc(x);
            }
            """;
        String baseSibling = """
            package oracle.ac11.demo;

            public fn useInternal(x: Int) -> Int effects() {
                return internalCalc(x);
            }
            """;

        Path mainFile = writeFile("Ac11Main.ngj", baseMain);
        Path siblingFile = writeFile("Ac11Sibling.ngj", baseSibling);
        String baseRevision = fetchRevision(mainFile, siblingFile);
        byte[] mainDiskBefore = Files.readAllBytes(mainFile);
        byte[] siblingDiskBefore = Files.readAllBytes(siblingFile);

        // 1. Mismatched --base-revision -> exit 3
        Path validPatch = writeFile("valid.patch", unifiedDiff(mainFile, baseMain,
            baseMain.replace("return internalCalc(x);", "val doubled: Int = internalCalc(x);\n    return doubled;")));
        CliRun mismatchRev = runCli(
            "check-patch", "--format=json", "--policy=strict-gates",
            "--base-revision=sha256:0000000000000000000000000000000000000000000000000000000000000000",
            "--patch=" + validPatch, mainFile.toString(), siblingFile.toString()
        );
        assertEquals(ExitCode.BAD_INPUT, mismatchRev.exitCode, "Mismatched --base-revision must exit 3");
        assertArrayEquals(mainDiskBefore, Files.readAllBytes(mainFile), "Disk file must remain unchanged");

        // 2. Valid patch preserving contracts, siblings, and 0 obligations -> exit 0, disk files unchanged
        CliRun validRun = runCli(
            "check-patch", "--format=json", "--policy=strict-gates",
            "--base-revision=" + baseRevision,
            "--patch=" + validPatch, mainFile.toString(), siblingFile.toString()
        );
        assertEquals(ExitCode.SUCCESS, validRun.exitCode, "Valid patch must pass strict-gates: " + validRun.stdout + "\n" + validRun.stderr);
        assertArrayEquals(mainDiskBefore, Files.readAllBytes(mainFile), "Disk file must not be modified by check-patch");
        assertArrayEquals(siblingDiskBefore, Files.readAllBytes(siblingFile), "Sibling file must not be modified by check-patch");

        // 3. Gate 1: spec-drift (NGJ-GATE-SPEC-DRIFT)
        Path specDriftPatch = writeFile("spec_drift.patch", unifiedDiff(mainFile, baseMain,
            baseMain.replace("x * 2", "x * 3").replace("return internalCalc(x);", "return x * 3;")));
        assertGateRejects(baseRevision, specDriftPatch, "NGJ-GATE-SPEC-DRIFT", mainFile, siblingFile);
        assertArrayEquals(mainDiskBefore, Files.readAllBytes(mainFile));

        // 4. Gate 2: axiom-drift (NGJ-GATE-AXIOM-DRIFT)
        String withTrustedPure = baseMain.replace(
            "fn internalCalc(x: Int) -> Int effects() {",
            "@trusted_pure\nfn internalCalc(x: Int) -> Int effects() {"
        );
        Path axiomDriftPatch = writeFile("axiom_drift.patch", unifiedDiff(mainFile, baseMain, withTrustedPure));
        assertGateRejects(baseRevision, axiomDriftPatch, "NGJ-GATE-AXIOM-DRIFT", mainFile, siblingFile);
        assertArrayEquals(mainDiskBefore, Files.readAllBytes(mainFile));

        // 5. Gate 3: forbidden-construct (NGJ-GATE-FORBIDDEN)
        String withAssume = baseMain.replace(
            "return internalCalc(x);",
            "proof { assume(x == 0); }\n    return internalCalc(x);"
        );
        Path forbiddenPatch = writeFile("forbidden.patch", unifiedDiff(mainFile, baseMain, withAssume));
        assertGateRejects(baseRevision, forbiddenPatch, "NGJ-GATE-FORBIDDEN", mainFile, siblingFile);
        assertArrayEquals(mainDiskBefore, Files.readAllBytes(mainFile));

        // 6. Gate 4: sibling-check (NGJ-GATE-SIBLING)
        String brokenSibling = baseMain
            .replace("fn internalCalc(x: Int) -> Int effects() {\n    return x * 2;",
                "fn internalCalc(x: Int, extra: Int) -> Int effects() {\n    return x * 2 + extra;")
            .replace("return internalCalc(x);", "return internalCalc(x, 0);");
        Path siblingPatch = writeFile("sibling.patch", unifiedDiff(mainFile, baseMain, brokenSibling));
        assertGateRejects(baseRevision, siblingPatch, "NGJ-GATE-SIBLING", mainFile, siblingFile);
        assertArrayEquals(mainDiskBefore, Files.readAllBytes(mainFile));

        // 7. Gate 5: obligation-monotonicity (NGJ-GATE-OBLIGATION)
        String moreHoles = baseMain.replace(
            "return internalCalc(x);",
            "return ?new_unresolved_hole;"
        );
        Path obligationPatch = writeFile("obligation.patch", unifiedDiff(mainFile, baseMain, moreHoles));
        assertGateRejects(baseRevision, obligationPatch, "NGJ-GATE-OBLIGATION", mainFile, siblingFile);
        assertArrayEquals(mainDiskBefore, Files.readAllBytes(mainFile));
    }

    // =========================================================================
    // [AC-12] PROTO-01: protocol graph checks (NGJ-PROTO-001), consume
    //         transitions, instance identity (NGJ-PROTO-004), @must_resolve (NGJ-PROTO-002)
    // =========================================================================

    @Test
    @DisplayName("[AC-12] protocol validates state graphs, consume transitions, instance identity (NGJ-PROTO-004), and @must_resolve completion across normal/error/? exits (NGJ-PROTO-002)")
    void ac12_protocolDefinitionsAndResolution() throws Exception {
        String validProtocol = """
            package oracle.ac12.pos;

            @must_resolve
            public protocol OrderFlow {
                initial Created;
                states Paid;
                final Fulfilled, Cancelled;

                transition pay(Created) -> Paid effects();
                transition fulfill(Paid) -> Fulfilled effects();
                transition cancel(Created) -> Cancelled effects();
            }

            public fn completeOrder(
                consume flow: OrderFlow<Created>,
                shouldPay: Bool,
                checkStep: Result<Int, String>
            ) -> Result<Int, String> effects() {
                if (!shouldPay) {
                    val done = OrderFlow.cancel(consume flow);
                    return Err("cancelled");
                }
                val paid = OrderFlow.pay(consume flow);
                val fulfilled = OrderFlow.fulfill(consume paid);
                val v = checkStep?;
                return Ok(v);
            }
            """;
        CliRun posRes = runCli("check", "--format=json", writeFile("ac12_pos.ngj", validProtocol).toString());
        assertEquals(ExitCode.SUCCESS, posRes.exitCode, "Valid protocol completion must pass: " + posRes.stdout);

        String unreachableState = """
            package oracle.ac12.unreachable;

            public protocol BadGraph1 {
                initial Start;
                states Orphan;
                final Done;

                transition finish(Start) -> Done effects();
                transition orphanStep(Orphan) -> Done effects();
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac12_unreachable.ngj", unreachableState), "NGJ-PROTO-001");

        String deadEndState = """
            package oracle.ac12.deadend;

            public protocol BadGraph2 {
                initial Start;
                states Trap;
                final Done;

                transition toTrap(Start) -> Trap effects();
                transition finish(Start) -> Done effects();
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac12_deadend.ngj", deadEndState), "NGJ-PROTO-001");

        String missingFinal = """
            package oracle.ac12.nofinal;

            public protocol BadGraph3 {
                initial Start;
                states Mid;

                transition step(Start) -> Mid effects();
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac12_nofinal.ngj", missingFinal), "NGJ-PROTO-001");

        String staleTransition = """
            package oracle.ac12.stale;

            @must_resolve
            public protocol SeqFlow {
                initial Init;
                final Done;

                transition close(Init) -> Done effects();
            }

            public fn doubleTransition(consume f: SeqFlow<Init>) -> Unit effects() {
                val d1 = SeqFlow.close(consume f);
                val d2 = SeqFlow.close(consume f);
            }
            """;
        CliRun staleRun = runCli("check", "--format=json", writeFile("ac12_stale.ngj", staleTransition).toString());
        assertEquals(ExitCode.INVALID, staleRun.exitCode);
        Map<String, Object> staleJson = parseJsonObject(staleRun.stdout);
        assertTrue(hasDiagnostic(staleJson, "NGJ-CAP-002") || hasDiagnostic(staleJson, "NGJ-PROTO-002") || hasDiagnostic(staleJson, "NGJ-PROTO-004"),
            "Double consume transition must be rejected: " + staleRun.stdout);

        String instanceIdentityMismatch = """
            package oracle.ac12.identity;

            @must_resolve
            public protocol TwoPhase {
                initial Start;
                states Prepared;
                final Committed;

                transition prepare(Start) -> Prepared effects();
                transition commit(Prepared) -> Committed effects();
            }

            public fn mixInstances(
                consume f1: TwoPhase<Start>,
                consume f2: TwoPhase<Start>
            ) -> Unit effects() {
                val p1 = TwoPhase.prepare(consume f1);
                val p2 = TwoPhase.prepare(consume f2);
                val mixed = if (true) { p1 } else { p2 };
                val c = TwoPhase.commit(consume mixed);
            }
            """;
        CliRun idRun = runCli("check", "--format=json", writeFile("ac12_identity.ngj", instanceIdentityMismatch).toString());
        assertEquals(ExitCode.INVALID, idRun.exitCode);
        Map<String, Object> idJson = parseJsonObject(idRun.stdout);
        assertTrue(hasDiagnostic(idJson, "NGJ-PROTO-004"),
            "Mixing distinct protocol instance identities must emit NGJ-PROTO-004: " + idRun.stdout);

        String unresolvedPropagateExit = """
            package oracle.ac12.propexit;

            @must_resolve
            public protocol TxFlow {
                initial Open;
                final Committed;

                transition commit(Open) -> Committed effects();
            }

            public fn abandonOnPropagate(
                consume tx: TxFlow<Open>,
                step: Result<Int, String>
            ) -> Result<Int, String> effects() {
                val v = step?;
                val done = TxFlow.commit(consume tx);
                return Ok(v);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac12_propexit.ngj", unresolvedPropagateExit), "NGJ-PROTO-002");

        String unresolvedErrorBranch = """
            package oracle.ac12.errexit;

            @must_resolve
            public protocol TxFlow2 {
                initial Open;
                final Committed;

                transition commit(Open) -> Committed effects();
            }

            public fn abandonOnBranch(consume tx: TxFlow2<Open>, fail: Bool) -> Result<Int, String> effects() {
                if (fail) {
                    return Err("failed early");
                }
                val done = TxFlow2.commit(consume tx);
                return Ok(1);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac12_errexit.ngj", unresolvedErrorBranch), "NGJ-PROTO-002");
    }

    // =========================================================================
    // [AC-13] PROTO-01: refines trace inclusion (NGJ-PROTO-003) &
    //         ngjc inspect --protocol=<id> --format=mermaid
    // =========================================================================

    @Test
    @DisplayName("[AC-13] protocol refines enforces trace inclusion (NGJ-PROTO-003); inspect --protocol --format=mermaid outputs deterministic stateDiagram-v2 and rejects stale --revision")
    void ac13_protocolRefinementAndMermaidInspect() throws Exception {
        String validRefine = """
            package oracle.ac13.pos;

            public protocol BaseFlow {
                initial Init;
                states Approved;
                final Committed, Aborted;

                transition approve(Init) -> Approved effects();
                transition commit(Approved) -> Committed effects();
                transition abort(Init) -> Aborted effects();
            }

            public protocol AuditedFlow refines BaseFlow {
                initial Init;
                states Logged, Approved;
                final Committed, Aborted;

                transition logStart(Init) -> Logged effects();
                transition approve(Logged) -> Approved effects();
                transition commit(Approved) -> Committed effects();
                transition abort(Init) -> Aborted effects();
            }
            """;
        Path posFile = writeFile("ac13_pos.ngj", validRefine);
        CliRun posRes = runCli("check", "--format=json", posFile.toString());
        assertEquals(ExitCode.SUCCESS, posRes.exitCode, "Valid protocol refinement must pass: " + posRes.stdout);

        String rev = fetchRevision(posFile);

        // Inspect --protocol=... --format=mermaid determinism & content
        CliRun m1 = runCli("inspect", "--protocol=AuditedFlow", "--format=mermaid", "--revision=" + rev, posFile.toString());
        CliRun m2 = runCli("inspect", "--protocol=AuditedFlow", "--format=mermaid", "--revision=" + rev, posFile.toString());
        assertEquals(ExitCode.SUCCESS, m1.exitCode, "Mermaid inspect must succeed: " + m1.stderr);
        assertEquals(m1.stdout, m2.stdout, "Mermaid output must be deterministic");
        assertTrue(m1.stdout.contains("stateDiagram-v2"), "Must emit stateDiagram-v2: " + m1.stdout);
        assertTrue(m1.stdout.contains("Init") && m1.stdout.contains("Logged")
            && m1.stdout.contains("Approved") && m1.stdout.contains("Committed"),
            "Mermaid diagram must include protocol states: " + m1.stdout);

        // Stale --revision must exit 3
        CliRun staleInspect = runCli("inspect", "--protocol=AuditedFlow", "--format=mermaid",
            "--revision=sha256:stale", posFile.toString());
        assertEquals(ExitCode.BAD_INPUT, staleInspect.exitCode, "Stale --revision on inspect must return exit 3");

        // Bypass refinement rejected with NGJ-PROTO-003
        String bypassRefine = """
            package oracle.ac13.neg;

            public protocol BaseGate {
                initial Start;
                states Checked;
                final Done;

                transition verify(Start) -> Checked effects();
                transition finish(Checked) -> Done effects();
            }

            public protocol BypassGate refines BaseGate {
                initial Start;
                states Checked;
                final Done;

                transition verify(Start) -> Checked effects();
                transition finish(Checked) -> Done effects();
                transition shortcut(Start) -> Done effects();
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac13_neg.ngj", bypassRefine), "NGJ-PROTO-003");
    }

    // =========================================================================
    // [AC-14] BUDGET-01: Linear Budget<Kind> split conservation & error IDs
    // =========================================================================

    @Test
    @DisplayName("[AC-14] Budget<Kind> enforces conservation, rejects negative/over-limit splits (NGJ-BUDGET-001), double-consume (NGJ-CAP-002), unsplit fork reuse (NGJ-SEP-001), and unauthorized minting (NGJ-BUDGET-002)")
    void ac14_linearBudgetConservationAndViolations() throws Exception {
        String validSplit = """
            package oracle.ac14.pos;

            public fn allocateShare(consume b: Budget<ApiQuota>, share: Long[0L..b.remaining()]) -> Long effects() {
                val splitPair = Budget.split(consume b, share);
                return share;
            }
            """;
        CliRun posRes = runCli("check", "--format=json", writeFile("ac14_pos.ngj", validSplit).toString());
        assertEquals(ExitCode.SUCCESS, posRes.exitCode, "Valid Budget.split conservation must pass: " + posRes.stdout);

        String negativeSplit = """
            package oracle.ac14.neg;

            public fn badNegSplit(consume b: Budget<ApiQuota>) -> Unit effects() {
                val p = Budget.split(consume b, -1L);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac14_neg.ngj", negativeSplit), "NGJ-BUDGET-001");

        String overLimitSplit = """
            package oracle.ac14.over;

            public fn badOverSplit(consume b: Budget<ApiQuota>) -> Unit effects() {
                val p = Budget.split(consume b, b.remaining() + 1L);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac14_over.ngj", overLimitSplit), "NGJ-BUDGET-001");

        String doubleConsume = """
            package oracle.ac14.doublec;

            public fn badDoubleConsume(consume b: Budget<ApiQuota>) -> Unit effects() {
                val p1 = Budget.split(consume b, 0L);
                val p2 = Budget.split(consume b, 0L);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac14_doublec.ngj", doubleConsume), "NGJ-CAP-002");

        String unsplitForkReuse = """
            package oracle.ac14.fork;

            public fn useBudget(consume b: Budget<ApiQuota>) -> Int effects() {
                val p = Budget.split(consume b, 0L);
                return 1;
            }

            public fn badParallelReuse(consume b: Budget<ApiQuota>) -> Result<Int, ScopeError> effects() {
                return scope {
                    val t1 = fork { useBudget(consume b) };
                    val t2 = fork { useBudget(consume b) };
                    val r1 = t1.join();
                    val r2 = t2.join();
                    1
                };
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac14_fork.ngj", unsplitForkReuse), "NGJ-SEP-001");

        String unauthorizedMint = """
            package oracle.ac14.mint;

            public fn forgedBudget() -> Budget<ApiQuota> effects() {
                return Budget.mint::<ApiQuota>(1000L);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac14_mint.ngj", unauthorizedMint), "NGJ-BUDGET-002");
    }

    // =========================================================================
    // [AC-15] IFC-03: Selective PC-Guard (NGJ-IFC-PC-001) &
    //         @boundary_schema (decode + refinement + Secret(****) + NGJ-SCHEMA-001)
    // =========================================================================

    @Test
    @DisplayName("[AC-15] Selective PC-Guard rejects privileged_effects under Untrusted<T> branches (NGJ-IFC-PC-001) and allows after Validated<T>; @boundary_schema compiles JSON/binary decode + refinements + Secret(****) and rejects capability fields (NGJ-SCHEMA-001)")
    void ac15_selectivePcGuardAndBoundarySchema() throws Exception {
        // Negative PC-Guard: if / match / while conditioned on Untrusted<T>
        String pcGuardIf = """
            package oracle.ac15.pcif;

            public fn executePayment() -> Int effects(PaymentWrite) {
                return 1;
            }

            public fn taintedIf(flag: Untrusted<Bool>) -> Int effects(PaymentWrite) {
                if (flag) {
                    return executePayment();
                } else {
                    return 0;
                }
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac15_pcif.ngj", pcGuardIf), "NGJ-IFC-PC-001");

        String pcGuardMatch = """
            package oracle.ac15.pcmatch;

            public fn persistRow() -> Int effects(DbWrite) {
                return 1;
            }

            public fn taintedMatch(opt: Untrusted<Option<Int>>) -> Int effects(DbWrite) {
                return match (opt) {
                    case Some(_) => { persistRow() },
                    case None() => { 0 }
                };
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac15_pcmatch.ngj", pcGuardMatch), "NGJ-IFC-PC-001");

        String pcGuardWhile = """
            package oracle.ac15.pcwhile;

            public fn emitNet() -> Unit effects(Net) {
                return ();
            }

            public fn taintedWhile(cond: Untrusted<Bool>) -> Unit effects(Net) {
                while (cond) {
                    emitNet();
                };
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac15_pcwhile.ngj", pcGuardWhile), "NGJ-IFC-PC-001");

        // Positive PC-Guard after Result<Validated<T>, ValidationError>
        String pcGuardValidated = """
            package oracle.ac15.pcok;

            public enum ValidationError {
                InvalidInput()
            }

            public fn chargeNow() -> Int effects(PaymentWrite) {
                return 1;
            }

            public fn validateFlag(raw: Untrusted<Bool>) -> Result<Validated<Bool>, ValidationError> effects() {
                return Ok(Validated(true));
            }

            public fn safeDispatch(raw: Untrusted<Bool>) -> Result<Int, ValidationError> effects(PaymentWrite) {
                val verified: Validated<Bool> = validateFlag(raw)?;
                if (verified) {
                    return Ok(chargeNow());
                } else {
                    return Ok(0);
                }
            }
            """;
        CliRun pcOk = runCli("check", "--format=json", writeFile("ac15_pcok.ngj", pcGuardValidated).toString());
        assertEquals(ExitCode.SUCCESS, pcOk.exitCode, "Validated<T> branch must allow privileged_effects: " + pcOk.stdout);

        // Negative @boundary_schema with capability field -> NGJ-SCHEMA-001
        String badSchemaCap = """
            package oracle.ac15.badschema;

            @boundary_schema
            public record BadBoundary(db: read DbRead, name: String);
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac15_badschema.ngj", badSchemaCap), "NGJ-SCHEMA-001");

        // Positive @boundary_schema compile + runtime JSON/binary decode + refinement + Secret(****)
        String goodSchema = """
            package oracle.ac15.goodschema;

            @boundary_schema
            public record ServicePayload(service: String, port: Int[1..65535], apiToken: Secret<String>);
            """;
        Path schemaFile = writeFile("ac15_goodschema.ngj", goodSchema);
        CliRun schemaBuild = runCli("build", "--format=json", "--target=valhalla", schemaFile.toString());
        assertEquals(ExitCode.SUCCESS, schemaBuild.exitCode,
            "@boundary_schema build must succeed: " + schemaBuild.stdout + "\n" + schemaBuild.stderr);

        Map<String, Object> buildJson = parseJsonObject(schemaBuild.stdout);
        List<Path> classPaths = extractOutputPaths(buildJson);
        GeneratedClassLoader loader = loadGeneratedClasses(classPaths);

        Class<?> payloadClass = loader.loadClass("oracle.ac15.goodschema.ServicePayload");
        List<Class<?>> schemaClasses = new ArrayList<>();
        for (Path p : classPaths) {
            ClassModel cm = ClassFile.of().parse(Files.readAllBytes(p));
            String cn = cm.thisClass().asInternalName().replace('/', '.');
            if (cn.startsWith("oracle.ac15.goodschema.")) {
                schemaClasses.add(loader.loadClass(cn));
            }
        }

        Method jsonDecode = findStaticMethod(schemaClasses, String.class, "json", "decode", "fromjson");
        Method jsonEncode = findSerializeMethod(schemaClasses, payloadClass, String.class, "json", "encode", "tojson");
        Method binDecode = findStaticMethod(schemaClasses, byte[].class, "binary", "bytes", "decode");
        Method binEncode = findSerializeMethod(schemaClasses, payloadClass, byte[].class, "binary", "bytes", "encode");

        assertNotNull(jsonDecode, "@boundary_schema must emit JSON decode method");
        assertNotNull(jsonEncode, "@boundary_schema must emit JSON serialize method");
        assertNotNull(binDecode, "@boundary_schema must emit binary decode method");
        assertNotNull(binEncode, "@boundary_schema must emit binary serialize method");

        String secretLiteral = "confidential-boundary-token-98765";
        String validJsonInput = String.format(
            "{\"service\":\"billing\",\"port\":8443,\"apiToken\":\"%s\"}",
            secretLiteral
        );
        Object validDecodedRes = jsonDecode.invoke(null, validJsonInput);
        Object payloadObj = unwrapOkOrThrow(validDecodedRes);
        assertNotNull(payloadObj);

        String serializedJson = (String) (jsonEncode.getParameterCount() == 0
            ? jsonEncode.invoke(payloadObj)
            : jsonEncode.invoke(null, payloadObj));
        assertTrue(serializedJson.contains("Secret(****)"),
            "@boundary_schema JSON serialization must mask Secret<T> as Secret(****): " + serializedJson);
        assertFalse(serializedJson.contains(secretLiteral),
            "@boundary_schema JSON serialization must never leak raw secret: " + serializedJson);
        assertTrue(serializedJson.contains("billing") && serializedJson.contains("8443"),
            "Serialized JSON must preserve public fields: " + serializedJson);

        // Binary round-trip
        byte[] binBytes = (byte[]) (binEncode.getParameterCount() == 0
            ? binEncode.invoke(payloadObj)
            : binEncode.invoke(null, payloadObj));
        assertTrue(binBytes.length > 0);
        Object binDecodedRes = binDecode.invoke(null, (Object) binBytes);
        Object binPayloadObj = unwrapOkOrThrow(binDecodedRes);
        assertEquals(payloadObj, binPayloadObj, "Binary round-trip must preserve value equality");

        // Runtime refinement rejection on invalid port = 0
        String badJsonInput = String.format(
            "{\"service\":\"billing\",\"port\":0,\"apiToken\":\"%s\"}",
            secretLiteral
        );
        try {
            Object badDecodedRes = jsonDecode.invoke(null, badJsonInput);
            assertTrue(isResultErr(badDecodedRes),
                "Invalid refinement (port=0) must return Err from @boundary_schema decode");
            assertFalse(String.valueOf(badDecodedRes).contains(secretLiteral),
                "Refinement error must not leak Secret value");
        } catch (InvocationTargetException ex) {
            assertFalse(String.valueOf(ex.getCause()).contains(secretLiteral),
                "Decode exception must not leak Secret value");
        }
    }

    // =========================================================================
    // [AC-16] PROOF-01: spec/proof/lemma/invariant, NGJ-PROOF-002, ?proof_hole
    //         INCOMPLETE_PROOF, proof-required exit 2, context --proof-hole,
    //         complete JVM .class erasure
    // =========================================================================

    @Test
    @DisplayName("[AC-16] spec/proof/lemma/invariant typecheck and erase completely from .class; MathInt/spec isolation (NGJ-PROOF-002); ?proof_hole INCOMPLETE_PROOF & context --proof-hole")
    void ac16_formalProofsErasureIsolationAndProofHoles() throws Exception {
        String validProofSource = """
            package oracle.ac16.valid;

            public spec fn absSpec(x: Int) -> Int {
                if (x < 0) { -x } else { x }
            }

            public spec predicate IsNonNeg(x: Int) {
                x >= 0
            }

            public lemma nonNegPreserve(n: Int)
                requires (n >= 0)
                ensures (absSpec(n) == n)
            {
                proof {
                    assert(absSpec(n) == n);
                }
            }

            public fn countUp(n: Int) -> Int effects()
                requires (n >= 0)
                ensures (IsNonNeg(result) && result == n)
            {
                var i: Int = 0;
                while (i < n)
                    invariant (0 <= i && i <= n)
                {
                    i = i + 1;
                };
                proof {
                    nonNegPreserve(i);
                }
                return i;
            }
            """;

        Path validFile = writeFile("ac16_valid.ngj", validProofSource);
        CliRun checkOk = runCli("check", "--format=json", "--profile=proof-required", validFile.toString());
        assertEquals(ExitCode.SUCCESS, checkOk.exitCode, "Complete proof must pass proof-required check: " + checkOk.stdout);

        CliRun buildOk = runCli("build", "--format=json", "--target=valhalla", "--profile=proof-required", validFile.toString());
        assertEquals(ExitCode.SUCCESS, buildOk.exitCode, "Complete proof must build cleanly: " + buildOk.stdout);

        // Verify complete JVM .class erasure of spec/proof/lemma/invariant artifacts
        List<Path> classPaths = extractOutputPaths(parseJsonObject(buildOk.stdout));
        assertFalse(classPaths.isEmpty());
        for (Path classPath : classPaths) {
            String fileName = classPath.getFileName().toString();
            assertFalse(fileName.contains("absSpec") || fileName.contains("IsNonNeg") || fileName.contains("nonNegPreserve"),
                "Ghost/spec/lemma declarations must not emit .class files: " + fileName);

            ClassModel cm = ClassFile.of().parse(Files.readAllBytes(classPath));
            for (MethodModel mm : cm.methods()) {
                String mn = mm.methodName().stringValue();
                assertFalse(mn.contains("absSpec") || mn.contains("IsNonNeg") || mn.contains("nonNegPreserve")
                    || mn.contains("invariant") || mn.contains("proof"),
                    "Generated .class must not contain spec/proof/lemma/invariant methods: " + mn);
            }
            for (FieldModel fm : cm.fields()) {
                String fn = fm.fieldName().stringValue();
                assertFalse(fn.contains("absSpec") || fn.contains("IsNonNeg") || fn.contains("nonNegPreserve")
                    || fn.contains("invariant") || fn.contains("proof"),
                    "Generated .class must not contain spec/proof/lemma/invariant fields: " + fn);
            }
            for (Attribute<?> attr : cm.attributes()) {
                String an = attr.attributeName().stringValue();
                assertFalse(an.toLowerCase().contains("spec") || an.toLowerCase().contains("proof")
                    || an.toLowerCase().contains("lemma") || an.toLowerCase().contains("invariant"),
                    "Generated .class must not contain spec/proof/lemma/invariant attributes: " + an);
            }
            for (PoolEntry pe : cm.constantPool()) {
                if (pe instanceof Utf8Entry utf8) {
                    String s = utf8.stringValue();
                    assertFalse(s.equals("absSpec") || s.equals("IsNonNeg") || s.equals("nonNegPreserve") || s.equals("MathInt"),
                        "Constant pool must not retain erased spec/lemma/MathInt symbols: " + s);
                }
            }
        }

        // Verify runtime behavior of countUp$Fn still executes normally
        GeneratedClassLoader loader = loadGeneratedClasses(classPaths);
        Class<?> countUpFn = loader.loadClass("oracle.ac16.valid.countUp$Fn");
        assertEquals(7, countUpFn.getMethod("invoke", int.class).invoke(null, 7));

        // Isolation violations -> NGJ-PROOF-002
        String mathIntInExec = """
            package oracle.ac16.mathint;

            public fn badExec(x: MathInt) -> Int effects() {
                return 0;
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac16_mathint.ngj", mathIntInExec), "NGJ-PROOF-002");

        String specCallInExec = """
            package oracle.ac16.speccall;

            public spec fn pureGhost(x: Int) -> Int {
                x + 1
            }

            public fn badExec(x: Int) -> Int effects() {
                return pureGhost(x);
            }
            """;
        assertCheckInvalidWithDiagnostic(writeFile("ac16_speccall.ngj", specCallInExec), "NGJ-PROOF-002");

        // ?proof_hole -> CheckStatus.INCOMPLETE_PROOF, NGJ-PROOF-001, exit 2, context --proof-hole
        String proofHoleSource = """
            package oracle.ac16.hole;

            public spec fn doubleVal(x: Int) -> Int {
                x * 2
            }

            public lemma doubleEven(x: Int)
                requires (x >= 0)
                ensures (doubleVal(x) >= 0)
            {
                proof {
                    ?double_nonneg_gap
                }
            }

            public fn computeDouble(x: Int) -> Int effects()
                requires (x >= 0)
                ensures (result == doubleVal(x))
            {
                proof {
                    ?compute_proof_gap
                }
                return x * 2;
            }
            """;
        Path holeFile = writeFile("ac16_hole.ngj", proofHoleSource);

        CliRun checkHole = runCli("check", "--format=json", holeFile.toString());
        assertEquals(ExitCode.INCOMPLETE, checkHole.exitCode, "?proof_hole check must return exit 2: " + checkHole.stdout);
        Map<String, Object> holeJson = parseJsonObject(checkHole.stdout);
        assertEquals("incomplete_proof", holeJson.get("status"));
        assertTrue(hasDiagnostic(holeJson, "NGJ-PROOF-001"),
            "?proof_hole must emit NGJ-PROOF-001: " + checkHole.stdout);

        CliRun checkProofReq = runCli("check", "--format=json", "--profile=proof-required", holeFile.toString());
        assertEquals(ExitCode.INCOMPLETE, checkProofReq.exitCode, "--profile=proof-required must reject ?proof_hole with exit 2");

        CliRun buildHole = runCli("build", "--format=json", holeFile.toString());
        assertEquals(ExitCode.INCOMPLETE, buildHole.exitCode, "build must reject ?proof_hole with exit 2");

        // context --proof-hole=<id>
        CliRun ctxHole = runCli("context", "--format=json", "--proof-hole=compute_proof_gap", holeFile.toString());
        assertEquals(ExitCode.SUCCESS, ctxHole.exitCode, "context --proof-hole must succeed: " + ctxHole.stderr);
        Map<String, Object> ctxJson = parseJsonObject(ctxHole.stdout);
        assertEquals("ngj.context/2", ctxJson.get("schema_version"));
        assertTrue(ctxHole.stdout.contains("compute_proof_gap"), "Must return target proof hole ID");
        assertTrue(ctxHole.stdout.contains("doubleVal") || ctxHole.stdout.contains("doubleEven"),
            "Proof hole context must retrieve available spec/lemma contracts: " + ctxHole.stdout);
    }

    // =========================================================================
    // [AC-17] CERT-01: --verify=smt pure-JVM DPLL(T)/QF-LIA outcomes &
    //         ngjc certify-spec (ngj.certify/2 Surface A/B/C)
    // =========================================================================

    @Test
    @DisplayName("[AC-17] --verify=smt distinguishes proved, counterexample (Secret-masked), unproven, timeout, and unsupported")
    void ac17_smtVerificationOutcomes() throws Exception {
        // 1. proved
        String provedSrc = """
            package oracle.ac17.proved;

            public fn addTen(x: Int) -> Int effects()
                requires (x >= 10 && x <= 1000)
                ensures (result >= 20 && result == x + 10)
            {
                return x + 10;
            }
            """;
        CliRun provedRun = runCli("check", "--format=json", "--diagnostic-detail=agent",
            "--verify=smt", "--smt-backend=jvm", writeFile("ac17_proved.ngj", provedSrc).toString());
        assertEquals(ExitCode.SUCCESS, provedRun.exitCode, "Proved QF-LIA contract must pass: " + provedRun.stdout);
        Map<String, Object> provedJson = parseJsonObject(provedRun.stdout);
        assertEquals("valid", provedJson.get("status"));

        // Determinism check for pure-JVM SMT solver
        CliRun provedRun2 = runCli("check", "--format=json", "--diagnostic-detail=agent",
            "--verify=smt", "--smt-backend=jvm", tempDir.resolve("ac17_proved.ngj").toString());
        assertEquals(provedRun.stdout, provedRun2.stdout, "Pure-JVM SMT verification must be deterministic");

        // 2. counterexample (Secret-masked)
        String cexSrc = """
            package oracle.ac17.cex;

            public fn badPost(x: Int, token: Secret<Int>) -> Int effects()
                requires (x >= 0)
                ensures (result > 50)
            {
                return x + 1;
            }
            """;
        CliRun cexRun = runCli("check", "--format=json", "--diagnostic-detail=agent",
            "--verify=smt", "--smt-backend=jvm", writeFile("ac17_cex.ngj", cexSrc).toString());
        assertEquals(ExitCode.INVALID, cexRun.exitCode);
        Map<String, Object> cexJson = parseJsonObject(cexRun.stdout);
        assertTrue(hasDiagnostic(cexJson, "NGJ-VERIFY-001") || hasDiagnostic(cexJson, "NGJ-REF-001"),
            "Counterexample must emit verification error: " + cexRun.stdout);
        assertTrue(cexRun.stdout.contains("counterexample"), "Must report counterexample outcome: " + cexRun.stdout);
        assertTrue(cexRun.stdout.contains("Secret(****)"), "Counterexample state must mask Secret<T>: " + cexRun.stdout);

        // 3. unsupported (non-linear multiplication outside QF-LIA)
        String unsupportedSrc = """
            package oracle.ac17.unsup;

            public fn nonLinear(a: Int, b: Int) -> Int effects()
                requires (a > 1 && b > 1)
                ensures (result == a * b && result > a + b - 1)
            {
                return a * b;
            }
            """;
        CliRun unsupRun = runCli("check", "--format=json", "--diagnostic-detail=agent",
            "--verify=smt", "--smt-backend=jvm", writeFile("ac17_unsup.ngj", unsupportedSrc).toString());
        assertNotEquals(ExitCode.SUCCESS, unsupRun.exitCode, "Unsupported nonlinear formula must NOT pass as proved");
        assertTrue(unsupRun.stdout.contains("unsupported") || unsupRun.stdout.contains("NGJ-VERIFY-001"),
            "Must explicitly report unsupported verification outcome: " + unsupRun.stdout);

        // 4. unproven (recursive / opaque spec without induction lemma)
        String unprovenSrc = """
            package oracle.ac17.unproven;

            public spec fn recFn(n: Int) -> Int {
                if (n <= 0) { 0 } else { n + recFn(n - 1) }
            }

            public fn sumUp(n: Int) -> Int effects()
                requires (n >= 0 && n <= 10)
                ensures (result >= 0 && recFn(n) >= n)
            {
                var acc = 0;
                var i = 0;
                while (i <= n) {
                    acc = acc + i;
                    i = i + 1;
                };
                return acc;
            }
            """;
        CliRun unprovenRun = runCli("check", "--format=json", "--diagnostic-detail=agent",
            "--verify=smt", "--smt-backend=jvm", writeFile("ac17_unproven.ngj", unprovenSrc).toString());
        assertNotEquals(ExitCode.SUCCESS, unprovenRun.exitCode, "Unproven contract must NOT be reported as proved");
        assertTrue(unprovenRun.stdout.contains("unproven") || unprovenRun.stdout.contains("NGJ-VERIFY-001"),
            "Must explicitly classify unproven outcome: " + unprovenRun.stdout);

        // 5. timeout (exponentially large 0/1 pigeonhole QF-LIA branch-and-bound: 10 pigeons in 9 holes)
        String timeoutSrc = buildPigeonholeContractSource(10, 9);
        CliRun timeoutRun = runCli("check", "--format=json", "--diagnostic-detail=agent",
            "--verify=smt", "--smt-backend=jvm", writeFile("ac17_timeout.ngj", timeoutSrc).toString());
        assertNotEquals(ExitCode.SUCCESS, timeoutRun.exitCode, "Timed-out SMT query must NOT pass as proved");
        assertTrue(timeoutRun.stdout.contains("timeout") || timeoutRun.stdout.contains("NGJ-VERIFY-001"),
            "Must explicitly distinguish timeout outcome: " + timeoutRun.stdout);
    }

    @Test
    @DisplayName("[AC-17] ngjc certify-spec emits ngj.certify/2 validating Surface A, Surface B (NGJ-CERT-VACUOUS-PRE, NGJ-CERT-WEAK-ENSURES, NGJ-CERT-ASSUME-PRESENT), and Surface C (Post2Exe + uniqueness/mutation witness)")
    void ac17_certifySpecSurfacesABC() throws Exception {
        // 1. Valid strong specification -> exit 0, ngj.certify/2, Surface A/B/C pass
        String strongSpecSrc = """
            package oracle.ac17.cert.strong;

            public spec fn absExpected(x: Int) -> Int {
                if (x < 0) { -x } else { x }
            }

            public fn absVal(x: Int) -> Int effects()
                requires (x >= -1000 && x <= 1000)
                ensures (result == absExpected(x))
            {
                if (x < 0) {
                    return -x;
                } else {
                    return x;
                }
            }
            """;
        Path strongFile = writeFile("ac17_cert_strong.ngj", strongSpecSrc);
        String strongRev = fetchRevision(strongFile);
        CliRun certOk = runCli("certify-spec", "--format=json", "--symbol=absVal", "--revision=" + strongRev, strongFile.toString());
        assertEquals(ExitCode.SUCCESS, certOk.exitCode, "Strong spec certification must exit 0: " + certOk.stdout + "\n" + certOk.stderr);

        Map<String, Object> certJson = parseJsonObject(certOk.stdout);
        assertEquals("ngj.certify/2", certJson.get("schema_version"));
        assertEquals("0.3.0-valhalla", certJson.get("compiler_version"));
        assertNotNull(certJson.get("surface_a"), "ngj.certify/2 must include surface_a");
        assertNotNull(certJson.get("surface_b"), "ngj.certify/2 must include surface_b");
        assertNotNull(certJson.get("surface_c"), "ngj.certify/2 must include surface_c");
        assertTrue(certOk.stdout.contains("proved"), "Surface A must report proved for strong QF-LIA spec");
        assertTrue(certOk.stdout.toLowerCase().contains("post2exe") || certOk.stdout.contains("negative"),
            "Surface C must include Post2Exe validation: " + certOk.stdout);
        assertTrue(certOk.stdout.toLowerCase().contains("unique") || certOk.stdout.toLowerCase().contains("mutation")
            || certOk.stdout.toLowerCase().contains("witness"),
            "Surface C must include 2-call uniqueness or mutation+PBT witness: " + certOk.stdout);

        // 2. Surface B: Vacuous precondition -> NGJ-CERT-VACUOUS-PRE, exit 1
        String vacuousPreSrc = """
            package oracle.ac17.cert.vacuous;

            public fn impossiblePre(x: Int) -> Int effects()
                requires (x > 10 && x < 5)
                ensures (result == x)
            {
                return x;
            }
            """;
        Path vacuousFile = writeFile("ac17_cert_vacuous.ngj", vacuousPreSrc);
        CliRun vacuousRun = runCli("certify-spec", "--format=json", "--symbol=impossiblePre",
            "--revision=" + fetchRevision(vacuousFile), vacuousFile.toString());
        assertEquals(ExitCode.INVALID, vacuousRun.exitCode);
        assertTrue(vacuousRun.stdout.contains("NGJ-CERT-VACUOUS-PRE"),
            "Vacuous precondition must emit NGJ-CERT-VACUOUS-PRE: " + vacuousRun.stdout);

        // 3. Surface B: Weak ensures -> NGJ-CERT-WEAK-ENSURES, exit 1
        String weakEnsuresSrc = """
            package oracle.ac17.cert.weak;

            public spec fn targetContract(x: Int) -> Int {
                x + 1
            }

            public fn weakPost(x: Int) -> Int effects()
                requires (x >= 0 && x <= 100)
                ensures (true)
            {
                return x + 1;
            }
            """;
        Path weakFile = writeFile("ac17_cert_weak.ngj", weakEnsuresSrc);
        CliRun weakRun = runCli("certify-spec", "--format=json", "--symbol=weakPost",
            "--revision=" + fetchRevision(weakFile), weakFile.toString());
        assertEquals(ExitCode.INVALID, weakRun.exitCode);
        assertTrue(weakRun.stdout.contains("NGJ-CERT-WEAK-ENSURES"),
            "Trivially weak ensures must emit NGJ-CERT-WEAK-ENSURES: " + weakRun.stdout);

        // 4. Surface B: assume present -> NGJ-CERT-ASSUME-PRESENT, exit 1
        String assumeSrc = """
            package oracle.ac17.cert.assume;

            public fn assumesTruth(x: Int) -> Int effects()
                requires (x >= 0 && x <= 100)
                ensures (result == x * 2)
            {
                proof {
                    assume(x * 2 >= 0);
                }
                return x * 2;
            }
            """;
        Path assumeFile = writeFile("ac17_cert_assume.ngj", assumeSrc);
        CliRun assumeRun = runCli("certify-spec", "--format=json", "--symbol=assumesTruth",
            "--revision=" + fetchRevision(assumeFile), assumeFile.toString());
        assertEquals(ExitCode.INVALID, assumeRun.exitCode);
        assertTrue(assumeRun.stdout.contains("NGJ-CERT-ASSUME-PRESENT"),
            "assume in certified symbol must emit NGJ-CERT-ASSUME-PRESENT: " + assumeRun.stdout);

        // 5. Surface C: Non-unique / Post2Exe-failing under-specified ensures -> exit 1
        String underSpecifiedSrc = """
            package oracle.ac17.cert.surfacec;

            public spec fn exactDouble(x: Int) -> Int {
                x * 2
            }

            public fn underSpec(x: Int) -> Int effects()
                requires (x >= 1 && x <= 100)
                ensures (result >= 2)
            {
                return x * 2;
            }
            """;
        Path underSpecFile = writeFile("ac17_cert_surfacec.ngj", underSpecifiedSrc);
        CliRun surfaceCFail = runCli("certify-spec", "--format=json", "--symbol=underSpec",
            "--revision=" + fetchRevision(underSpecFile), underSpecFile.toString());
        assertEquals(ExitCode.INVALID, surfaceCFail.exitCode,
            "Under-specified ensures failing Surface C mutation/Post2Exe/uniqueness check must exit 1: " + surfaceCFail.stdout);
    }

    // =========================================================================
    // Helper utilities (black-box CLI, unified diff, ClassLoader, JSON parser)
    // =========================================================================

    private Path writeFile(String name, String content) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private CliRun runCli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        return new CliRun(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private void assertCheckInvalidWithDiagnostic(Path file, String expectedDiagId) {
        CliRun run = runCli("check", "--format=json", "--diagnostic-detail=agent", file.toString());
        assertEquals(ExitCode.INVALID, run.exitCode,
            "Expected exit code 1 (INVALID) with " + expectedDiagId + " for " + file.getFileName()
                + ", got " + run.exitCode + "\nstdout:\n" + run.stdout + "\nstderr:\n" + run.stderr);
        Map<String, Object> root = parseJsonObject(run.stdout);
        assertTrue(hasDiagnostic(root, expectedDiagId),
            "Expected diagnostic " + expectedDiagId + " in output:\n" + run.stdout);
    }

    private void assertGateRejects(String baseRevision, Path patchFile, String expectedGateDiag, Path... files) {
        List<String> args = new ArrayList<>();
        args.add("check-patch");
        args.add("--format=json");
        args.add("--policy=strict-gates");
        args.add("--base-revision=" + baseRevision);
        args.add("--patch=" + patchFile);
        for (Path f : files) {
            args.add(f.toString());
        }
        CliRun run = runCli(args.toArray(String[]::new));
        assertEquals(ExitCode.INVALID, run.exitCode,
            "Expected strict-gates exit 1 for " + expectedGateDiag + ", got " + run.exitCode
                + "\nstdout:\n" + run.stdout + "\nstderr:\n" + run.stderr);
        Map<String, Object> root = parseJsonObject(run.stdout);
        assertTrue(hasDiagnostic(root, expectedGateDiag),
            "Expected gate diagnostic " + expectedGateDiag + " in:\n" + run.stdout);
    }

    private String fetchRevision(Path... files) {
        List<String> args = new ArrayList<>();
        args.add("check");
        args.add("--format=json");
        for (Path f : files) {
            args.add(f.toString());
        }
        CliRun res = runCli(args.toArray(String[]::new));
        Map<String, Object> json = parseJsonObject(res.stdout);
        Object rev = json.get("source_revision");
        assertNotNull(rev, "check --format=json must return source_revision: " + res.stdout);
        return rev.toString();
    }

    private static boolean hasDiagnostic(Map<String, Object> root, String diagId) {
        Object raw = root.get("diagnostics");
        if (!(raw instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && diagId.equals(map.get("id"))) {
                return true;
            }
        }
        return false;
    }

    private List<Path> extractOutputPaths(Map<String, Object> buildJson) {
        Object raw = buildJson.get("output_files");
        assertInstanceOf(List.class, raw, "build JSON must include output_files list");
        List<Path> paths = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            Path p = Path.of(String.valueOf(item));
            assertTrue(Files.exists(p), "Reported build output file does not exist: " + p);
            paths.add(p);
            filesToCleanup.add(p);
        }
        return paths;
    }

    private GeneratedClassLoader loadGeneratedClasses(List<Path> classFiles) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        for (Path p : classFiles) {
            byte[] bytes = Files.readAllBytes(p);
            ClassModel cm = ClassFile.of().parse(bytes);
            String binaryName = cm.thisClass().asInternalName().replace('/', '.');
            classes.put(binaryName, bytes);
        }
        return new GeneratedClassLoader(classes, getClass().getClassLoader());
    }

    private static Method findStaticMethod(List<Class<?>> classes, Class<?> paramType, String... nameHints) {
        for (Class<?> c : classes) {
            for (Method m : c.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == paramType) {
                    String lower = m.getName().toLowerCase();
                    for (String hint : nameHints) {
                        if (lower.contains(hint)) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Method findSerializeMethod(
        List<Class<?>> classes,
        Class<?> payloadClass,
        Class<?> returnType,
        String... nameHints
    ) {
        for (Class<?> c : classes) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != returnType) {
                    continue;
                }
                boolean instanceNoArg = !java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && c == payloadClass && m.getParameterCount() == 0;
                boolean staticOneArg = java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(payloadClass);
                if (!instanceNoArg && !staticOneArg) {
                    continue;
                }
                String lower = m.getName().toLowerCase();
                for (String hint : nameHints) {
                    if (lower.contains(hint)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
        }
        return null;
    }

    private static Object unwrapOkOrThrow(Object resultObj) throws Exception {
        assertNotNull(resultObj, "Decode result must not be null");
        String simpleName = resultObj.getClass().getSimpleName();
        if (simpleName.equals("Ok") || simpleName.endsWith("$Ok")) {
            for (var f : resultObj.getClass().getFields()) {
                return f.get(resultObj);
            }
            for (var m : resultObj.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && (m.getName().equals("value") || m.getName().equals("getValue"))) {
                    return m.invoke(resultObj);
                }
            }
        }
        if (simpleName.equals("Err") || simpleName.endsWith("$Err")) {
            fail("Expected Ok(...) result, got Err: " + resultObj);
        }
        return resultObj;
    }

    private static boolean isResultErr(Object resultObj) {
        if (resultObj == null) {
            return false;
        }
        String simpleName = resultObj.getClass().getSimpleName();
        if (simpleName.equals("Err") || simpleName.endsWith("$Err")) {
            return true;
        }
        try {
            Method isErr = resultObj.getClass().getMethod("isErr");
            return Boolean.TRUE.equals(isErr.invoke(resultObj));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String unifiedDiff(Path filePath, String before, String after) {
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(filePath).append("\n");
        sb.append("+++ ").append(filePath).append("\n");
        sb.append("@@ -1,").append(oldLines.length).append(" +1,").append(newLines.length).append(" @@\n");
        for (String line : oldLines) {
            sb.append("-").append(line).append("\n");
        }
        for (String line : newLines) {
            sb.append("+").append(line).append("\n");
        }
        return sb.toString();
    }

    private static String buildPigeonholeContractSource(int pigeons, int holes) {
        StringBuilder sb = new StringBuilder();
        sb.append("package oracle.ac17.timeout;\n\n");
        sb.append("public fn pigeonhole(\n");
        List<String> vars = new ArrayList<>();
        for (int p = 0; p < pigeons; p++) {
            for (int h = 0; h < holes; h++) {
                String name = "x_" + p + "_" + h;
                vars.add(name);
            }
        }
        for (int i = 0; i < vars.size(); i++) {
            sb.append("    ").append(vars.get(i)).append(": Int");
            if (i + 1 < vars.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(") -> Int effects()\n");
        sb.append("    requires (\n");
        for (int i = 0; i < vars.size(); i++) {
            sb.append("        ").append(vars.get(i)).append(" >= 0 && ").append(vars.get(i)).append(" <= 1 && \n");
        }
        // Every pigeon sits in at least 1 hole
        for (int p = 0; p < pigeons; p++) {
            sb.append("        (");
            for (int h = 0; h < holes; h++) {
                if (h > 0) sb.append(" + ");
                sb.append("x_").append(p).append("_").append(h);
            }
            sb.append(" >= 1) && \n");
        }
        // Every hole holds at most 1 pigeon
        for (int h = 0; h < holes; h++) {
            sb.append("        (");
            for (int p = 0; p < pigeons; p++) {
                if (p > 0) sb.append(" + ");
                sb.append("x_").append(p).append("_").append(h);
            }
            sb.append(" <= 1)");
            if (h + 1 < holes) sb.append(" && ");
            sb.append("\n");
        }
        sb.append("    )\n");
        sb.append("    ensures (result == 0)\n");
        sb.append("{\n    return 1;\n}\n");
        return sb.toString();
    }

    private static int indexOfBytes(byte[] haystack, byte[] needle, int approxCharStart) {
        int startSearch = Math.max(0, approxCharStart);
        for (int i = startSearch; i <= haystack.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> parseJsonObject(String text) {
        Object parsed = new JsonParser(text).parseRoot();
        return asObject(parsed);
    }

    private static Map<String, Object> asObject(Object val) {
        assertInstanceOf(Map.class, val, "Expected JSON object, got: " + val);
        Map<?, ?> raw = (Map<?, ?>) val;
        Map<String, Object> result = new LinkedHashMap<>();
        for (var e : raw.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }

    private static List<Map<String, Object>> asObjectList(Object val) {
        assertInstanceOf(List.class, val, "Expected JSON array, got: " + val);
        List<?> raw = (List<?>) val;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            result.add(asObject(item));
        }
        return result;
    }

    private static int asInt(Object val) {
        assertInstanceOf(Number.class, val, "Expected JSON number, got: " + val);
        return ((Number) val).intValue();
    }

    private record CliRun(int exitCode, String stdout, String stderr) {}

    private static final class GeneratedClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        GeneratedClassLoader(Map<String, byte[]> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }

    /**
     * Minimal strict RFC 8259 JSON parser for black-box CLI stdout verification.
     */
    private static final class JsonParser {
        private final String input;
        private int pos;

        JsonParser(String input) {
            this.input = input == null ? "" : input;
        }

        Object parseRoot() {
            skipWhitespace();
            Object val = parseValue();
            skipWhitespace();
            if (pos != input.length()) {
                fail("Unexpected trailing content in JSON at pos " + pos + ": " + input.substring(pos));
            }
            return val;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) {
                fail("Unexpected end of JSON input:\n" + input);
            }
            char c = input.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    fail("Unexpected JSON token '" + c + "' at pos " + pos + " in:\n" + input);
                    yield null;
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return map;
                } else {
                    fail("Expected ',' or '}' in JSON object at pos " + pos);
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return list;
                } else {
                    fail("Expected ',' or ']' in JSON array at pos " + pos);
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= input.length()) fail("Unterminated escape");
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            String hex = input.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> fail("Invalid JSON escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            fail("Unterminated JSON string");
            return "";
        }

        private Number parseNumber() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            boolean isFloat = false;
            if (peek('.')) {
                isFloat = true;
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            if (peek('e') || peek('E')) {
                isFloat = true;
                pos++;
                if (peek('+') || peek('-')) pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            String num = input.substring(start, pos);
            return isFloat ? Double.parseDouble(num) : Long.parseLong(num);
        }

        private Object parseLiteral(String literal, Object val) {
            if (!input.startsWith(literal, pos)) {
                fail("Expected '" + literal + "' at pos " + pos);
            }
            pos += literal.length();
            return val;
        }

        private void expect(char expected) {
            if (pos >= input.length() || input.charAt(pos) != expected) {
                fail("Expected '" + expected + "' at pos " + pos);
            }
            pos++;
        }

        private boolean peek(char c) {
            return pos < input.length() && input.charAt(pos) == c;
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
