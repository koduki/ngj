package ngj.cli;

import ngj.gate.PatchGateEvaluator;
import ngj.typechecker.IfcPcChecker;
import ngj.typechecker.TypeChecker.CheckOptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * NGJ Compiler CLI entry point.
 */
public final class Main {

    public static final String DIAGNOSTICS_SCHEMA_VERSION = "ngj.diagnostics/2";
    public static final String CONTEXT_SCHEMA_VERSION = "ngj.context/2";
    public static final String INSPECT_SCHEMA_VERSION = "ngj.inspect/2";
    public static final String OBLIGATIONS_SCHEMA_VERSION = "ngj.obligations/2";
    public static final String PATCH_CHECK_SCHEMA_VERSION = "ngj.patch_check/2";
    public static final String CERTIFY_SCHEMA_VERSION = "ngj.certify/2";

    public static final String SCHEMA_VERSION = DIAGNOSTICS_SCHEMA_VERSION;
    public static final String COMPILER_VERSION = "0.3.0-valhalla";
    public static final String TARGET_PROFILE = "valhalla/standard";

    public static final int REQUIRED_JDK_VERSION = 28;
    public static final String REQUIRED_JDK_BUILD = "24";
    public static final String VALHALLA_JDK_URL = "https://jdk.java.net/28/";

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        System.exit(exitCode);
    }

    public static int run(String[] args, PrintStream stdout, PrintStream stderr) {
        try {
            // TARGET-02: Verify Valhalla-capable JDK before any operation
            var jdkCheck = checkValhallaJdk();
            if (!jdkCheck.isValid()) {
                stderr.println(jdkCheck.errorMessage());
                return ExitCode.BAD_INPUT;
            }

            if (args.length == 0) {
                printUsage(stderr);
                return ExitCode.BAD_INPUT;
            }

            String command = args[0];
            String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);

            return switch (command) {
                case "check" -> runCheck(remainingArgs, stdout, stderr);
                case "context" -> runContext(remainingArgs, stdout, stderr);
                case "inspect" -> runInspect(remainingArgs, stdout, stderr);
                case "obligations" -> runObligations(remainingArgs, stdout, stderr);
                case "check-patch" -> runCheckPatch(remainingArgs, stdout, stderr);
                case "certify-spec" -> runCertifySpec(remainingArgs, stdout, stderr);
                case "build" -> runBuild(remainingArgs, stdout, stderr);
                case "--version", "-v" -> {
                    stdout.println("ngjc " + COMPILER_VERSION);
                    yield ExitCode.SUCCESS;
                }
                case "--help", "-h" -> {
                    printUsage(stdout);
                    yield ExitCode.SUCCESS;
                }
                default -> {
                    stderr.println("Unknown command: " + command);
                    printUsage(stderr);
                    yield ExitCode.BAD_INPUT;
                }
            };
        } catch (Exception e) {
            stderr.println("Internal compiler error: " + e.getMessage());
            return ExitCode.INTERNAL_ERROR;
        }
    }

    public static String targetProfileFor(String profile) {
        if ("restricted".equals(profile)) {
            return "valhalla/restricted";
        }
        if ("proof-required".equals(profile)) {
            return "valhalla/proof-required";
        }
        return "valhalla/standard";
    }

    public static String computePolicyDigest(
        String profile,
        String policy,
        String verifyMode,
        String smtBackend,
        Collection<String> trustedAllowlist
    ) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String payload = String.join("|",
                profile == null ? "standard" : profile,
                policy == null ? "default" : policy,
                verifyMode == null ? "tier1" : verifyMode,
                smtBackend == null ? "jvm" : smtBackend,
                trustedAllowlist == null ? "" : String.join(",", trustedAllowlist.stream().sorted().toList())
            );
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "sha256:0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    private static int runCheck(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);
        var files = opts.files();

        if (files.isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }

        String actualRevision = computeRevision(files);
        String policyDigest = opts.policyDigest();
        String targetProfile = targetProfileFor(opts.profile());

        if (opts.revision() != null && !opts.revision().equals(actualRevision)) {
            if (opts.formatJson()) {
                stdout.println(formatRevisionMismatchJson(
                    DIAGNOSTICS_SCHEMA_VERSION, opts.revision(), actualRevision, policyDigest, targetProfile));
            } else {
                stderr.println("Revision mismatch: expected " + opts.revision() + ", got " + actualRevision);
            }
            return ExitCode.BAD_INPUT;
        }

        var compiler = new ngj.Compiler();
        var result = compiler.check(files, opts.toCheckOptions());

        if (opts.formatJson()) {
            stdout.println(result.toJson(actualRevision, policyDigest, targetProfile, opts.diagnosticDetail()));
        } else {
            result.printHuman(stdout);
        }

        return result.exitCode();
    }

    private static int runContext(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);

        boolean proofHoleMode = opts.proofHoleId() != null;
        String targetHole = proofHoleMode ? opts.proofHoleId() : opts.holeId();
        if (targetHole == null) {
            stderr.println("Missing required --hole=<id> or --proof-hole=<id> argument");
            return ExitCode.BAD_INPUT;
        }

        if (opts.files().isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }

        String actualRevision = computeRevision(opts.files());
        String policyDigest = opts.policyDigest();
        String targetProfile = targetProfileFor(opts.profile());

        if (opts.revision() != null && !opts.revision().equals(actualRevision)) {
            if (opts.formatJson()) {
                stdout.println(formatRevisionMismatchJson(
                    CONTEXT_SCHEMA_VERSION, opts.revision(), actualRevision, policyDigest, targetProfile));
            } else {
                stderr.println("Revision mismatch: expected " + opts.revision() + ", got " + actualRevision);
            }
            return ExitCode.BAD_INPUT;
        }

        var compiler = new ngj.Compiler();
        compiler.check(opts.files(), opts.toCheckOptions());

        var result = compiler.context(
            targetHole, proofHoleMode, opts.maxBytes(), actualRevision, policyDigest, targetProfile);

        if (opts.formatJson()) {
            stdout.println(result.toJson(actualRevision, policyDigest, targetProfile));
        } else {
            result.printHuman(stdout);
        }

        return result.exitCode();
    }

    private static int runInspect(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);

        if (opts.symbolId() == null && opts.protocolId() == null) {
            stderr.println("Missing required --symbol=<id> or --protocol=<id> argument");
            return ExitCode.BAD_INPUT;
        }

        if (opts.files().isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }

        String actualRevision = computeRevision(opts.files());
        String policyDigest = opts.policyDigest();
        String targetProfile = targetProfileFor(opts.profile());

        if (opts.revision() != null && !opts.revision().equals(actualRevision)) {
            if (opts.formatJson()) {
                stdout.println(formatRevisionMismatchJson(
                    INSPECT_SCHEMA_VERSION, opts.revision(), actualRevision, policyDigest, targetProfile));
            } else {
                stderr.println("Revision mismatch: expected " + opts.revision() + ", got " + actualRevision);
            }
            return ExitCode.BAD_INPUT;
        }

        var compiler = new ngj.Compiler();
        compiler.check(opts.files(), opts.toCheckOptions());

        var result = opts.protocolId() != null
            ? compiler.inspectProtocol(opts.protocolId())
            : compiler.inspect(opts.symbolId());

        if (opts.formatMermaid()) {
            if (result.error() != null) {
                stderr.println("Error: " + result.error());
            } else {
                stdout.print(result.toMermaid());
            }
        } else if (opts.formatJson()) {
            stdout.println(result.toJson(actualRevision, policyDigest, targetProfile));
        } else {
            result.printHuman(stdout);
        }

        return result.exitCode();
    }

    private static int runObligations(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);
        if (opts.files().isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }

        String actualRevision = computeRevision(opts.files());
        String policyDigest = opts.policyDigest();
        String targetProfile = targetProfileFor(opts.profile());

        if (opts.revision() != null && !opts.revision().equals(actualRevision)) {
            if (opts.formatJson()) {
                stdout.println(formatRevisionMismatchJson(
                    OBLIGATIONS_SCHEMA_VERSION, opts.revision(), actualRevision, policyDigest, targetProfile));
            } else {
                stderr.println("Revision mismatch: expected " + opts.revision() + ", got " + actualRevision);
            }
            return ExitCode.BAD_INPUT;
        }

        var compiler = new ngj.Compiler();
        compiler.check(opts.files(), opts.toCheckOptions());
        var result = compiler.obligations();

        if (opts.formatJson()) {
            stdout.println(result.toJson(actualRevision, policyDigest, targetProfile));
        } else {
            result.printHuman(stdout);
        }

        return result.exitCode();
    }

    private static int runCheckPatch(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);
        if (opts.files().isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }
        if (opts.patchPath() == null) {
            stderr.println("Missing required --patch=<file> argument");
            return ExitCode.BAD_INPUT;
        }

        String actualRevision = computeRevision(opts.files());
        String policyDigest = opts.policyDigest();
        String targetProfile = targetProfileFor(opts.profile());

        if (opts.baseRevision() == null || !opts.baseRevision().equals(actualRevision)) {
            if (opts.formatJson()) {
                stdout.println(formatRevisionMismatchJson(
                    PATCH_CHECK_SCHEMA_VERSION,
                    opts.baseRevision() != null ? opts.baseRevision() : "",
                    actualRevision,
                    policyDigest,
                    targetProfile
                ));
            } else {
                stderr.println("Base revision mismatch: expected " + opts.baseRevision() + ", got " + actualRevision);
            }
            return ExitCode.BAD_INPUT;
        }

        Map<Path, String> baseWorkspace = new LinkedHashMap<>();
        for (Path f : opts.files()) {
            try {
                baseWorkspace.put(f, Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException e) {
                stderr.println("Cannot read file: " + f + " (" + e.getMessage() + ")");
                return ExitCode.BAD_INPUT;
            }
        }

        String unifiedDiff;
        try {
            unifiedDiff = Files.readString(Path.of(opts.patchPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            stderr.println("Cannot read patch file: " + opts.patchPath());
            return ExitCode.BAD_INPUT;
        }

        var evaluator = new PatchGateEvaluator();
        var result = evaluator.evaluate(
            baseWorkspace,
            unifiedDiff,
            opts.policy(),
            opts.frozenSpecs(),
            opts.requireComplete(),
            opts.toCheckOptions()
        );

        if (opts.formatJson()) {
            stdout.println(result.toJson(actualRevision, policyDigest, targetProfile));
        } else {
            result.printHuman(stdout);
        }

        return result.exitCode();
    }

    private static int runCertifySpec(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);
        if (opts.symbolId() == null) {
            stderr.println("Missing required --symbol=<id> argument");
            return ExitCode.BAD_INPUT;
        }
        if (opts.files().isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }

        String actualRevision = computeRevision(opts.files());
        String policyDigest = opts.policyDigest();
        String targetProfile = targetProfileFor(opts.profile());

        if (opts.revision() != null && !opts.revision().equals(actualRevision)) {
            if (opts.formatJson()) {
                stdout.println(formatRevisionMismatchJson(
                    CERTIFY_SCHEMA_VERSION, opts.revision(), actualRevision, policyDigest, targetProfile));
            } else {
                stderr.println("Revision mismatch: expected " + opts.revision() + ", got " + actualRevision);
            }
            return ExitCode.BAD_INPUT;
        }

        var compiler = new ngj.Compiler();
        var checkResult = compiler.check(opts.files(), opts.toCheckOptions());
        var result = compiler.certifySpec(opts.symbolId(), opts.smtBackend(), checkResult);

        if (opts.formatJson()) {
            stdout.println(result.toJson(actualRevision, policyDigest, targetProfile));
        } else {
            result.printHuman(stdout);
        }

        return result.exitCode();
    }

    private static int runBuild(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);
        var files = opts.files();

        if (files.isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }

        String actualRevision = computeRevision(files);
        String policyDigest = opts.policyDigest();
        String targetProfile = targetProfileFor(opts.profile());

        var compiler = new ngj.Compiler();
        var result = compiler.build(files, opts.target(), opts.toCheckOptions());

        if (opts.formatJson()) {
            stdout.println(result.toJson(actualRevision, policyDigest, targetProfile));
        } else {
            result.printHuman(stdout, stderr);
        }

        return result.exitCode();
    }

    private static JdkCheckResult checkValhallaJdk() {
        if ("true".equals(System.getenv("NGJ_SKIP_JDK_CHECK"))) {
            return new JdkCheckResult(true, null);
        }

        int jdkVersion = Runtime.version().feature();

        if (jdkVersion < REQUIRED_JDK_VERSION) {
            return new JdkCheckResult(false, """
                NGJ-TARGET-002: Valhalla-capable JDK required

                NGJ requires JDK %d or later with JEP 401 Value Objects (Preview).
                Current JDK: %s

                Download JDK 28 EA from: %s

                Run with: java --enable-preview -jar ngjc.jar <command>
                """.formatted(REQUIRED_JDK_VERSION, Runtime.version(), VALHALLA_JDK_URL));
        }

        if (!isPreviewEnabled()) {
            return new JdkCheckResult(false, """
                NGJ-TARGET-002: Preview features required

                NGJ requires JDK %d with --enable-preview for JEP 401 Value Objects.
                Current JDK: %s (preview features not enabled)

                Run with: java --enable-preview -jar ngjc.jar <command>
                """.formatted(REQUIRED_JDK_VERSION, Runtime.version()));
        }

        return new JdkCheckResult(true, null);
    }

    private static boolean isPreviewEnabled() {
        try {
            java.util.Objects.class.getMethod("hasIdentity", Object.class);
            return true;
        } catch (NoSuchMethodException e) {
            return "true".equals(System.getenv("NGJ_SKIP_JDK_CHECK"));
        }
    }

    private record JdkCheckResult(boolean isValid, String errorMessage) {}

    private static void printUsage(PrintStream out) {
        out.println("""
            NGJ Compiler %s (Valhalla foundation: %s)

            Usage:
              ngjc check [--format=json] [--diagnostic-detail=human|agent] [--profile=standard|restricted|proof-required] [--verify=tier1|smt] [--smt-backend=jvm|z3|auto] [--revision=<hash>] <files...>
              ngjc context [--format=json] (--hole=<id> | --proof-hole=<id>) [--revision=<hash>] [--max-bytes=N] <files...>
              ngjc inspect [--format=json|mermaid] (--symbol=<id> | --protocol=<id>) [--revision=<hash>] <files...>
              ngjc obligations [--format=json] [--revision=<hash>] <files...>
              ngjc check-patch [--format=json] --policy=strict-gates --base-revision=<hash> --patch=<file> <files...>
              ngjc certify-spec [--format=json] --symbol=<id> [--revision=<hash>] <files...>
              ngjc build [--target=valhalla|valhalla-compat|java25] [--profile=standard|restricted|proof-required] <files...>

            Exit codes:
              0 - Success (valid for check)
              1 - Invalid (compilation errors or gate violation)
              2 - Incomplete (holes or proof holes present, no definite errors)
              3 - Revision mismatch or bad input
              4 - Internal compiler error
            """.formatted(COMPILER_VERSION, TARGET_PROFILE));
    }

    private static CliOptions parseOptions(String[] args) {
        boolean formatJson = false;
        boolean formatMermaid = false;
        String diagnosticDetail = "human";
        String profile = "standard";
        String verifyMode = "tier1";
        String smtBackend = "jvm";
        String policy = "default";
        String revision = null;
        String baseRevision = null;
        String patchPath = null;
        String holeId = null;
        String proofHoleId = null;
        String symbolId = null;
        String protocolId = null;
        String target = "valhalla";
        int maxBytes = 16000;
        boolean requireComplete = false;
        Set<String> trustedAllowlist = new LinkedHashSet<>();
        Set<String> frozenSpecs = new LinkedHashSet<>();
        List<Path> files = new ArrayList<>();

        for (String arg : args) {
            if (arg.equals("--format=json")) {
                formatJson = true;
            } else if (arg.equals("--format=mermaid")) {
                formatMermaid = true;
            } else if (arg.startsWith("--diagnostic-detail=")) {
                diagnosticDetail = arg.substring("--diagnostic-detail=".length());
            } else if (arg.startsWith("--profile=")) {
                profile = arg.substring("--profile=".length());
            } else if (arg.startsWith("--verify=")) {
                verifyMode = arg.substring("--verify=".length());
            } else if (arg.startsWith("--smt-backend=")) {
                smtBackend = arg.substring("--smt-backend=".length());
            } else if (arg.startsWith("--policy=")) {
                policy = arg.substring("--policy=".length());
            } else if (arg.startsWith("--revision=")) {
                revision = arg.substring("--revision=".length());
            } else if (arg.startsWith("--base-revision=")) {
                baseRevision = arg.substring("--base-revision=".length());
            } else if (arg.startsWith("--patch=")) {
                patchPath = arg.substring("--patch=".length());
            } else if (arg.startsWith("--hole=")) {
                holeId = arg.substring("--hole=".length());
            } else if (arg.startsWith("--proof-hole=")) {
                proofHoleId = arg.substring("--proof-hole=".length());
            } else if (arg.startsWith("--symbol=")) {
                symbolId = arg.substring("--symbol=".length());
            } else if (arg.startsWith("--protocol=")) {
                protocolId = arg.substring("--protocol=".length());
            } else if (arg.startsWith("--target=")) {
                target = arg.substring("--target=".length());
            } else if (arg.startsWith("--max-bytes=")) {
                maxBytes = Integer.parseInt(arg.substring("--max-bytes=".length()));
            } else if (arg.equals("--require-complete")) {
                requireComplete = true;
            } else if (arg.startsWith("--trusted-allowlist=")) {
                String raw = arg.substring("--trusted-allowlist=".length());
                for (String s : raw.split(",")) {
                    if (!s.isBlank()) trustedAllowlist.add(s.trim());
                }
            } else if (arg.startsWith("--frozen-specs=")) {
                String raw = arg.substring("--frozen-specs=".length());
                for (String s : raw.split(",")) {
                    if (!s.isBlank()) frozenSpecs.add(s.trim());
                }
            } else if (!arg.startsWith("-")) {
                files.add(Path.of(arg));
            }
        }

        return new CliOptions(
            formatJson, formatMermaid, diagnosticDetail, profile, verifyMode, smtBackend,
            policy, revision, baseRevision, patchPath, holeId, proofHoleId, symbolId,
            protocolId, target, maxBytes, requireComplete, trustedAllowlist, frozenSpecs, files
        );
    }

    private static String computeRevision(List<Path> files) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var file : files.stream().sorted().toList()) {
                if (Files.exists(file)) {
                    digest.update(file.toString().getBytes(StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(file));
                }
            }
            var hash = digest.digest();
            var sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "sha256:0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    private static String formatRevisionMismatchJson(
        String schemaVersion,
        String expected,
        String actual,
        String policyDigest,
        String targetProfile
    ) {
        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "%s",
              "policy_digest": "%s",
              "target_profile": "%s",
              "error": "revision_mismatch",
              "expected_revision": "%s",
              "actual_revision": "%s"
            }""".formatted(
                schemaVersion,
                COMPILER_VERSION,
                actual,
                policyDigest,
                targetProfile,
                expected,
                actual
            );
    }

    private record CliOptions(
        boolean formatJson,
        boolean formatMermaid,
        String diagnosticDetail,
        String profile,
        String verifyMode,
        String smtBackend,
        String policy,
        String revision,
        String baseRevision,
        String patchPath,
        String holeId,
        String proofHoleId,
        String symbolId,
        String protocolId,
        String target,
        int maxBytes,
        boolean requireComplete,
        Set<String> trustedAllowlist,
        Set<String> frozenSpecs,
        List<Path> files
    ) {
        CheckOptions toCheckOptions() {
            return new CheckOptions(
                profile,
                verifyMode,
                smtBackend,
                trustedAllowlist,
                IfcPcChecker.DEFAULT_PRIVILEGED_EFFECTS
            );
        }

        String policyDigest() {
            return computePolicyDigest(profile, policy, verifyMode, smtBackend, trustedAllowlist);
        }
    }
}
