#!/usr/bin/env node
/**
 * Antigravity SessionStart hook:
 * - Automatically enables repository-managed `.githooks` on fresh Remote Agent VMs
 *   so pre-commit guards work with zero manual setup.
 * - Surfaces a compact resume hint if an unfinished `/ship` checkpoint exists.
 */
import fs from "node:fs";
import path from "node:path";
import { execSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { loadState, resolveStatePath } from "./state.mjs";

export function runSessionInit(cwd = process.cwd()) {
  const notices = [];
  const githooksDir = path.join(cwd, ".githooks");
  if (fs.existsSync(githooksDir) && fs.existsSync(path.join(cwd, ".git"))) {
    try {
      execSync("git config core.hooksPath .githooks", { cwd, stdio: "ignore" });
      const preCommit = path.join(githooksDir, "pre-commit");
      if (fs.existsSync(preCommit)) fs.chmodSync(preCommit, 0o755);
    } catch {
      // Non-fatal in read-only or restricted sandboxes.
    }
  }

  const state = loadState(resolveStatePath(cwd));
  if (state && state.currentState !== "DONE") {
    notices.push(
      `Active /ship run detected on branch checkpoint: state=${state.currentState}, gate=${state.gate}, contractLocked=${state.contractLocked}, pr=${state.prNumber ?? "none"}.`,
    );
  }

  if (notices.length === 0) {
    return { injectSteps: [] };
  }
  return {
    injectSteps: [
      {
        systemMessage: {
          systemMessage: notices.join(" "),
          metadata: { source: "ship-session-init" },
        },
      },
    ],
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const raw = fs.readFileSync(0, "utf8").trim();
  const payload = raw ? JSON.parse(raw) : {};
  const cwd = payload?.workspacePaths?.[0] || process.cwd();
  console.log(JSON.stringify(runSessionInit(cwd)));
}
