#!/usr/bin/env node
/**
 * Runs OpenAI Codex CLI non-interactive cross-model review (`codex review --base <branch>`)
 * using saved ChatGPT Plus credentials (zero extra API billing, read-only on working tree),
 * persists raw output under `.ship/last-codex-review.txt`, and returns bounded JSON to /ship.
 */
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export function parseCodexReviewOutput(rawText) {
  const lines = String(rawText || "").split(/\r?\n/);
  const findings = [];
  let p0Count = 0;
  let p1Count = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;
    const isP0 = /\bP0\b|\bcritical\b/i.test(line);
    const isP1 = /\bP1\b|\bhigh[- ]severity\b/i.test(line);
    if (isP0) p0Count += 1;
    if (isP1) p1Count += 1;
    if (isP0 || isP1) {
      const next = lines[i + 1]?.trim();
      findings.push(next && !/^[-*#\s]*$/.test(next) ? `${line} | ${next}` : line);
    }
    if (findings.length >= 25) break;
  }

  let verdict = "unknown";
  if (/\bpatch is incorrect\b|\bchanges requested\b|\bblocking\b/i.test(rawText) || p0Count > 0 || p1Count > 0) {
    verdict = "issues_found";
  } else if (/\bpatch is correct\b|\bno (?:major |blocking )?issues\b|\blooks good\b/i.test(rawText)) {
    verdict = "clean";
  }

  return {
    verdict,
    p0Count,
    p1Count,
    highPriorityFindings: findings,
    summaryTail: lines.filter((l) => l.trim()).slice(-12),
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const baseIdx = args.indexOf("--base");
  const baseBranch = baseIdx >= 0 && args[baseIdx + 1] ? args[baseIdx + 1] : "main";
  const uncommitted = args.includes("--uncommitted");
  const codexArgs = uncommitted ? ["review", "--uncommitted"] : ["review", "--base", baseBranch];

  const res = spawnSync("codex", codexArgs, {
    encoding: "utf8",
    timeout: 240_000,
  });

  if (res.error) {
    console.log(JSON.stringify({
      status: "unavailable",
      fallbackToGitHubApp: true,
      error: `Codex CLI unavailable (${res.error.message}). Proceed to S5 where ChatGPT Codex GitHub App will review the PR.`,
    }, null, 2));
    process.exit(0);
  }

  const raw = `${res.stdout || ""}\n${res.stderr || ""}`.trim();
  fs.mkdirSync(".ship", { recursive: true });
  const outPath = ".ship/last-codex-review.txt";
  fs.writeFileSync(outPath, raw + "\n", "utf8");

  const parsed = parseCodexReviewOutput(raw);
  const rateLimited = /usage limit|rate limit|quota/i.test(raw) && res.status !== 0;

  console.log(JSON.stringify({
    status: rateLimited ? "unavailable" : res.status === 0 ? "completed" : "warning",
    fallbackToGitHubApp: rateLimited,
    exitCode: res.status,
    command: `codex ${codexArgs.join(" ")}`,
    fullOutputSavedTo: outPath,
    ...parsed,
  }, null, 2));
}
