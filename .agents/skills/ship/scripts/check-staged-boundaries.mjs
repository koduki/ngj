#!/usr/bin/env node
/**
 * Secondary git pre-commit boundary lock guard for /ship.
 * Enforces boundary locks on staged git changes (`git diff --cached --name-status -z`)
 * for surfaces where `.agents/hooks.json` PreToolUse hooks do not fire:
 * - `neverUnlockablePatterns` (.env*, *.keystore, *.jks, *.p12): ALWAYS denied regardless
 *   of status, active /ship state, allowedProtectedPaths, or NGJ_ALLOW_BOUNDARY_EDIT.
 * - `protectedPatterns`: denied for any status during an active /ship run unless matched
 *   in `state.allowedProtectedPaths` (or overridden via NGJ_ALLOW_BOUNDARY_EDIT=1).
 * - `oraclePatterns` / `oracleTestFiles`: when `oracleLocked === true`, allows initial `A`
 *   addition on a feature branch and denies `M`, `D`, `T`, `R` (evaluating rename oldPath
 *   as `D` and new path as `A`).
 * - `contractPatterns`: when `contractLocked === true`, allows initial `A` addition and
 *   denies `M`, `D`, `T`, `R` unless matched in `allowedProtectedPaths`.
 */
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { loadShipConfig, loadState, resolveStatePath } from "./state.mjs";

const DEFAULT_NEVER_UNLOCKABLE = [
  /(^|\/)\.env(\.|$)/,
  /\.(keystore|jks|p12)$/i,
];

const DEFAULT_PROTECTED = [
  /(^|\/)\.github\/workflows\//,
  /(^|\/)scripts\/guards\//,
  /(^|\/)\.agents\/hooks\.json$/,
  /(^|\/)\.agents\/ship\.config\.json$/,
  /(^|\/)\.agents\/agents\//,
  /(^|\/)gradle\/wrapper\/gradle-wrapper\.properties$/,
  /(^|\/)Dockerfile$/,
  /(^|\/)(LICENSE|THIRD_PARTY_NOTICES\.md)$/,
  /(^|\/)LICENSES\//,
];

const DEFAULT_CONTRACT = [
  /(^|\/)docs\/SPECIFICATION\.md$/,
  /(^|\/)docs\/EBNF\.md$/,
  /(^|\/)docs\/AGENT_TOOLING\.md$/,
  /(^|\/)docs\/VALIDATION\.md$/,
  /(^|\/)docs\/adr\//,
  /(^|\/)docs\/specs\/[^/]+\/spec\.md$/,
];

const DEFAULT_ORACLE = [
  /(^|\/)tests\/oracle\//,
  /(^|\/)compiler\/src\/test\/java\/ngj\/oracle\//,
  /\.oracle\.test\.cjs$/,
  /OracleTest\.java$/,
  /_oracle\.py$/,
];

function compilePatterns(list, fallback) {
  if (!Array.isArray(list) || list.length === 0) return fallback;
  return list.map((p) => (p instanceof RegExp ? p : new RegExp(p)));
}

function normalizePath(p) {
  return String(p || "").trim().replace(/\\/g, "/").replace(/^\.\//, "");
}

function matchesPathList(normalizedTarget, pathList = []) {
  for (const item of pathList) {
    const clean = String(item || "").replace(/\\/g, "/").replace(/^\.\//, "").replace(/^\/+/, "");
    if (!clean) continue;
    if (normalizedTarget === clean || normalizedTarget.endsWith(`/${clean}`)) {
      return true;
    }
  }
  return false;
}

export function parseNameStatusOutput(raw, nulDelimited = false) {
  const text = String(raw || "");
  if (!text.trim() && !text.includes("\0")) return [];

  const entries = [];
  if (nulDelimited) {
    const tokens = text.split("\0");
    for (let i = 0; i < tokens.length; ) {
      const rawStatus = tokens[i];
      if (!rawStatus) {
        i += 1;
        continue;
      }
      const code = rawStatus.trim().charAt(0).toUpperCase();
      if (code === "R" || code === "C") {
        const oldPath = tokens[i + 1];
        const newPath = tokens[i + 2];
        i += 3;
        if (newPath !== undefined && newPath !== "") {
          entries.push({
            status: rawStatus.trim().toUpperCase(),
            oldPath: normalizePath(oldPath),
            path: normalizePath(newPath),
          });
        }
      } else {
        const filePath = tokens[i + 1];
        i += 2;
        if (filePath !== undefined && filePath !== "") {
          entries.push({
            status: code,
            path: normalizePath(filePath),
          });
        }
      }
    }
    return entries;
  }

  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const parts = trimmed.includes("\t") ? trimmed.split("\t") : trimmed.split(/\s+/);
    const rawStatus = parts[0].trim().toUpperCase();
    const code = rawStatus.charAt(0);
    if (code === "R" || code === "C") {
      if (parts.length >= 3) {
        entries.push({
          status: rawStatus,
          oldPath: normalizePath(parts[1]),
          path: normalizePath(parts[2]),
        });
      }
    } else if (parts.length >= 2) {
      entries.push({
        status: code,
        path: normalizePath(parts[1]),
      });
    }
  }
  return entries;
}

function evaluateSinglePath(statusCode, targetPath, ctx, violations) {
  const normalized = normalizePath(targetPath);
  if (!normalized) return;

  // Rule 1: Secret/signing files are ALWAYS denied regardless of status, active run, or overrideEnv.
  if (ctx.neverUnlockable.some((re) => re.test(normalized))) {
    violations.push({
      status: statusCode,
      path: normalized,
      rule: "never-unlockable",
      reason: `Secret/signing boundary is never unlockable: ${normalized}.`,
    });
    return;
  }

  // Rule 2: Outside an active /ship run or with explicit human overrideEnv, allow non-secret entries.
  if (ctx.overrideEnv || !ctx.hasActiveShipRun) {
    return;
  }

  const isAllowedProtected = matchesPathList(normalized, ctx.allowedProtectedPaths);

  // Rule 3: Protected paths are denied for any status unless explicitly unlocked in S2 allowedProtectedPaths.
  if (ctx.protectedPatterns.some((re) => re.test(normalized)) && !isAllowedProtected) {
    violations.push({
      status: statusCode,
      path: normalized,
      rule: "protected-boundary",
      reason: `Protected boundary cannot be modified without explicit S2 contract approval (allowedProtectedPaths): ${normalized}.`,
    });
    return;
  }

  const isMutatingExisting = statusCode === "M" || statusCode === "D" || statusCode === "T" || statusCode === "R";

  // Rule 4: Locked oracle tests allow initial `A` addition, deny `M`/`D`/`T`/`R`.
  if (ctx.oracleLocked && isMutatingExisting) {
    const isOracle =
      ctx.oraclePatterns.some((re) => re.test(normalized)) ||
      matchesPathList(normalized, ctx.oracleTestFiles);
    if (isOracle) {
      violations.push({
        status: statusCode,
        path: normalized,
        rule: "oracle-locked",
        reason: `Oracle test is locked after S3a_ORACLE_TEST_GEN (${statusCode} ${normalized}). Fix the implementation to satisfy the spec oracle, or escalate to the human.`,
      });
      return;
    }
  }

  // Rule 5: Locked contracts allow initial `A` addition, deny `M`/`D`/`T`/`R` unless in allowedProtectedPaths.
  if (ctx.contractLocked && isMutatingExisting && !isAllowedProtected) {
    if (ctx.contractPatterns.some((re) => re.test(normalized))) {
      violations.push({
        status: statusCode,
        path: normalized,
        rule: "contract-locked",
        reason: `Contract is locked after /ship S2 approval (${statusCode} ${normalized}). Escalate any contract revision to the human.`,
      });
    }
  }
}

export function evaluateStagedEntries(entries = [], options = {}) {
  const {
    hasActiveShipRun = false,
    currentState = "",
    contractLocked = false,
    oracleLocked = false,
    oracleTestFiles = [],
    allowedProtectedPaths = [],
    config = {},
    overrideEnv = process.env.NGJ_ALLOW_BOUNDARY_EDIT === "1" || process.env.SHIP_ALLOW_BOUNDARY_EDIT === "1",
  } = options;

  const ctx = {
    hasActiveShipRun: Boolean(hasActiveShipRun),
    currentState,
    contractLocked: Boolean(contractLocked),
    oracleLocked: Boolean(oracleLocked),
    oracleTestFiles,
    allowedProtectedPaths,
    overrideEnv: Boolean(overrideEnv),
    neverUnlockable: compilePatterns(config?.boundaries?.neverUnlockablePatterns, DEFAULT_NEVER_UNLOCKABLE),
    protectedPatterns: compilePatterns(config?.boundaries?.protectedPatterns, DEFAULT_PROTECTED),
    oraclePatterns: compilePatterns(config?.boundaries?.oraclePatterns, DEFAULT_ORACLE),
    contractPatterns: compilePatterns(config?.boundaries?.contractPatterns, DEFAULT_CONTRACT),
  };

  const violations = [];
  for (const entry of entries || []) {
    if (!entry) continue;
    const statusCode = String(entry.status || "").trim().charAt(0).toUpperCase();
    const entryPath = normalizePath(entry.path);
    const entryOldPath = entry.oldPath ? normalizePath(entry.oldPath) : "";

    if (statusCode === "R") {
      if (entryOldPath) {
        evaluateSinglePath("D", entryOldPath, ctx, violations);
        evaluateSinglePath("A", entryPath, ctx, violations);
      } else {
        evaluateSinglePath("R", entryPath, ctx, violations);
      }
    } else if (statusCode === "C") {
      // Copy creates a new destination path; check destination as addition (`A`).
      evaluateSinglePath("A", entryPath, ctx, violations);
    } else {
      evaluateSinglePath(statusCode, entryPath, ctx, violations);
    }
  }

  if (violations.length > 0) {
    return {
      decision: "deny",
      violations,
      reason: violations.map((v) => v.reason).join(" | "),
    };
  }

  return {
    decision: "allow",
    violations: [],
    reason: null,
  };
}

export function runCheckStagedBoundaries(cwd = process.cwd()) {
  const resolvedCwd = path.resolve(cwd);
  const raw = execFileSync("git", ["diff", "--cached", "--name-status", "-z"], {
    cwd: resolvedCwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  const entries = parseNameStatusOutput(raw, true);
  const config = loadShipConfig(resolvedCwd);
  const state = loadState(resolveStatePath(resolvedCwd));
  const hasActiveShipRun = Boolean(state && state.currentState !== "DONE");

  return evaluateStagedEntries(entries, {
    hasActiveShipRun,
    currentState: state?.currentState || "",
    contractLocked: Boolean(state?.contractLocked),
    oracleLocked: Boolean(state?.oracleLocked),
    oracleTestFiles: state?.oracleTestFiles || [],
    allowedProtectedPaths: state?.allowedProtectedPaths || [],
    config,
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  let cwd = process.cwd();
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--cwd" && i + 1 < args.length) {
      cwd = args[++i];
    } else if (args[i].startsWith("--cwd=")) {
      cwd = args[i].slice("--cwd=".length);
    }
  }

  try {
    const result = runCheckStagedBoundaries(cwd);
    if (result.decision === "deny") {
      console.error("[check-staged-boundaries] COMMIT BLOCKED — boundary violation(s) detected:");
      for (const v of result.violations) {
        console.error(`  - [${v.rule}] ${v.status} ${v.path}: ${v.reason}`);
      }
      console.error(
        "Remediation: unstage the restricted file(s), request S2 allowedProtectedPaths approval, or (for human-approved non-secret edits only) re-run with NGJ_ALLOW_BOUNDARY_EDIT=1.",
      );
      process.exit(1);
    }
    process.exit(0);
  } catch (err) {
    console.error(`[check-staged-boundaries] Error: ${err.message}`);
    process.exit(1);
  }
}
