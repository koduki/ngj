#!/usr/bin/env node
/**
 * Generic Antigravity PreToolUse boundary lock hook.
 * Loads boundary regexes from `.agents/ship.config.json` (with safe NGJ fallbacks):
 * - `neverUnlockablePatterns` (.env, .keystore, etc.): ALWAYS denied, never unlockable.
 * - `protectedPatterns` (CI, guards, hooks, agents, Dockerfile, wrapper, licenses): denied by default,
 *   unlockable ONLY for exact paths explicitly approved in S2 (`state.allowedProtectedPaths`).
 * - `contractPatterns` (normative docs, docs/adr, docs/specs/<slug>/spec.md): locked automatically
 *   after `state.contractLocked === true` unless explicitly in `allowedProtectedPaths`.
 * - `oraclePatterns` & `state.oracleTestFiles`: editable ONLY during `S3a_ORACLE_TEST_GEN`
 *   by the clean-room oracle author, then hard-locked once `state.oracleLocked === true`
 *   so coding agents can never weaken or adapt oracle tests to match implementation bugs.
 */
import fs from "node:fs";
import path from "node:path";
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
  return list.map((p) => new RegExp(p));
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

export function evaluateBoundaryWrite(targetPath, options = {}) {
  const normalized = String(targetPath || "").replace(/\\/g, "/");
  const {
    currentState = "",
    contractLocked = false,
    oracleLocked = false,
    oracleTestFiles = [],
    allowedProtectedPaths = [],
    config = {},
    overrideEnv = process.env.NGJ_ALLOW_BOUNDARY_EDIT === "1" || process.env.SHIP_ALLOW_BOUNDARY_EDIT === "1",
  } = options;

  if (!normalized) {
    return { decision: "allow" };
  }

  const neverUnlockable = compilePatterns(config?.boundaries?.neverUnlockablePatterns, DEFAULT_NEVER_UNLOCKABLE);
  if (neverUnlockable.some((re) => re.test(normalized))) {
    return {
      decision: "deny",
      reason: `Secret/signing boundary is never unlockable by an agent: ${normalized}.`,
    };
  }

  if (overrideEnv || matchesPathList(normalized, allowedProtectedPaths)) {
    return { decision: "allow" };
  }

  const protectedPatterns = compilePatterns(config?.boundaries?.protectedPatterns, DEFAULT_PROTECTED);
  if (protectedPatterns.some((re) => re.test(normalized))) {
    return {
      decision: "deny",
      reason: `Protected boundary cannot be modified without explicit S2 contract approval (allowedProtectedPaths): ${normalized}. Escalate to the human.`,
    };
  }

  const oraclePatterns = compilePatterns(config?.boundaries?.oraclePatterns, DEFAULT_ORACLE);
  const isOracleTarget =
    oraclePatterns.some((re) => re.test(normalized)) || matchesPathList(normalized, oracleTestFiles);

  // During S3a_ORACLE_TEST_GEN, ONLY oracle test files may be written — never production code!
  if (currentState === "S3a_ORACLE_TEST_GEN" && !isOracleTarget) {
    return {
      decision: "deny",
      reason: `During S3a_ORACLE_TEST_GEN, only clean-room oracle tests may be written; production/non-oracle edits are blocked: ${normalized}.`,
    };
  }

  // Once oracle tests are locked (S3b onward), coding agents cannot alter oracle tests to match implementation bugs!
  if (oracleLocked && isOracleTarget) {
    return {
      decision: "deny",
      reason: `Oracle test is locked after S3a_ORACLE_TEST_GEN (${normalized}). Fix the implementation to satisfy the spec oracle, or escalate to the human if the spec itself is ambiguous.`,
    };
  }

  const contractPatterns = compilePatterns(config?.boundaries?.contractPatterns, DEFAULT_CONTRACT);
  if (contractLocked && contractPatterns.some((re) => re.test(normalized))) {
    return {
      decision: "deny",
      reason: `Contract is locked after /ship S2 approval: ${normalized}. Escalate any contract revision to the human.`,
    };
  }

  return { decision: "allow" };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const raw = fs.readFileSync(0, "utf8").trim();
  const payload = raw ? JSON.parse(raw) : {};
  const target = payload?.toolCall?.args?.TargetFile ?? "";
  const cwd = payload?.workspacePaths?.[0] || process.cwd();
  const config = loadShipConfig(cwd);
  const state = loadState(resolveStatePath(cwd));
  const result = evaluateBoundaryWrite(target, {
    currentState: state?.currentState || "",
    contractLocked: Boolean(state?.contractLocked),
    oracleLocked: Boolean(state?.oracleLocked),
    oracleTestFiles: state?.oracleTestFiles || [],
    allowedProtectedPaths: state?.allowedProtectedPaths || [],
    config,
  });
  console.log(JSON.stringify(result));
}
