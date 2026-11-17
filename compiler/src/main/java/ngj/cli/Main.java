package ngj.cli;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * NGJ Compiler CLI entry point.
 * 
 * Commands:
 *   ngjc check [--format=json] [--revision=<hash>] <files...>
 *   ngjc context [--format=json] --hole=<id> [--revision=<hash>] [--max-bytes=N]
 *   ngjc inspect [--format=json] --symbol=<id> [--revision=<hash>]
 *   ngjc build [--target=valhalla] <files...>
 * 
 * Exit codes:
 *   0 - Success (valid for check)
 *   1 - Invalid (compilation errors)
 *   2 - Incomplete (holes present, no errors)
 *   3 - Revision mismatch or bad input
 *   4 - Internal compiler error
 * 
 * Valhalla Foundation:
 *   NGJ targets JDK 28 Early-Access with JEP 401 (Value Objects) preview feature.
 *   Pinned build: JDK 28 EA Build 24 (2026-11-12)
 *   Download: https://jdk.java.net/28/
 *   
 *   Running on a non-Valhalla JDK will produce an explicit error (TARGET-02).
 */
public final class Main {
    
    public static final String SCHEMA_VERSION = "ngj.cli/1";
    public static final String COMPILER_VERSION = "0.1.0-mvp";
    public static final String TARGET_PROFILE = "valhalla/jdk28-ea-b24";
    
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
    
    private static int runCheck(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);
        var files = opts.files();
        
        if (files.isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }
        
        // Validate revision if specified
        if (opts.revision() != null) {
            String actualRevision = computeRevision(files);
            if (!opts.revision().equals(actualRevision)) {
                if (opts.formatJson()) {
                    stdout.println(formatRevisionMismatchJson(opts.revision(), actualRevision));
                } else {
                    stderr.println("Revision mismatch: expected " + opts.revision() + ", got " + actualRevision);
                }
                return ExitCode.BAD_INPUT;
            }
        }
        
        var compiler = new ngj.Compiler();
        var result = compiler.check(files);
        
        if (opts.formatJson()) {
            stdout.println(result.toJson(computeRevision(files)));
        } else {
            result.printHuman(stdout);
        }
        
        return result.exitCode();
    }
    
    private static int runContext(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);
        
        if (opts.holeId() == null) {
            stderr.println("Missing required --hole=<id> argument");
            return ExitCode.BAD_INPUT;
        }
        
        if (opts.files().isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }
        
        // Validate revision if specified
        if (opts.revision() != null) {
            String actualRevision = computeRevision(opts.files());
            if (!opts.revision().equals(actualRevision)) {
                if (opts.formatJson()) {
                    stdout.println(formatRevisionMismatchJson(opts.revision(), actualRevision));
                } else {
                    stderr.println("Revision mismatch: expected " + opts.revision() + ", got " + actualRevision);
                }
                return ExitCode.BAD_INPUT;
            }
        }
        
        var compiler = new ngj.Compiler();
        // First, check the files to populate parsedUnits
        compiler.check(opts.files());
        
        var result = compiler.context(opts.holeId(), opts.maxBytes());
        
        if (opts.formatJson()) {
            stdout.println(result.toJson());
        } else {
            result.printHuman(stdout);
        }
        
        return result.exitCode();
    }
    
    private static int runInspect(String[] args, PrintStream stdout, PrintStream stderr) {
        var opts = parseOptions(args);
        
        if (opts.symbolId() == null) {
            stderr.println("Missing required --symbol=<id> argument");
            return ExitCode.BAD_INPUT;
        }
        
        if (opts.files().isEmpty()) {
            stderr.println("No input files specified");
            return ExitCode.BAD_INPUT;
        }
        
        // Validate revision if specified
        if (opts.revision() != null) {
            String actualRevision = computeRevision(opts.files());
            if (!opts.revision().equals(actualRevision)) {
                if (opts.formatJson()) {
                    stdout.println(formatRevisionMismatchJson(opts.revision(), actualRevision));
                } else {
                    stderr.println("Revision mismatch: expected " + opts.revision() + ", got " + actualRevision);
                }
                return ExitCode.BAD_INPUT;
            }
        }
        
        var compiler = new ngj.Compiler();
        // First, check the files to populate parsedUnits
        compiler.check(opts.files());
        
        var result = compiler.inspect(opts.symbolId());
        
        if (opts.formatJson()) {
            stdout.println(result.toJson());
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
        
        var compiler = new ngj.Compiler();
        var result = compiler.build(files, opts.target());
        
        if (opts.formatJson()) {
            stdout.println(result.toJson(computeRevision(files)));
        } else {
            result.printHuman(stdout, stderr);
        }
        
        return result.exitCode();
    }
    
    /**
     * TARGET-02: Check that we're running on a Valhalla-capable JDK.
     * NGJ requires JDK 28+ with preview features enabled for JEP 401 Value Objects.
     * 
     * Can be skipped for testing by setting environment variable NGJ_SKIP_JDK_CHECK=true
     */
    private static JdkCheckResult checkValhallaJdk() {
        // Allow skipping for tests (CI uses JDK 28)
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
        
        // Check if preview features are enabled by testing for a preview API
        // We can detect this by checking if we can use value class features
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
        // Check if preview features are enabled by looking for preview-specific behavior
        // The simplest check is to see if Objects.hasIdentity exists (JEP 401 addition)
        try {
            java.util.Objects.class.getMethod("hasIdentity", Object.class);
            return true;
        } catch (NoSuchMethodException e) {
            // Method doesn't exist - preview may not be enabled or not JDK 28+
            // For older JDKs with NGJ_SKIP_JDK_CHECK, return true
            return "true".equals(System.getenv("NGJ_SKIP_JDK_CHECK"));
        }
    }
    
    private record JdkCheckResult(boolean isValid, String errorMessage) {}
    
    private static void printUsage(PrintStream out) {
        out.println("""
            NGJ Compiler %s (Valhalla foundation: %s)
            
            Usage:
              ngjc check [--format=json] [--revision=<hash>] <files...>
              ngjc context [--format=json] --hole=<id> [--revision=<hash>] [--max-bytes=N]
              ngjc inspect [--format=json] --symbol=<id> [--revision=<hash>]
              ngjc build [--target=valhalla] <files...>
            
            Exit codes:
              0 - Success (valid for check)
              1 - Invalid (compilation errors)
              2 - Incomplete (holes present, no errors)
              3 - Revision mismatch or bad input
              4 - Internal compiler error
              
            Valhalla Foundation:
              NGJ targets JDK 28 EA with JEP 401 Value Objects (Preview).
              All types are value types by default (identity-free).
              Download: %s
            """.formatted(COMPILER_VERSION, TARGET_PROFILE, VALHALLA_JDK_URL));
    }
    
    private static CliOptions parseOptions(String[] args) {
        boolean formatJson = false;
        String revision = null;
        String holeId = null;
        String symbolId = null;
        String target = "valhalla";
        int maxBytes = 16000;
        List<Path> files = new ArrayList<>();
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--format=json")) {
                formatJson = true;
            } else if (arg.startsWith("--revision=")) {
                revision = arg.substring("--revision=".length());
            } else if (arg.startsWith("--hole=")) {
                holeId = arg.substring("--hole=".length());
            } else if (arg.startsWith("--symbol=")) {
                symbolId = arg.substring("--symbol=".length());
            } else if (arg.startsWith("--target=")) {
                target = arg.substring("--target=".length());
            } else if (arg.startsWith("--max-bytes=")) {
                maxBytes = Integer.parseInt(arg.substring("--max-bytes=".length()));
            } else if (!arg.startsWith("-")) {
                files.add(Path.of(arg));
            }
        }
        
        return new CliOptions(formatJson, revision, holeId, symbolId, target, maxBytes, files);
    }
    
    private static String computeRevision(List<Path> files) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var file : files.stream().sorted().toList()) {
                if (Files.exists(file)) {
                    digest.update(file.toString().getBytes());
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
            return "unknown";
        }
    }
    
    private static String formatRevisionMismatchJson(String expected, String actual) {
        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "error": "revision_mismatch",
              "expected_revision": "%s",
              "actual_revision": "%s"
            }""".formatted(SCHEMA_VERSION, COMPILER_VERSION, expected, actual);
    }
    
    private record CliOptions(
        boolean formatJson,
        String revision,
        String holeId,
        String symbolId,
        String target,
        int maxBytes,
        List<Path> files
    ) {}
}
