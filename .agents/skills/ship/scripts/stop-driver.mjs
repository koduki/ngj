#!/usr/bin/env node
/**
 * Antigravity Stop hook: drives continuous end-to-end execution of /ship without
 * premature stopping during AUTO_PROCEED states, while respecting human gates and
 * enforcing a strict stall breaker (max 3 consecutive stops in the same state).
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadState, resolveStatePath, saveState } from "./state.mjs";

export const MAX_STALLS_PER_STATE = 3;

export function evaluateStop(hookInput, statePath) {
  const state = loadState(statePath);
  if (!state) {
    return { decision: "allow" };
  }

  if (
    !hookInput?.fullyIdle ||
    state.gate === "WAITING_HUMAN" ||
    state.currentState === "DONE" ||
    state.currentState === "ESCALATED"
  ) {
    return { decision: "allow" };
  }

  state.stallsInState = (state.stallsInState || 0) + 1;
  if (state.stallsInState > MAX_STALLS_PER_STATE) {
    state.currentState = "ESCALATED";
    state.gate = "WAITING_HUMAN";
    state.escalationReason = `Orchestrator stalled ${MAX_STALLS_PER_STATE} times without advancing from state.`;
    saveState(statePath, state);
    return { decision: "allow" };
  }

  saveState(statePath, state);
  return {
    decision: "continue",
    reason: `[ship-orchestrator] Active state is ${state.currentState} (gate=AUTO_PROCEED, stall ${state.stallsInState}/${MAX_STALLS_PER_STATE}). Continue automatically per .agents/skills/ship/SKILL.md and advance via state.mjs instead of stopping.`,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const raw = fs.readFileSync(0, "utf8").trim();
  const hookInput = raw ? JSON.parse(raw) : { fullyIdle: true };
  const cwd = hookInput?.workspacePaths?.[0] || process.cwd();
  const statePath = resolveStatePath(cwd);
  console.log(JSON.stringify(evaluateStop(hookInput, statePath)));
}
