#!/usr/bin/env node
/**
 * Polls GitHub PR CI checks and ChatGPT Codex Connector PR review status until
 * both finish (or timeout), following `.agents/ship.config.json`:
 * - Parses real GitHub Codex Connector `<!-- codex-review-state:v1 {...} -->`
 *   metadata (both nested `code_review.*` and flat schemas), checks `updatedAt`/
 *   `updated_at` and `headRefOid`, and never treats `running` or `eyes`-only state
 *   as `clean`.
 * - Grants up to `+runningGraceSeconds` (default 300s, capped at
 *   `maxTotalTimeoutSeconds`, default 900s) while Codex is actively running.
 * - Retries transient `gh` HTTP 5xx / network / 429 failures with exponential
 *   backoff (`runGhWithRetry`, max 4 retries), failing fast on deterministic 4xx.
 * - Deduplicates `@codex review` across invocations via fresh PR comments and
 *   emits periodic single-line heartbeats to `stderr` (keeping `stdout` strict JSON).
 * - Compresses failed CI logs via LogSage-style preprocessing (never dumps raw logs).
 */
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { compressCiLog } from "../../../../scripts/guards/ci-log-compressor.mjs";
import { loadShipConfig } from "./state.mjs";

function runGh(args) {
  return execFileSync("gh", args, { encoding: "utf8", maxBuffer: 20 * 1024 * 1024 });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const CODEX_LOGIN_RE = /codex|chatgpt-codex/i;
const QUOTA_RE = /(usage limit|rate limit|quota exceeded|out of (?:code )?review|limit reached)/i;
const NOT_CONFIGURED_RE = /to use codex here|create an environment for this repo|chatgpt\.com\/codex\/cloud\/settings\/environments/i;
const BLOCKING_RE = /\b(P0|P1)\b|badge\/P[01]|critical|must fix|severity:\s*(?:critical|high)/i;
const CODEX_STATE_MARKER_RE = /<!--\s*codex-review-state:v1\s+([\s\S]*?)\s*-->/gi;
const CODEX_MENTION_RE = /@codex\s+review\b/i;

const RATE_LIMIT_RE = /\b429\b|too many requests|(?:secondary|api)?\s*rate\s+limit(?:\s+already)?\s+exceeded|\brate\s+limit\b|abuse detection/i;
const DETERMINISTIC_4XX_RE =
  /\bHTTP\s*4(?:00|01|03|04|22)\b|\bstatus(?:\s*code)?\s*[:=]?\s*4(?:00|01|03|04|22)\b|\b(?:400\s+Bad Request|401\s+Unauthorized|403\s+Forbidden|404\s+Not Found|422\s+Unprocessable)\b|unknown flag|unknown command/i;
const TRANSIENT_GH_RE =
  /\bHTTP\s*5(?:00|02|03|04)\b|\bstatus(?:\s*code)?\s*[:=]?\s*5(?:00|02|03|04)\b|\b5(?:00|02|03|04)\s+(?:internal server error|bad gateway|service unavailable|gateway timeout)\b|bad gateway|service unavailable|gateway timeout|internal server error|ETIMEDOUT|ECONNRESET|ECONNREFUSED|ENOTFOUND|EAI_AGAIN|socket hang up|\bTLS\b|timed out|connection reset/i;

function getLatestTimestamp(item) {
  if (!item || typeof item !== "object") return { ms: null, iso: null };
  const candidates = [
    item.updatedAt,
    item.updated_at,
    item.submittedAt,
    item.submitted_at,
    item.createdAt,
    item.created_at,
  ];
  let bestMs = null;
  let bestIso = null;
  for (const candidate of candidates) {
    if (!candidate) continue;
    const parsed = Date.parse(String(candidate));
    if (Number.isFinite(parsed) && (bestMs === null || parsed > bestMs)) {
      bestMs = parsed;
      bestIso = String(candidate);
    }
  }
  return { ms: bestMs, iso: bestIso };
}

function isItemFresh(item, sinceMs) {
  if (!sinceMs || !Number.isFinite(sinceMs)) return true;
  const { ms } = getLatestTimestamp(item);
  if (ms === null) return true;
  return ms >= sinceMs - 5000;
}

function isCommitShaMatch(expectedOid, actualOid) {
  const expected = typeof expectedOid === "string" ? expectedOid.trim().toLowerCase() : "";
  const actual = typeof actualOid === "string" ? actualOid.trim().toLowerCase() : "";
  if (!expected || !actual) return true;
  if (expected === actual) return true;
  const minLen = Math.min(expected.length, actual.length);
  return minLen >= 7 && (expected.startsWith(actual) || actual.startsWith(expected));
}

function isTransientGhError(err) {
  if (!err) return false;
  const statusCode =
    typeof err.statusCode === "number"
      ? err.statusCode
      : typeof err.status === "number" && err.status >= 100
        ? err.status
        : null;

  const stderrText = Buffer.isBuffer(err.stderr) ? err.stderr.toString("utf8") : err.stderr;
  const stdoutText = Buffer.isBuffer(err.stdout) ? err.stdout.toString("utf8") : err.stdout;
  const text = [err.code, err.message, stderrText, stdoutText].filter(Boolean).join("\n");

  if (statusCode === 429 || RATE_LIMIT_RE.test(text)) {
    return true;
  }
  if ((statusCode !== null && statusCode >= 400 && statusCode < 500) || DETERMINISTIC_4XX_RE.test(text)) {
    return false;
  }
  if ((statusCode !== null && statusCode >= 500 && statusCode < 600) || TRANSIENT_GH_RE.test(text)) {
    return true;
  }
  return false;
}

function normalizeSubReview(obj) {
  if (!obj || typeof obj !== "object" || Array.isArray(obj)) return null;
  const rawStatus = obj.status;
  const status = typeof rawStatus === "string" ? rawStatus.trim().toLowerCase() : "";
  const rawSha = obj.commit_sha ?? obj.headRefOid ?? obj.commitSha ?? null;
  const headRefOid = typeof rawSha === "string" && rawSha.trim() ? rawSha.trim() : null;
  const rawHasAny = obj.has_any_findings ?? obj.hasAnyFindings;
  const hasAnyFindings = typeof rawHasAny === "boolean" ? rawHasAny : null;
  const rawHasUnresolved = obj.has_unresolved_findings ?? obj.hasUnresolvedFindings;
  const hasUnresolvedFindings = typeof rawHasUnresolved === "boolean" ? rawHasUnresolved : null;

  if (!status && !headRefOid) return null;

  return {
    status,
    headRefOid,
    hasAnyFindings,
    hasUnresolvedFindings,
  };
}

function combineParsedCodexState(raw, expectedHeadRefOid = "") {
  const hasNestedContainer =
    (raw.code_review && typeof raw.code_review === "object" && !Array.isArray(raw.code_review)) ||
    (raw.security_review && typeof raw.security_review === "object" && !Array.isArray(raw.security_review));

  const parseFlat = () => {
    const rawStatus = raw.status ?? "";
    const status = typeof rawStatus === "string" ? rawStatus.trim().toLowerCase() : "";
    const rawSha = raw.headRefOid ?? raw.commit_sha ?? raw.commitSha ?? null;
    const headRefOid = typeof rawSha === "string" && rawSha.trim() ? rawSha.trim() : null;
    const rawHasAny = raw.has_any_findings ?? raw.hasAnyFindings;
    const hasAnyFindings = typeof rawHasAny === "boolean" ? rawHasAny : null;
    const rawHasUnresolved = raw.has_unresolved_findings ?? raw.hasUnresolvedFindings;
    const hasUnresolvedFindings = typeof rawHasUnresolved === "boolean" ? rawHasUnresolved : null;

    if (expectedHeadRefOid && !isCommitShaMatch(expectedHeadRefOid, headRefOid)) {
      return null;
    }
    return {
      version: 1,
      status,
      headRefOid,
      hasAnyFindings,
      hasUnresolvedFindings,
      raw,
    };
  };

  if (!hasNestedContainer) {
    return parseFlat();
  }

  const codeReview = normalizeSubReview(raw.code_review);
  const securityReview = normalizeSubReview(raw.security_review);
  let active = [codeReview, securityReview].filter(Boolean);

  if (active.length === 0) {
    return parseFlat();
  }

  if (expectedHeadRefOid) {
    active = active.filter((sub) => isCommitShaMatch(expectedHeadRefOid, sub.headRefOid));
    if (active.length === 0) {
      return null;
    }
  }

  let status = "";
  if (active.some((sub) => sub.status === "running")) {
    status = "running";
  } else if (active.every((sub) => sub.status === "completed")) {
    status = "completed";
  } else {
    status = active.find((sub) => sub.status && sub.status !== "completed")?.status ?? active[0]?.status ?? "";
  }

  const combineBool = (key) => {
    if (active.some((sub) => sub[key] === true)) return true;
    if (active.some((sub) => sub[key] === false)) return false;
    return null;
  };

  const hasAnyFindings = combineBool("hasAnyFindings");
  const hasUnresolvedFindings = combineBool("hasUnresolvedFindings");

  const rawFallbackSha = raw.headRefOid ?? raw.commit_sha ?? raw.commitSha ?? null;
  const fallbackSha = typeof rawFallbackSha === "string" && rawFallbackSha.trim() ? rawFallbackSha.trim() : null;
  const headRefOid = active.find((sub) => sub.headRefOid)?.headRefOid ?? fallbackSha;

  return {
    version: 1,
    status,
    headRefOid,
    hasAnyFindings,
    hasUnresolvedFindings,
    raw,
  };
}

export function parseCodexReviewState(body) {
  if (typeof body !== "string" || !body) return null;
  let lastValid = null;
  const re = new RegExp(CODEX_STATE_MARKER_RE.source, "gi");
  let match;
  while ((match = re.exec(body)) !== null) {
    const rawJson = match[1]?.trim();
    if (!rawJson) continue;
    try {
      const parsed = JSON.parse(rawJson);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) continue;
      lastValid = combineParsedCodexState(parsed);
    } catch {
      // Ignore malformed JSON blocks; if none are valid, return null.
    }
  }
  return lastValid;
}

export function hasFreshCodexMention(issueComments = [], options = {}) {
  const { sinceIso, autoMentionComment = "@codex review" } = options || {};
  const sinceMs = sinceIso ? Date.parse(sinceIso) : 0;
  const normalizedTarget = String(autoMentionComment || "").trim().toLowerCase();
  const useCustomIncludes = normalizedTarget.length > 0 && normalizedTarget !== "@codex review";

  for (const c of issueComments || []) {
    const body = String(c?.body || "");
    if (!body.trim()) continue;
    const matchesMention =
      CODEX_MENTION_RE.test(body) || (useCustomIncludes && body.toLowerCase().includes(normalizedTarget));
    if (!matchesMention) continue;
    if (isItemFresh(c, sinceMs)) {
      return true;
    }
  }
  return false;
}

export function extractCodexFindings(reviews = [], lineComments = [], issueComments = [], options = {}) {
  const sinceMs = options?.sinceIso ? Date.parse(options.sinceIso) : 0;
  const expectedHeadRefOid = typeof options?.headRefOid === "string" ? options.headRefOid.trim() : "";
  const reactions = Array.isArray(options?.reactions) ? options.reactions : [];
  const isCodexUser = (login) => CODEX_LOGIN_RE.test(String(login || ""));

  const findings = [];
  let quotaLimited = false;
  let notConfigured = false;
  let nonMarkerReviewPosted = false;
  let latestNonMarkerTs = -Infinity;

  let codexState = null;
  let codexStateTs = -Infinity;
  let codexStateIso = null;
  let codexStateBody = "";

  const recordCodexState = (parsedState, tsMs, tsIso, body) => {
    const effectiveTs = tsMs ?? 0;
    if (effectiveTs >= codexStateTs) {
      codexState = parsedState;
      codexStateTs = effectiveTs;
      codexStateIso = tsIso;
      codexStateBody = body;
    }
  };

  const resolveStateForHead = (body) => {
    const parsed = parseCodexReviewState(body);
    if (!parsed) return { hasMarker: false, state: null };
    const matched = expectedHeadRefOid ? combineParsedCodexState(parsed.raw, expectedHeadRefOid) : parsed;
    return { hasMarker: true, state: matched };
  };

  for (const r of reviews || []) {
    const login = r?.author?.login || r?.user?.login;
    if (!isCodexUser(login) || !isItemFresh(r, sinceMs)) continue;

    const reviewSha = r?.commit?.oid || r?.commit_id || r?.commitId || "";
    if (expectedHeadRefOid && reviewSha && !isCommitShaMatch(expectedHeadRefOid, reviewSha)) {
      continue;
    }

    const { ms: tsMs, iso: tsIso } = getLatestTimestamp(r);
    const body = String(r.body || "");
    const { hasMarker, state: matchedState } = resolveStateForHead(body);
    if (hasMarker && !matchedState) {
      continue;
    }

    if (QUOTA_RE.test(body)) quotaLimited = true;
    const isNotConfiguredMsg = !hasMarker && NOT_CONFIGURED_RE.test(body);
    if (isNotConfiguredMsg) notConfigured = true;

    if (matchedState) {
      recordCodexState(matchedState, tsMs, tsIso, body);
      if (body.trim()) {
        findings.push({
          source: "review",
          state: r.state,
          createdAt: tsIso,
          body: body.slice(0, 600),
          _skipBlocking: matchedState.status === "running",
        });
      }
    } else if (!isNotConfiguredMsg) {
      nonMarkerReviewPosted = true;
      latestNonMarkerTs = Math.max(latestNonMarkerTs, tsMs ?? 0);
      if (body.trim()) {
        findings.push({
          source: "review",
          state: r.state,
          createdAt: tsIso,
          body: body.slice(0, 600),
        });
      }
    } else if (body.trim()) {
      findings.push({
        source: "review",
        state: r.state,
        createdAt: tsIso,
        body: body.slice(0, 600),
        _skipBlocking: true,
      });
    }
  }

  for (const c of lineComments || []) {
    const login = c?.user?.login || c?.author?.login;
    if (!isCodexUser(login) || !isItemFresh(c, sinceMs)) continue;

    const commentSha = c?.commit_id || c?.original_commit_id || c?.commit?.oid || "";
    if (expectedHeadRefOid && commentSha && !isCommitShaMatch(expectedHeadRefOid, commentSha)) {
      continue;
    }

    const { ms: tsMs, iso: tsIso } = getLatestTimestamp(c);
    const body = String(c.body || "");
    const { hasMarker, state: matchedState } = resolveStateForHead(body);
    if (hasMarker && !matchedState) {
      continue;
    }

    if (QUOTA_RE.test(body)) quotaLimited = true;
    const isNotConfiguredMsg = !hasMarker && NOT_CONFIGURED_RE.test(body);
    if (isNotConfiguredMsg) notConfigured = true;

    if (matchedState) {
      recordCodexState(matchedState, tsMs, tsIso, body);
    } else if (!isNotConfiguredMsg) {
      nonMarkerReviewPosted = true;
      latestNonMarkerTs = Math.max(latestNonMarkerTs, tsMs ?? 0);
    }

    if (body.trim()) {
      findings.push({
        source: "inline",
        path: c.path,
        line: c.line || c.original_line || null,
        createdAt: tsIso,
        body: body.slice(0, 600),
        _skipBlocking: matchedState?.status === "running" || isNotConfiguredMsg,
      });
    }
  }

  for (const c of issueComments || []) {
    const login = c?.author?.login || c?.user?.login;
    if (!isCodexUser(login) || !isItemFresh(c, sinceMs)) continue;

    const { ms: tsMs, iso: tsIso } = getLatestTimestamp(c);
    const body = String(c.body || "");
    const { hasMarker, state: matchedState } = resolveStateForHead(body);
    if (hasMarker && !matchedState) {
      continue;
    }

    if (QUOTA_RE.test(body)) quotaLimited = true;
    const isNotConfiguredMsg = !hasMarker && NOT_CONFIGURED_RE.test(body);
    if (isNotConfiguredMsg) notConfigured = true;

    if (matchedState) {
      recordCodexState(matchedState, tsMs, tsIso, body);
      if (body.trim()) {
        findings.push({
          source: "comment",
          createdAt: tsIso,
          body: body.slice(0, 600),
          _skipBlocking: matchedState.status === "running",
        });
      }
    } else if (!isNotConfiguredMsg) {
      nonMarkerReviewPosted = true;
      latestNonMarkerTs = Math.max(latestNonMarkerTs, tsMs ?? 0);
      if (body.trim()) {
        findings.push({
          source: "comment",
          createdAt: tsIso,
          body: body.slice(0, 600),
        });
      }
    } else if (body.trim()) {
      findings.push({
        source: "comment",
        createdAt: tsIso,
        body: body.slice(0, 600),
        _skipBlocking: true,
      });
    }
  }

  const hasEyesReaction = reactions.some((r) => {
    const login = r?.user?.login || r?.author?.login;
    return isCodexUser(login) && String(r?.content || "").toLowerCase() === "eyes";
  });

  const FAILED_CODEX_STATES = new Set(["error", "failed", "cancelled", "canceled"]);
  let reviewPosted = false;
  let isRunning = false;

  if (codexState) {
    if (codexState.status === "completed") {
      reviewPosted = true;
      isRunning = false;
    } else if (codexState.status === "running") {
      reviewPosted = false;
      isRunning = true;
    } else if (FAILED_CODEX_STATES.has(codexState.status)) {
      reviewPosted = nonMarkerReviewPosted && latestNonMarkerTs > codexStateTs;
      isRunning = !reviewPosted && hasEyesReaction;
    } else {
      reviewPosted = nonMarkerReviewPosted && latestNonMarkerTs > codexStateTs;
      isRunning = !reviewPosted && hasEyesReaction;
    }
  } else {
    reviewPosted = nonMarkerReviewPosted;
    isRunning = !reviewPosted && hasEyesReaction;
  }

  const cleanItem = (item) => {
    const copy = { ...item };
    delete copy._skipBlocking;
    return copy;
  };

  const blocking = findings
    .filter((f) => !f._skipBlocking && BLOCKING_RE.test(f.body) && !QUOTA_RE.test(f.body))
    .map(cleanItem);

  if (
    codexState?.status === "completed" &&
    codexState.hasUnresolvedFindings === true &&
    blocking.length === 0 &&
    !quotaLimited
  ) {
    blocking.push({
      source: "codex-state",
      createdAt: codexStateIso,
      body: (codexStateBody || "Codex review reported unresolved findings (has_unresolved_findings=true).").slice(0, 600),
    });
  }

  const all = findings.map(cleanItem);

  const status = quotaLimited
    ? "quota_limited"
    : blocking.length > 0
      ? "blocking_findings"
      : isRunning
        ? "running"
        : !reviewPosted
          ? notConfigured
            ? "not_configured"
            : "pending"
          : "clean";

  return {
    status,
    reviewPosted,
    isRunning,
    hasEyesReaction,
    codexState,
    quotaLimited,
    notConfigured,
    totalCodexItems: all.length,
    blockingCount: blocking.length,
    blocking,
    all: all.slice(0, 20),
  };
}

export function evaluateGateVerdict({ ciPassed, codex, timedOut }) {
  if (!ciPassed) {
    return {
      readyToAutoMerge: false,
      exitCode: 2,
      nextAction: "heal_ci",
      escalateReason: null,
    };
  }
  if (codex?.quotaLimited) {
    return {
      readyToAutoMerge: false,
      exitCode: 3,
      nextAction: "escalate_human",
      escalateReason: "OpenAI ChatGPT Plus Codex review hit usage/quota limit. Auto-merge halted for human confirmation.",
    };
  }
  if (codex?.notConfigured && !codex?.reviewPosted && !codex?.isRunning) {
    return {
      readyToAutoMerge: false,
      exitCode: 3,
      nextAction: "escalate_human",
      escalateReason: "ChatGPT Codex Connector is not configured for this repository/environment. Auto-merge halted for human confirmation.",
    };
  }
  if (timedOut || !codex?.reviewPosted || codex?.status === "running" || codex?.isRunning) {
    const stillRunning = codex?.status === "running" || codex?.isRunning;
    return {
      readyToAutoMerge: false,
      exitCode: 3,
      nextAction: "escalate_human",
      escalateReason: stillRunning
        ? "Codex PR review is still running and did not complete within the configured timeout. Auto-merge halted for human confirmation."
        : "Codex PR review did not complete within the configured timeout. Auto-merge halted for human confirmation.",
    };
  }
  if (codex.blockingCount > 0) {
    return {
      readyToAutoMerge: false,
      exitCode: 2,
      nextAction: "fix_codex_findings",
      escalateReason: null,
    };
  }
  return {
    readyToAutoMerge: true,
    exitCode: 0,
    nextAction: "auto_merge",
    escalateReason: null,
  };
}

export function computeCodexDeadline({
  nowMs,
  baseDeadlineMs,
  startMs,
  codex,
  runningGraceSeconds = 300,
  maxTotalTimeoutSeconds = 900,
  graceGranted = false,
} = {}) {
  const maxAbsoluteDeadlineMs = startMs + maxTotalTimeoutSeconds * 1000;
  const isActivelyRunning = Boolean(codex?.isRunning || codex?.status === "running" || codex?.hasEyesReaction);
  if (isActivelyRunning && !graceGranted) {
    const deadlineMs = Math.min(maxAbsoluteDeadlineMs, Math.max(baseDeadlineMs, nowMs + runningGraceSeconds * 1000));
    return {
      deadlineMs,
      graceGranted: true,
      extended: deadlineMs > baseDeadlineMs,
    };
  }
  return {
    deadlineMs: Math.min(maxAbsoluteDeadlineMs, baseDeadlineMs),
    graceGranted: Boolean(graceGranted),
    extended: false,
  };
}

export async function runGhWithRetry(args, options = {}) {
  const {
    maxRetries = 4,
    baseDelayMs = 500,
    runner = runGh,
    sleepFn = sleep,
    onRetry,
  } = options || {};

  let attempt = 0;
  while (true) {
    try {
      return await runner(args);
    } catch (err) {
      if (attempt >= maxRetries || !isTransientGhError(err)) {
        throw err;
      }
      const delayMs = baseDelayMs * 2 ** attempt;
      attempt += 1;
      if (typeof onRetry === "function") {
        onRetry({ attempt, maxRetries, delayMs, error: err, args });
      }
      await sleepFn(delayMs);
    }
  }
}

export async function pollPr(prNumber, options = {}) {
  const cfg = loadShipConfig(process.cwd());
  const pollSeconds = options.pollSeconds ?? 15;
  const heartbeatSeconds = options.heartbeatSeconds ?? 60;
  const ciTimeoutSeconds = options.ciTimeoutSeconds ?? 1800;
  const codexTimeoutSeconds = options.codexTimeoutSeconds ?? Number(cfg?.codexReview?.timeoutSeconds ?? 600);
  const runningGraceSeconds = options.runningGraceSeconds ?? Number(cfg?.codexReview?.runningGraceSeconds ?? 300);
  const maxTotalTimeoutSeconds =
    options.maxTotalTimeoutSeconds ??
    Math.max(Number(cfg?.codexReview?.maxTotalTimeoutSeconds ?? 900), codexTimeoutSeconds);
  const autoMentionAfterSec = options.autoMentionAfterSec ?? Number(cfg?.codexReview?.autoMentionIfSilentSeconds ?? 90);
  const autoMentionComment = options.autoMentionComment || cfg?.codexReview?.autoMentionComment || "@codex review";
  const allowAutoMention = !options.noAutoMention;
  const repoArgs = options.repo ? ["-R", options.repo] : [];

  const sleepFn = options.sleepFn || sleep;
  const nowFn = options.nowFn || Date.now;
  const logFn =
    options.logFn ||
    ((line) => {
      process.stderr.write(line.endsWith("\n") ? line : `${line}\n`);
    });
  const ghRetryOpts = {
    maxRetries: options.ghMaxRetries ?? 4,
    baseDelayMs: options.ghBaseDelayMs ?? 500,
    runner: options.ghRunner || runGh,
    sleepFn,
  };
  const gh = (args) => runGhWithRetry(args, ghRetryOpts);

  const repoName = options.repo || JSON.parse(await gh(["repo", "view", "--json", "nameWithOwner"])).nameWithOwner;
  const startMs = nowFn();
  const ciDeadline = startMs + ciTimeoutSeconds * 1000;
  const maxAbsoluteDeadlineMs = startMs + maxTotalTimeoutSeconds * 1000;
  let codexDeadline = Math.min(maxAbsoluteDeadlineMs, startMs + codexTimeoutSeconds * 1000);
  let runningGraceGranted = false;
  let autoMentioned = false;
  let autoMentionHandled = false;
  let lastHeartbeatMs = startMs;

  // Determine the timestamp & HEAD SHA of the latest commit on the PR so we wait for fresh Codex review
  const initialPr = JSON.parse(
    await gh(["pr", "view", String(prNumber), ...repoArgs, "--json", "commits,headRefOid,url"]),
  );
  const lastCommit = (initialPr.commits || []).slice(-1)[0];
  let headRefOid = options.headRefOid || initialPr.headRefOid || lastCommit?.oid || null;
  let sinceIso = options.sinceIso || lastCommit?.committedDate || new Date(startMs - 10_000).toISOString();

  let checks = [];
  let ciDone = false;
  let codex = {
    status: "pending",
    reviewPosted: false,
    isRunning: false,
    hasEyesReaction: false,
    codexState: null,
    quotaLimited: false,
    notConfigured: false,
    totalCodexItems: 0,
    blockingCount: 0,
    blocking: [],
    all: [],
  };
  let codexTimedOut = false;

  while (nowFn() < ciDeadline) {
    const pr = JSON.parse(
      await gh(["pr", "view", String(prNumber), ...repoArgs, "--json", "statusCheckRollup,reviews,comments,headRefOid,url"]),
    );
    if (!options.headRefOid && pr.headRefOid) {
      if (headRefOid && pr.headRefOid !== headRefOid) {
        const changeNowMs = nowFn();
        if (!options.sinceIso) {
          sinceIso = new Date(changeNowMs).toISOString();
        }
        autoMentioned = false;
        autoMentionHandled = false;
        runningGraceGranted = false;
        codexDeadline = Math.min(maxAbsoluteDeadlineMs, changeNowMs + codexTimeoutSeconds * 1000);
      }
      headRefOid = pr.headRefOid;
    }

    checks = pr.statusCheckRollup || [];
    const pendingChecks = checks.filter((c) => {
      const s = String(c.status || c.state || "").toUpperCase();
      return s === "IN_PROGRESS" || s === "QUEUED" || s === "PENDING" || s === "WAITING";
    });
    ciDone = checks.length > 0 && pendingChecks.length === 0;

    let lineComments = [];
    try {
      lineComments = JSON.parse(await gh(["api", `repos/${repoName}/pulls/${prNumber}/comments`, "--paginate"]));
    } catch {
      lineComments = [];
    }

    let reactions = [];
    try {
      reactions = JSON.parse(await gh(["api", `repos/${repoName}/issues/${prNumber}/reactions`]));
    } catch {
      reactions = [];
    }

    codex = extractCodexFindings(pr.reviews, lineComments, pr.comments, {
      sinceIso,
      headRefOid,
      reactions,
    });

    const nowMs = nowFn();
    const elapsedSec = (nowMs - startMs) / 1000;

    // Deduplicate `@codex review` across invocations via fresh PR comments
    const freshMentionPresent = hasFreshCodexMention(pr.comments, { sinceIso, autoMentionComment });
    if (freshMentionPresent) {
      autoMentionHandled = true;
    }

    if (
      allowAutoMention &&
      !autoMentionHandled &&
      !codex.reviewPosted &&
      !codex.isRunning &&
      !codex.hasEyesReaction &&
      !codex.notConfigured &&
      elapsedSec >= autoMentionAfterSec
    ) {
      try {
        await gh(["pr", "comment", String(prNumber), ...repoArgs, "--body", autoMentionComment]);
        autoMentioned = true;
        autoMentionHandled = true;
        codexDeadline = Math.min(
          maxAbsoluteDeadlineMs,
          Math.max(codexDeadline, nowMs + codexTimeoutSeconds * 1000),
        );
      } catch {
        autoMentionHandled = true;
      }
    }

    const codexFinished =
      (codex.reviewPosted && !codex.isRunning) ||
      codex.quotaLimited ||
      (codex.notConfigured && !codex.isRunning && !codex.reviewPosted);
    if (!codexFinished && nowMs >= codexDeadline && !runningGraceGranted) {
      const grace = computeCodexDeadline({
        nowMs,
        baseDeadlineMs: codexDeadline,
        startMs,
        codex,
        runningGraceSeconds,
        maxTotalTimeoutSeconds,
        graceGranted: runningGraceGranted,
      });
      if (grace.extended) {
        codexDeadline = grace.deadlineMs;
        runningGraceGranted = grace.graceGranted;
      }
    }
    codexTimedOut = !codexFinished && nowMs >= codexDeadline;

    if (heartbeatSeconds >= 0 && nowMs - lastHeartbeatMs >= heartbeatSeconds * 1000) {
      const ciSummary =
        checks.length === 0
          ? "none"
          : ciDone
            ? `done(${checks.length}/${checks.length})`
            : `pending(${checks.length - pendingChecks.length}/${checks.length})`;
      logFn(
        `[wait-pr-gates] pr=#${prNumber} elapsed=${Math.floor(elapsedSec)}s ci=${ciSummary} codex=${codex.status} grace=${runningGraceGranted}`,
      );
      lastHeartbeatMs = nowMs;
    }

    // Exit loop once CI has finished AND (Codex finished OR Codex timed out)
    if (ciDone && (codexFinished || codexTimedOut)) {
      break;
    }
    // If CI failed fast, don't block 10 minutes on Codex before starting CI repair
    const anyCiFailed =
      ciDone &&
      checks.some((c) =>
        ["FAILURE", "TIMED_OUT", "ACTION_REQUIRED", "ERROR"].includes(
          String(c.conclusion || c.state || "").toUpperCase(),
        ),
      );
    if (anyCiFailed) {
      break;
    }

    await sleepFn(pollSeconds * 1000);
  }

  const finalCodexFinished = (codex.reviewPosted && !codex.isRunning) || codex.quotaLimited;
  if (!finalCodexFinished && nowFn() >= codexDeadline) {
    codexTimedOut = true;
  }

  const failedChecks = checks.filter((c) => {
    const conc = String(c.conclusion || c.state || "").toUpperCase();
    return ["FAILURE", "TIMED_OUT", "ACTION_REQUIRED", "ERROR"].includes(conc);
  });
  const ciPassed = ciDone && failedChecks.length === 0;

  const failureReports = [];
  for (const fc of failedChecks.slice(0, 3)) {
    const url = fc.detailsUrl || "";
    const runMatch = url.match(/\/actions\/runs\/(\d+)/);
    if (runMatch) {
      try {
        const failedLog = await gh(["run", "view", runMatch[1], ...repoArgs, "--log-failed"]);
        failureReports.push({
          checkName: fc.name || fc.context,
          runId: runMatch[1],
          triage: compressCiLog(failedLog),
        });
      } catch (err) {
        failureReports.push({
          checkName: fc.name || fc.context,
          runId: runMatch[1],
          error: `Could not fetch failed logs: ${err.message}`,
        });
      }
    } else {
      failureReports.push({ checkName: fc.name || fc.context, conclusion: fc.conclusion || fc.state });
    }
  }

  const verdict = evaluateGateVerdict({ ciPassed, codex, timedOut: codexTimedOut });
  const effectiveTimeoutSeconds = Math.round((codexDeadline - startMs) / 1000);

  return {
    prNumber: Number(prNumber),
    url: initialPr.url,
    headRefOid,
    ciPassed,
    totalChecks: checks.length,
    failedChecks: failedChecks.map((c) => ({ name: c.name || c.context, conclusion: c.conclusion || c.state })),
    failureReports,
    codex: {
      ...codex,
      autoMentioned,
      timedOut: codexTimedOut,
      runningGraceGranted,
      effectiveTimeoutSeconds,
    },
    ...verdict,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const prNumber = args.find((a) => /^\d+$/.test(a));
  if (!prNumber) {
    console.error(
      "Usage: wait-pr-gates.mjs <pr-number> [--repo owner/repo] [--codex-timeout 600] [--running-grace 300] [--max-timeout 900] [--ci-timeout 1800] [--poll-seconds 15] [--heartbeat-seconds 60] [--no-auto-mention]",
    );
    process.exit(1);
  }
  const flag = (k) => {
    const idx = args.indexOf(k);
    return idx >= 0 ? args[idx + 1] : undefined;
  };
  const numFlag = (k) => {
    const val = flag(k);
    return val !== undefined ? Number(val) : undefined;
  };
  const report = await pollPr(prNumber, {
    repo: flag("--repo"),
    codexTimeoutSeconds: numFlag("--codex-timeout"),
    runningGraceSeconds: numFlag("--running-grace"),
    maxTotalTimeoutSeconds: numFlag("--max-timeout"),
    ciTimeoutSeconds: numFlag("--ci-timeout"),
    pollSeconds: numFlag("--poll-seconds"),
    heartbeatSeconds: numFlag("--heartbeat-seconds"),
    noAutoMention: args.includes("--no-auto-mention"),
  });
  console.log(JSON.stringify(report, null, 2));
  process.exit(report.exitCode);
}
