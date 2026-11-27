#!/usr/bin/env node
/**
 * Antigravity PreToolUse hook for run_command: blocks destructive git operations,
 * hook bypass flags (--no-verify), root filesystem deletion, and secret leaks.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const BLOCKED_COMMAND_RULES = [
  {
    id: "no-verify-bypass",
    re: /\bgit\s+(?:commit|push)\b[^\n]*--no-verify\b/,
    reason: "Bypassing git pre-commit guards with --no-verify is forbidden.",
  },
  {
    id: "no-force-push",
    re: /\bgit\s+push\b[^\n]*(?:--force(?:-with-lease)?|\s-f\b)/,
    reason: "Force pushing is forbidden; push normal commits or escalate to the human.",
  },
  {
    id: "no-hard-reset-or-clean",
    re: /\bgit\s+(?:reset\s+--hard|clean\s+-[a-zA-Z]*f)/,
    reason: "Destructive git reset/clean commands are blocked to preserve workspace state.",
  },
  {
    id: "no-root-rm",
    re: /\brm\s+-[a-zA-Z]*r[a-zA-Z]*f\s+\/(?:\s|$)/,
    reason: "Destructive root filesystem deletion is blocked.",
  },
  {
    id: "no-secret-echo",
    re: /\b(?:gh\s+auth\s+token|printenv\s+(?:GH_TOKEN|GITHUB_TOKEN|OPENAI_API_KEY|GEMINI_API_KEY))\b/,
    reason: "Printing authentication tokens or signing secrets is forbidden.",
  },
];

export function evaluateCommand(commandLine) {
  const cmd = String(commandLine || "");
  for (const rule of BLOCKED_COMMAND_RULES) {
    if (rule.re.test(cmd)) {
      return {
        decision: "deny",
        reason: `[${rule.id}] ${rule.reason}`,
      };
    }
  }
  return { decision: "allow" };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const raw = fs.readFileSync(0, "utf8").trim();
  const payload = raw ? JSON.parse(raw) : {};
  const cmd = payload?.toolCall?.args?.CommandLine ?? "";
  console.log(JSON.stringify(evaluateCommand(cmd)));
}
