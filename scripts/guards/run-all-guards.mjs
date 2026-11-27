#!/usr/bin/env node
/**
 * NGJ non-ignorable deterministic guards (pre-commit, PostToolUse hook, and CI).
 *
 * Design principles:
 * - Zero external dependencies (runs on standard Node 22).
 * - Cannot be bypassed with inline ignore comments.
 * - Every violation includes actionable remediation guidance for coding agents.
 */
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const SELF_GUARD_FILES = new Set([
  "scripts/guards/run-all-guards.mjs",
  "scripts/guards/ci-log-compressor.mjs",
  "tests/agentic-harness.test.cjs",
]);

// Existing audited Java suppressions (new additions are blocked by default).
const ALLOWED_JAVA_SUPPRESSIONS = new Map([
  ["compiler/src/main/java/ngj/typechecker/TypeChecker.java", new Set([
    '@SuppressWarnings("unchecked")',
  ])],
]);

const SECRET_SCAN_EXCLUDED = new Set([
  "scripts/guards/run-all-guards.mjs",
  "tests/agentic-harness.test.cjs",
]);

// Guard 8 (subagent frontmatter): Verified Antigravity custom subagent allowlists (prevents unmapped-tool hangs).
const ALLOWED_SUBAGENT_TOOLS = new Set([
  "view_file",
  "write_to_file",
  "replace_file_content",
  "multi_replace_file_content",
  "list_dir",
  "find_by_name",
  "grep_search",
  "run_command",
  "manage_task",
  "send_message",
]);

const SUBAGENT_WRITE_TOOLS = new Set([
  "write_to_file",
  "replace_file_content",
  "multi_replace_file_content",
]);

const READ_ONLY_SUBAGENTS = new Set([
  "ship-recon",
  "ship-architect",
  "ship-verifier",
  "ship-reviewer",
]);

const ALLOWED_SUBAGENT_MODELS = new Set(["inherit", "flash", "pro"]);
const ALLOWED_COMMAND_POLICIES = new Set(["off", "auto", "eager", "sandbox"]);

function stripYamlQuotes(raw) {
  const s = String(raw || "").trim();
  if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
    return s.slice(1, -1).trim();
  }
  return s;
}

function parseAgentFrontmatter(content) {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  if (lines[0]?.trim() !== "---") {
    return { validDelimiters: false, fields: {}, tools: [] };
  }
  let endIdx = -1;
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === "---") {
      endIdx = i;
      break;
    }
  }
  if (endIdx === -1) {
    return { validDelimiters: false, fields: {}, tools: [] };
  }

  const fields = {};
  const tools = [];
  let currentListKey = null;

  for (let i = 1; i < endIdx; i++) {
    const rawLine = lines[i];
    const trimmed = rawLine.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;

    const listMatch = rawLine.match(/^\s*-\s+(.+)$/);
    if (listMatch && currentListKey === "tools") {
      const val = stripYamlQuotes(listMatch[1]);
      if (val) tools.push({ value: val, line: i + 1 });
      continue;
    }

    const kvMatch = rawLine.match(/^([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)$/);
    if (kvMatch) {
      const key = kvMatch[1];
      const rawVal = kvMatch[2].trim();
      if (key === "tools") {
        if (rawVal.startsWith("[") && rawVal.endsWith("]")) {
          const inner = rawVal.slice(1, -1).trim();
          if (inner) {
            for (const part of inner.split(",")) {
              const val = stripYamlQuotes(part);
              if (val) tools.push({ value: val, line: i + 1 });
            }
          }
          currentListKey = null;
        } else if (rawVal === "") {
          currentListKey = "tools";
        } else {
          tools.push({ value: stripYamlQuotes(rawVal), line: i + 1 });
          currentListKey = null;
        }
      } else {
        currentListKey = null;
        fields[key] = { value: stripYamlQuotes(rawVal), line: i + 1 };
      }
    }
  }

  return { validDelimiters: true, fields, tools };
}

function normalizeRel(filePath) {
  return filePath.replace(/\\/g, "/").replace(/^\.\//, "");
}

function listTrackedFiles(repoRoot, changedOnly) {
  const execOpts = { cwd: repoRoot, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] };
  if (changedOnly) {
    try {
      const out = execSync("git status --porcelain", execOpts);
      const files = out
        .split("\n")
        .filter(Boolean)
        .map((line) => line.slice(3).trim())
        .map((entry) => (entry.includes(" -> ") ? entry.split(" -> ").pop() : entry))
        .map(normalizeRel)
        .filter((rel) => fs.existsSync(path.join(repoRoot, rel)) && fs.statSync(path.join(repoRoot, rel)).isFile());
      return Array.from(new Set(files));
    } catch {
      // Fallback to directory walk below.
    }
  }

  try {
    const out = execSync("git ls-files --cached --others --exclude-standard", execOpts);
    return out
      .split("\n")
      .map((s) => normalizeRel(s.trim()))
      .filter((rel) => rel && fs.existsSync(path.join(repoRoot, rel)) && fs.statSync(path.join(repoRoot, rel)).isFile());
  } catch {
    const result = [];
    function walk(dir) {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.name === ".git" || entry.name === "node_modules" || entry.name === ".gradle" || entry.name === ".ship" || entry.name === "build") {
          continue;
        }
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          walk(full);
        } else if (entry.isFile()) {
          result.push(normalizeRel(path.relative(repoRoot, full)));
        }
      }
    }
    walk(repoRoot);
    return result;
  }
}

function isTextCodeFile(rel) {
  return /\.(java|kt|kts|js|cjs|mjs|ts|tsx|py|sh|yml|yaml|json|xml|html|ngj)$/.test(rel) || path.basename(rel) === "Dockerfile";
}

export function runGuards(repoRoot, options = {}) {
  const { changedOnly = false } = options;
  const files = listTrackedFiles(repoRoot, changedOnly);
  const violations = [];

  const addViolation = (ruleId, file, line, detail, remediation) => {
    violations.push({ ruleId, file, line, detail, remediation });
  };

  // --- Per-file static scans ---
  for (const rel of files) {
    // Guard 6a: forbid committing keystore or signing binaries
    if (/\.(keystore|jks|p12)$/i.test(rel)) {
      addViolation(
        "no-secret-or-keystore-commit",
        rel,
        1,
        "Keystore or signing binary found in repository",
        "Remove the signing key from the repository immediately and provide credentials via environment variables or GitHub Secrets.",
      );
      continue;
    }

    // Guard 8: validate custom subagent YAML frontmatter, official tool allowlists, model tiers, and read-only invariants
    if (/(^|\/)\.agents\/agents\/[^/]+\.md$/.test(rel)) {
      const abs = path.join(repoRoot, rel);
      const content = fs.readFileSync(abs, "utf8");
      const parsed = parseAgentFrontmatter(content);
      const expectedName = path.basename(rel, ".md");
      if (!parsed.validDelimiters) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          1,
          "Custom subagent file is missing valid YAML frontmatter delimited by '---'",
          "Add valid YAML frontmatter (name, description, tools, mainAgent, subagent, model, commandExecutionPolicy) at the top of the file.",
        );
        continue;
      }

      const { fields, tools } = parsed;
      const agentName = fields.name?.value || "";
      if (!agentName || !/^[a-z0-9][a-z0-9_.-]*$/.test(agentName)) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.name?.line || 1,
          `Invalid or missing subagent 'name': '${agentName}'`,
          `Set 'name: ${expectedName}' matching [a-z0-9][a-z0-9_.-]*.`,
        );
      } else if (agentName !== expectedName) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.name?.line || 1,
          `Subagent name '${agentName}' does not match filename '${expectedName}.md'`,
          `Rename the file or set 'name: ${expectedName}' so Antigravity subagent lookup stays unambiguous.`,
        );
      }

      if (!fields.description?.value) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.description?.line || 1,
          "Custom subagent is missing a non-empty 'description' field",
          "Provide a clear description explaining when the orchestrator should invoke this subagent.",
        );
      }

      if (fields.subagent?.value !== "true") {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.subagent?.line || 1,
          `Custom subagent must set 'subagent: true' (got '${fields.subagent?.value || ""}')`,
          "Set 'subagent: true' in frontmatter so the agent can be invoked via invoke_subagent.",
        );
      }

      const modelVal = fields.model?.value || "";
      if (!ALLOWED_SUBAGENT_MODELS.has(modelVal)) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.model?.line || 1,
          `Invalid custom subagent model tier '${modelVal}'`,
          "Set 'model' to one of the supported Antigravity tiers: 'inherit', 'flash', or 'pro'.",
        );
      }

      const policyVal = fields.commandExecutionPolicy?.value || "";
      if (!ALLOWED_COMMAND_POLICIES.has(policyVal)) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          fields.commandExecutionPolicy?.line || 1,
          `Invalid or missing commandExecutionPolicy '${policyVal}'`,
          "Set 'commandExecutionPolicy' to one of: 'off', 'auto', 'eager', or 'sandbox'.",
        );
      }

      if (tools.length === 0) {
        addViolation(
          "custom-subagent-frontmatter",
          rel,
          1,
          "Custom subagent must declare an explicit non-empty 'tools' allowlist",
          "Declare only the minimal verified tools needed (e.g., view_file, run_command, send_message).",
        );
      } else {
        const isReadOnlyAgent = READ_ONLY_SUBAGENTS.has(agentName) || READ_ONLY_SUBAGENTS.has(expectedName);
        for (const toolEntry of tools) {
          if (!ALLOWED_SUBAGENT_TOOLS.has(toolEntry.value)) {
            addViolation(
              "custom-subagent-frontmatter",
              rel,
              toolEntry.line,
              `Unmapped or unsupported tool name '${toolEntry.value}' in custom subagent tools list`,
              `Replace '${toolEntry.value}' with a verified Antigravity core tool (${Array.from(ALLOWED_SUBAGENT_TOOLS).join(", ")}) to avoid subagent tool-resolution hangs.`,
            );
          }
          if (isReadOnlyAgent && SUBAGENT_WRITE_TOOLS.has(toolEntry.value)) {
            addViolation(
              "custom-subagent-frontmatter",
              rel,
              toolEntry.line,
              `Read-only subagent '${expectedName}' must not be granted write tool '${toolEntry.value}'`,
              "Remove all file-write tools from read-only subagents (ship-recon, ship-architect, ship-verifier, ship-reviewer).",
            );
          }
        }
      }
      continue;
    }

    if (!isTextCodeFile(rel)) continue;
    const abs = path.join(repoRoot, rel);
    const content = fs.readFileSync(abs, "utf8");
    const lines = content.split("\n");

    const skipSelf = SELF_GUARD_FILES.has(rel);

    lines.forEach((lineText, idx) => {
      const lineNo = idx + 1;

      // Guard 1: forbid lint/type suppression directives
      if (!skipSelf && !rel.startsWith("docs/") && !rel.startsWith(".agents/")) {
        const bypassMatch = lineText.match(/eslint-disable|@ts-ignore|@ts-nocheck|@ts-expect-error|#\s*noqa\b/);
        if (bypassMatch) {
          addViolation(
            "no-lint-or-type-bypass",
            rel,
            lineNo,
            `Suppression directive '${bypassMatch[0]}' is forbidden`,
            "Fix the underlying type or lint issue properly instead of suppressing checks. If a contract needs revision, escalate to the human.",
          );
        }
      }

      // Guard 2: forbid unconditional test skips / @Disabled / @Ignore
      if (!skipSelf && (rel.startsWith("tests/") || rel.includes("/src/test/"))) {
        const skipMatch = lineText.match(/@Disabled\b|@Ignore\b|\b(?:test|it|describe)\.skip\s*\(|@unittest\.skip\b/);
        if (skipMatch) {
          addViolation(
            "no-test-skip-or-ignore",
            rel,
            lineNo,
            `Unconditional test skip '${skipMatch[0]}' is forbidden`,
            "Do not skip or weaken tests to pass CI. Fix the implementation or reproduce the real defect.",
          );
        }
      }

      // Guard 3: forbid new @SuppressWarnings in Java
      if (rel.endsWith(".java")) {
        const supMatch = lineText.match(/@SuppressWarnings\([^)]*\)/);
        if (supMatch) {
          const allowed = ALLOWED_JAVA_SUPPRESSIONS.get(rel);
          if (!allowed || !allowed.has(supMatch[0])) {
            addViolation(
              "no-new-java-suppress",
              rel,
              lineNo,
              `Unapproved Java suppression '${supMatch[0]}'`,
              "Resolve the compiler/lint warning cleanly instead of adding @SuppressWarnings. If an unchecked generic cast truly requires it, ask the human to audit and allowlist it.",
            );
          }
        }
      }

      // Guard 5: keep compiler core deterministic with zero LLM API dependencies
      if (rel.startsWith("compiler/src/main/java/")) {
        const llmMatch = lineText.match(/api\.openai\.com|generativelanguage\.googleapis\.com|api\.anthropic\.com/i);
        if (llmMatch) {
          addViolation(
            "no-compiler-llm-dependency",
            rel,
            lineNo,
            `External LLM endpoint reference '${llmMatch[0]}' is forbidden inside compiler core`,
            "Keep the NGJ compiler deterministic and usable without an LLM (README.md / SPECIFICATION.md §1.1).",
          );
        }
      }

      // Guard 6b: forbid accidental secret / private key literals
      if (!SECRET_SCAN_EXCLUDED.has(rel) && !rel.startsWith("docs/") && !rel.startsWith(".agents/")) {
        const secretPatterns = [
          /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/,
          /\bgh[pousr]_[A-Za-z0-9_]{20,}\b/,
          /\bgithub_pat_[A-Za-z0-9_]{20,}\b/,
          /\bsk-(?:proj-|ant-)[A-Za-z0-9_-]{20,}\b/,
          /\bAIza[0-9A-Za-z_-]{35}\b/,
          /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/,
        ];
        for (const pat of secretPatterns) {
          if (pat.test(lineText)) {
            addViolation(
              "no-secret-or-keystore-commit",
              rel,
              lineNo,
              "Potential hardcoded secret or private key detected",
              "Remove the secret immediately and load credentials from environment variables or GitHub Secrets.",
            );
            break;
          }
        }
      }
    });

    // Guard 7: clean-room oracle test integrity (must reference acceptance criteria AC-* and contain real assertions)
    const isOracleFile =
      /(^|\/)tests\/oracle\/.+\.(?:oracle\.test\.cjs|py)$/.test(rel) ||
      /OracleTest\.java$/.test(rel);
    if (isOracleFile) {
      if (!/\bAC-\d+\b/.test(content)) {
        addViolation(
          "oracle-test-integrity",
          rel,
          1,
          "Oracle test file does not reference any acceptance criterion ID (e.g., AC-1)",
          "Map each oracle test case explicitly to a spec acceptance criterion ID (AC-1, AC-2, ...) from docs/specs/<slug>/acceptance.json.",
        );
      }
      if (!/\b(assert|assertEquals|assertNotEquals|assertTrue|assertFalse|assertNotNull|assertNull|assertThrows|assertDoesNotThrow|self\.assert)\b/.test(content)) {
        addViolation(
          "oracle-test-integrity",
          rel,
          1,
          "Oracle test file contains no assertions",
          "Add behavioral contract assertions derived strictly from the specification so the oracle cannot trivially pass.",
        );
      }
    }
  }

  // --- Structural repository-wide invariants (always checked unless changedOnly excludes relevant files) ---
  const shouldCheck = (targetRel) => !changedOnly || files.includes(targetRel);

  // Guard 4a: Root Gradle Valhalla & preview build invariants
  const rootGradleRel = "build.gradle.kts";
  const rootGradleAbs = path.join(repoRoot, rootGradleRel);
  if (fs.existsSync(rootGradleAbs) && shouldCheck(rootGradleRel)) {
    const gradleText = fs.readFileSync(rootGradleAbs, "utf8");
    const requiredChecks = [
      { re: /VALHALLA_JDK_VERSION\s*=\s*28\b/, msg: "VALHALLA_JDK_VERSION must remain 28 in build.gradle.kts" },
      { re: /"--enable-preview"/, msg: "build.gradle.kts must keep --enable-preview enabled for compilation and tests" },
    ];
    for (const check of requiredChecks) {
      if (!check.re.test(gradleText)) {
        addViolation(
          "valhalla-and-build-invariants",
          rootGradleRel,
          1,
          check.msg,
          "Restore the required Valhalla preview/build configuration in build.gradle.kts.",
        );
      }
    }
  }

  // Guard 4b: Compiler Gradle entrypoint & fatJar invariants
  const compilerGradleRel = "compiler/build.gradle.kts";
  const compilerGradleAbs = path.join(repoRoot, compilerGradleRel);
  if (fs.existsSync(compilerGradleAbs) && shouldCheck(compilerGradleRel)) {
    const compilerGradleText = fs.readFileSync(compilerGradleAbs, "utf8");
    const requiredChecks = [
      { re: /mainClass\.set\("ngj\.cli\.Main"\)/, msg: 'compiler/build.gradle.kts must keep mainClass.set("ngj.cli.Main")' },
      { re: /register<Jar>\("fatJar"\)/, msg: 'compiler/build.gradle.kts must keep the fatJar task for CI/container packaging' },
    ];
    for (const check of requiredChecks) {
      if (!check.re.test(compilerGradleText)) {
        addViolation(
          "valhalla-and-build-invariants",
          compilerGradleRel,
          1,
          check.msg,
          "Restore the CLI mainClass and fatJar task in compiler/build.gradle.kts.",
        );
      }
    }
  }

  // Guard 4c: Dockerfile preview entrypoint & non-root runtime invariants
  const dockerfileRel = "Dockerfile";
  const dockerfileAbs = path.join(repoRoot, dockerfileRel);
  if (fs.existsSync(dockerfileAbs) && shouldCheck(dockerfileRel)) {
    const dockerText = fs.readFileSync(dockerfileAbs, "utf8");
    const requiredChecks = [
      { re: /ENTRYPOINT\s*\[[^\]]*"--enable-preview"/, msg: 'Dockerfile runtime ENTRYPOINT must keep "--enable-preview"' },
      { re: /^USER\s+ngj\b/m, msg: "Dockerfile runtime stage must run as non-root USER ngj" },
    ];
    for (const check of requiredChecks) {
      if (!check.re.test(dockerText)) {
        addViolation(
          "valhalla-and-build-invariants",
          dockerfileRel,
          1,
          check.msg,
          "Restore --enable-preview ENTRYPOINT and non-root USER ngj in Dockerfile.",
        );
      }
    }
  }

  // Guard 9: .agents JSON config validity
  for (const cfgRel of [".agents/ship.config.json", ".agents/hooks.json"]) {
    const cfgAbs = path.join(repoRoot, cfgRel);
    if (fs.existsSync(cfgAbs) && shouldCheck(cfgRel)) {
      try {
        JSON.parse(fs.readFileSync(cfgAbs, "utf8"));
      } catch (err) {
        addViolation(
          "agents-config-json-validity",
          cfgRel,
          1,
          `Invalid JSON in ${cfgRel}: ${err.message}`,
          `Fix JSON syntax in ${cfgRel}.`,
        );
      }
    }
  }

  return {
    passed: violations.length === 0,
    checkedFiles: files.length,
    changedOnly,
    violationCount: violations.length,
    violations,
  };
}

// CLI entrypoint
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = new Set(process.argv.slice(2));
  const changedOnly = args.has("--changed-only");
  const asJson = args.has("--json");
  const repoRoot = process.cwd();
  const report = runGuards(repoRoot, { changedOnly });

  if (asJson) {
    console.log(JSON.stringify(report, null, 2));
  } else if (report.passed) {
    console.log(`OK: NGJ non-ignorable guards passed (${report.checkedFiles} files checked${changedOnly ? ", changed-only" : ""}).`);
  } else {
    console.error(`GUARD FAILED (${report.violationCount} violation(s)):`);
    for (const v of report.violations) {
      console.error(`- ${v.file}:${v.line} [${v.ruleId}] ${v.detail}`);
      console.error(`  Remediation: ${v.remediation}`);
    }
  }
  process.exit(report.passed ? 0 : 1);
}
