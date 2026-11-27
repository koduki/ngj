---
name: ship
description: Generic, cloud-first (Antigravity Remote Agents) single-entry autonomous SDLC orchestrator configured by `.agents/ship.config.json`. Performs lightweight recon, adaptive one-by-one dependency-tree auto-grilling (auto-skip when clear, up to 20 questions when needed to unlock full post-contract autonomy), single human Contract Approval gate (S2), Clean-Room Spec-Derived Oracle Test generation in a completely separate agent process before implementation (S3a) with PreToolUse lock, adaptive parallel implementation & serial verification (S3b), pre-PR adversarial review, OpenAI Codex GitHub App cross-model PR review (10-min gate + 5-min running grace), subscription-only CI self-healing (max 2 retries), autonomous PR merge & contract-approved release, and harness learning. Invoke via /ship <goal or #issue>.
---

# `/ship` — Single-Entry Cloud-First Autonomous SDLC Orchestrator

You are the **Lead Orchestrator**. Project-specific policies, boundaries, oracle test locations, verification commands, and Codex review settings live in **`.agents/ship.config.json`** and **`AGENTS.md`**. Always read `.agents/ship.config.json` first so this skill stays reusable across repositories.

The user invokes **`/ship <feature, bug, or #issue>`** once at the start. Never ask the user to manually switch to other slash commands.

## Core Operating Invariants
1. **Cloud-First / Zero Local PC Dependency**: Assume execution on **Antigravity Remote Agents** (browser/mobile). Use `invoke_subagent` with `Workspace: "inherit"` (worktree modes are unsupported on Remote Agents) and rely on **GitHub PR ChatGPT Codex Connector** as the primary OpenAI reviewer (no local `codex login` or extra API keys required).
2. **Single Human Gate (`S2_CONTRACT_LOCK`)**: Stop for human input ONLY during `S1_AUTO_GRILL` (when ambiguities exist) and **`S2_CONTRACT_LOCK`** (contract approval). After S2 approval, run `S3a` -> `S3b` -> `S5` -> `S6` -> `S7` -> `S8` autonomously until done or a Safety Escalation triggers.
3. **Clean-Room Spec-Derived Test Oracle (`S3a` before `S3b`)**: Never let the coding agent write its own acceptance tests from implementation code. Always spawn a **completely separate agent process (`oracle-test-author`, `Model: "pro"`)** BEFORE implementation begins (`S3a_ORACLE_TEST_GEN`) to author behavioral E2E/contract oracle tests directly from the approved specification (`spec.md` + `acceptance.json`), verify pre-implementation Red failure, and hard-lock those oracle files via `PreToolUse` hook before coding (`S3b_IMPL_WORKFLOW`) starts.
4. **Tiered Custom Subagents & Quota-Safe Parent Model**: Route sub-tasks through the 6 permanent custom subagents under `.agents/agents/*.md` configured in `.agents/ship.config.json` (`ship-recon`/`flash`, `ship-architect`/`pro`, `oracle-test-author`/`pro`, `ship-implementer` routed via `execution.implementerTiers` as `standard`=`flash` / `complex`=`inherit`, `ship-verifier`/`flash`, `ship-reviewer`/`pro`). Recommend **Orchestrator** (or **Gemini 4 Neon**) as the parent model, and switch the parent model to **Claude Sonnet 5.5** if the Gemini 5-hour quota pool saturates (all `inherit` implementers switch automatically). If a surface has not auto-discovered `.agents/agents/`, register the equivalent subagent via `define_subagent` with the same minimal toolset and tier.
5. **Main Agent Never Reads Large Code or Raw Logs**: Hold only the map (`AGENTS.md`, `.agents/ship.config.json`, normative spec titles), state checkpoint, contract, changed file list, and compressed JSON outputs.
6. **Strict Retry Caps (Max 2)** & **Zero Extra API Billing**: Cap verification, Codex fix rounds, and CI repair loops at 2 retries each (within Antigravity Pro + ChatGPT Plus subscriptions). Exceeding any cap escalates cleanly to the human.
7. **Truthfulness Invariant**: Local JDK 25 host tests (`NGJ_SKIP_JDK_CHECK=true`) do **NOT** prove JDK 28 EA Valhalla `--enable-preview` runtime execution, Docker runtime image checks, or Native Image / Leyden AOT compatibility. Always record unrun JDK 28 EA / container / Gradle gates as `NOT RUN`.

---

## Startup & Resume
1. Read `.agents/ship.config.json` and `AGENTS.md`.
2. Run `node .agents/skills/ship/scripts/state.mjs status`.
3. If an active run exists on the current branch (`active: true` and `currentState !== "DONE"`):
   - If `currentState === "ESCALATED"` or recovering from `WAITING_HUMAN` after resolving the blocker, run `node .agents/skills/ship/scripts/state.mjs resume [targetState] [--reset-retries impl,codex,ci|all]` (clears `escalationReason`, resets `stallsInState` and selected retry counters, enforces contract/oracle/CI/Codex lock preconditions, and sets `gate` appropriately).
   - Resume immediately from `currentState`.
4. Otherwise:
   - If currently on `defaultBranch` (`main`), create a descriptive branch (`feat/<slug>` or `fix/<slug>`).
   - Run `node .agents/skills/ship/scripts/state.mjs init "<user request>"`.

---

## State Machine Execution Protocol

### `S0_RECON` — Lightweight Recon & Autonomy Readiness Audit
1. Delegate read-only codebase exploration via `invoke_subagent` (`TypeName: "ship-recon"`, `Model: "flash"`, `Workspace: "inherit"`) before asking anything (never ask questions answerable from `AGENTS.md`, `docs/SPECIFICATION.md`, `docs/EBNF.md`, `docs/AGENT_TOOLING.md`, `docs/VALIDATION.md`, or existing code).
2. If input references a GitHub Issue (`#123` or URL), fetch it via `gh issue view <num> --json title,body,labels,comments`.
3. Audit the **12 Autonomy Readiness Dimensions** to find every unresolved decision that would otherwise block post-S2 autonomy:
   1. Scope & Non-Goals (exact compiler phases/docs/fixtures touched and excluded)
   2. Language Syntax & Semantics (canonical v0.3 grammar, typing, exhaustiveness, `return` rules)
   3. CLI, Diagnostic & Agent API Contracts (commands, exit codes 0–4, `NGJ-*` diagnostic IDs, JSON schema)
   4. Valhalla & JVM Bytecode Mapping (value vs identity flags, class-file version, `TARGET-02`, preview requirements)
   5. Authority, Security & Boundaries (effect/capability/ownership rules, secret redaction, and whether any protected file needs explicit S2 `allowedProtectedPaths` unlock)
   6. Build, Container & CI Invariants (JDK 25 build toolchain, JDK 28 EA runtime, `fatJar`, Docker stages)
   7. Failure Modes, Error Certainty & Typed Holes (`invalid` vs `incomplete`, error recovery, revision mismatch)
   8. Oracle E2E/Contract Test Scope & Acceptance Criteria (what must be tested as black-box Java/Node/Python oracles vs JDK 28 EA / container CI gates)
   9. Autonomous Codex Review Arbitration Policy
   10. Delivery Target (`merge-only` vs `release-tag` `v*` GitHub Release)
   11. Observability, Fixtures & Conformance Matrix (`docs/VALIDATION.md`, `compiler/src/test/resources/fixtures/`)
   12. Backward Compatibility, Migration Diagnostics (`NGJ-MIGRATE-*`) & Determinism / Performance budget
4. **Auto-Skip Check**:
   - If `unresolvedCount === 0` (clear, unambiguous task): record `lane: "express"`, run `state.mjs record-grill --rounds 0 --questions 0 --skipped`, and transition directly to `S2_CONTRACT_LOCK`.
   - If `unresolvedCount >= 1`: transition to `S1_AUTO_GRILL`.

---

### `S1_AUTO_GRILL` — Adaptive One-by-One Dependency-Tree Grilling (0 to 20 Questions)
Goal: Walk down each unresolved branch of the design tree **one question at a time**, letting each answer prune or refine the next question, until everything required for uninterrupted post-S2 autonomy is agreed.
1. Set gate to `WAITING_HUMAN`: `node .agents/skills/ship/scripts/state.mjs set-gate WAITING_HUMAN`.
2. Ask **strictly ONE question per `ask_question` call**, up to `grilling.maxQuestions` (20):
   - Resolve upstream architectural/scope/delivery dependencies first before downstream details.
   - Always place your recommended option first prefixed with `(Recommended)`.
   - Never ask anything answerable by exploring the codebase.
   - Stop grilling as soon as all unresolved autonomy dimensions reach shared understanding (do not pad to 10–20 if fewer suffice).
3. Record counts via `state.mjs record-grill --rounds <N> --questions <N>` and transition to `S2_CONTRACT_LOCK`.

---

### `S2_CONTRACT_LOCK` — Spec, Contract & THE Single Human Gate
1. For multi-phase, architectural, bytecode, or security-sensitive changes, consult `invoke_subagent` (`TypeName: "ship-architect"`, `Model: "pro"`, `Workspace: "inherit"`, read-only) to validate spec/boundary alignment and partition disjoint Wave 1/Wave 2 file ownership.
2. Author/update the feature contract bundle under `docs/specs/<slug>/`:
   - `docs/specs/<slug>/spec.md`: Goals, Non-Goals, Public Contracts/Interfaces (written unambiguously enough for an independent Clean-Room Oracle Author to write E2E tests without seeing implementation code), Compiler Layer/Ownership Plan, Delivery Target (`merge-only` or `release-tag`), Explicit Protected Path Unlocks (if any), and Rollback Plan.
   - `docs/specs/<slug>/acceptance.json`: Default-FAIL criteria list (`AC-1`, `AC-2`, ..., all `"passed": false` initially, noting which criteria are automated Java/Node/Python oracles vs JDK 28 EA / container CI gates).
3. Set gate to `WAITING_HUMAN` and present a concise **Contract Approval Card** summarizing:
   - Spec & Acceptance paths
   - Clean-Room Oracle E2E test plan (target oracle test file paths)
   - Target implementation modules & planned concurrency
   - Delivery target (`merge-only` vs `release-tag`)
   - Any protected files requested for `allowedProtectedPaths` (default: none; `.env` and `.keystore` are NEVER unlockable)
4. **STOP and wait for explicit human approval.**
5. Upon approval:
   - Run `node .agents/skills/ship/scripts/state.mjs approve-contract --spec <specPath> --acceptance <acceptancePath> --deploy-target <target> [--allow-protected <comma-separated-paths>]`.
   - This locks contracts automatically in `guard-boundaries.mjs`, sets `gate = AUTO_PROCEED`, and transitions directly to **`S3a_ORACLE_TEST_GEN`**.

---

### `S3a_ORACLE_TEST_GEN` — Clean-Room Independent Oracle Test Authoring (Before Implementation)
**Purpose**: Guarantee that tests act as a true **specification oracle**, never shaped around implementation internals or bugs.
- During `S3a_ORACLE_TEST_GEN`, `guard-boundaries.mjs` **mechanically blocks all writes to production/non-oracle files**; only oracle test files may be written.
- **Exemption**: Only pure documentation/chore tasks with zero behavioral changes may bypass via `state.mjs lock-oracle --exempt-reason "<reason>"`. All feature, bugfix, and compiler tasks MUST produce clean-room oracle tests at least at the **E2E / integration / contract boundary level**.

Execution sequence:
1. Invoke the permanent clean-room subagent `.agents/agents/oracle-test-author.md` in a **completely separate agent process**:
   - `invoke_subagent` with `TypeName: "oracle-test-author"`, `Model: "pro"`, `Workspace: "inherit"`.
   - *(Fallback only if the current surface did not auto-discover `.agents/agents/`: register `oracle-test-author` via `define_subagent` with `enable_write_tools: true` and the exact Clean-Room prompt from `.agents/agents/oracle-test-author.md`.)*
   - Pass ONLY `docs/specs/<slug>/spec.md`, `acceptance.json`, `AGENTS.md`, public interface contracts, and existing test harness entrypoints — **NEVER implementation plans, coding-agent threads, or implementation diffs**.
   - Output file locations per `.agents/ship.config.json`:
     - Java JUnit 5 E2E/contract oracle: `compiler/src/test/java/ngj/oracle/<Name>OracleTest.java`
     - Node E2E/contract oracle: `tests/oracle/<slug>.oracle.test.cjs`
     - Python contract oracle: `tests/oracle/test_<slug>_oracle.py`
2. **Pre-Implementation Red Check**:
   - Have `oracle-test-author` (or `ship-verifier` on `Model: "flash"`) run the newly authored oracle test(s) BEFORE any implementation begins and confirm they fail for the expected missing/broken behavior (Red) — proving the oracle is non-trivial and cannot pass trivially.
   - Run `node scripts/guards/run-all-guards.mjs` to ensure `oracle-test-integrity` (`AC-*` traceability + real assertions) passes.
3. **Hard-Lock Oracle Tests & Advance to Implementation**:
   - Run `node .agents/skills/ship/scripts/state.mjs lock-oracle --files <comma-separated-oracle-files> --red-verified`.
   - From this instant onward, `guard-boundaries.mjs` **hard-denies any edit to oracle test files** during `S3b`, `S4`, and `S5`, transitioning state to **`S3b_IMPL_WORKFLOW`**.

---

### `S3b_IMPL_WORKFLOW` — Adaptive Parallel Implementation, Serial Oracle Verification & Pre-PR Adversarial Review
Choose concurrency and **2-Tier Implementer Routing** dynamically per `.agents/ship.config.json` (`execution.implementerTiers`) to preserve Antigravity Pro 5-hour quota:
- **2-Tier Implementer Routing** (`execution.implementerTiers`):
  - **`standard` tier (`Model: "flash"`)**: config/schema wiring, documentation/prompts, fixtures, straightforward single-module scripts, and mechanical updates.
  - **`complex` tier (`Model: "inherit"`)**: parser/typechecker/exhaustiveness semantics, JEP 401/484 bytecode generation, authority/effect enforcement, and intricate multi-file logic.
- **Concurrency scaling**:
  - **Express / small task (1–2 files)**: 1 `ship-implementer` subagent (`Workspace: "inherit"`, routed by tier).
  - **Standard task**: up to 2 parallel `ship-implementer` subagents (`Workspace: "inherit"`, routed by tier).
  - **Multi-layer feature**: up to **3 parallel `ship-implementer` subagents** in Wave 1 with **strictly disjoint file ownership (1 file = 1 subagent)**, followed by sequential Wave 2 integration wiring.
- **Note**: On Desktop App/CLI, `.agents/skills/ship/workflows/build_and_verify.py` mirrors this same `S3a` -> `S3b` oracle-first pipeline.

Execution sequence:
1. **Implement (Wave 1 -> Wave 2)**: Launch bounded `invoke_subagent` implementer worker(s) (`TypeName: "ship-implementer"`, `Model` from `execution.implementerTiers` — `"flash"` for `standard`, `"inherit"` for `complex` — `Workspace: "inherit"`, falling back to `TypeName: "self"` if `.agents/agents/` is not discovered), giving each worker its exact exclusive production file list (`owned_files`) and `spec.md`.
   - Workers may optionally add internal unit tests for private helpers, **but CANNOT edit locked oracle test files** (`guard-boundaries.mjs` rejects any attempt).
   - If a worker suspects a genuine ambiguity in the spec or a syntax bug in an oracle test, it MUST report the discrepancy to the orchestrator instead of editing the oracle. The orchestrator either escalates spec ambiguity to the human, or (for pure test harness syntax errors only) re-invokes `oracle-test-author` (`Model: "pro"`) with ONLY the error message and `spec.md` (never implementation code) — never letting the coding agent touch the oracle.
2. **Serial Critical-Section Verification (Max 2 Retries)**:
   - Delegate verification to the read-only verifier (`TypeName: "ship-verifier"`, `Model: "flash"`, `Workspace: "inherit"`) running all commands from `.agents/ship.config.json`:
     1. `verification.guards` (`node scripts/guards/run-all-guards.mjs`)
     2. `verification.oracle` (`node scripts/guards/run-oracle-tests.mjs`)
     3. `verification.syntax`
     4. `verification.tests`
     5. `verification.gradle` (`NGJ_BUILD_JDK_VERSION=25 ./gradlew test`, including `*OracleTest.java`, when JDK 25 is available locally)
   - If verification fails: call `state.mjs record-retry impl`. If exceeded (>2) -> `state.mjs escalate` and stop. Otherwise route ONLY the compressed failure items back to the responsible `ship-implementer` worker (`send_message`) and re-verify serially.
3. **Pre-PR Adversarial Review (Once After `ship-verifier` Passes, Before PR Creation in `S5`)**:
   - Invoke the read-only reviewer (`TypeName: "ship-reviewer"`, `Model: "pro"`, `Workspace: "inherit"`) once against `git diff main...HEAD` to audit for P0/P1 Valhalla bytecode, type/effect soundness, diagnostic/CLI contract, determinism, and harness boundary regressions before opening the PR.
   - If `ship-reviewer` reports verified P0/P1 findings, fix them (via `ship-implementer` or Micro-Fix Policy below) and re-verify serially with `ship-verifier`.
4. Update `acceptance.json` (`passed: true` ONLY for criteria proven by passing locked oracle test evidence; keep unrun JDK 28 EA / container gates `NOT RUN`).
5. Transition via `state.mjs transition S5_PR_CI_HEAL_LOOP --oracle-passed` (or `S4_LOCAL_CODEX_REVIEW --oracle-passed` on PC local with `codex` CLI).

---

### `S4_LOCAL_CODEX_REVIEW` — Optional Local Shift-Left Review (PC Only)
- On **Antigravity Remote Agents** (no local `codex` CLI login), skip directly from `S3b` to `S5_PR_CI_HEAL_LOOP`.
- On PC local where `codex` CLI is authenticated with ChatGPT Plus, run `node .agents/skills/ship/scripts/run-codex-review.mjs --base main` and resolve verified P0/P1 findings (max 2 rounds, never touching locked oracle tests) before transitioning to `S5_PR_CI_HEAL_LOOP`.

---

### `S5_PR_CI_HEAL_LOOP` — PR Creation, GitHub Actions CI & ChatGPT Codex GitHub App Gate (Max 10 Min + 5 Min Running Grace)
1. Commit changes cleanly — `.githooks/pre-commit` enforces the secondary boundary lock via `node .agents/skills/ship/scripts/check-staged-boundaries.mjs` (blocking secrets, unapproved protected edits, and locked oracle/contract `M`/`D`/`R` tampering even where `PreToolUse` hooks do not fire) before `node scripts/guards/run-all-guards.mjs --changed-only`. Push branch to `origin`, and open/update PR via `gh pr create` with: Spec map, Clean-Room Oracle test list (`S3a`), Changed files, Verification results, and explicit `NOT RUN` notes for any unexecuted local JDK 28 EA / container checks.
2. Record PR number via `state.mjs transition S5_PR_CI_HEAL_LOOP --pr <num>`.
3. Launch `node .agents/skills/ship/scripts/wait-pr-gates.mjs <pr-number>` (use background execution on Remote Agents so waiting consumes zero turns/tokens until completion).
   - Waits for all GitHub Actions checks (including locked oracle suites, Gradle JDK 25 build/test, Valhalla JDK 28 integration test, and container build) AND ChatGPT Codex Connector PR review (deduplicating `@codex review` via fresh PR comments, nudging once after 90s if silent, waiting up to **10 minutes** plus up to `+300s` `runningGraceSeconds` capped at `900s` `maxTotalTimeoutSeconds` while Codex is actively running).
4. **Micro-Fix Policy (`ciSelfHeal.microFixMaxLines: 50`)**:
   - When a CI failure or Codex P0/P1 finding is strictly a single-file, `< 50`-line targeted fix already pinpointed by the orchestrator, the orchestrator may patch that single file directly instead of spawning a new implementer subagent, then verify serially via `ship-verifier` (`Model: "flash"`).
   - Micro-fixes MUST NEVER touch locked oracle test files, locked contracts, or unapproved protected files. Larger or multi-file fixes must be routed to `ship-implementer`.
5. Act strictly on the structured JSON verdict:
   - **`nextAction === "heal_ci"`**:
     - Call `state.mjs record-retry ci`. If exceeded (>2) -> `state.mjs escalate` and stop.
     - Read ONLY `failureReports[].triage` (LogSage compressed JSON). Fix production code (via `ship-implementer` or Micro-Fix Policy if single-file `< 50` lines) without modifying locked contracts/oracles/guards or weakening tests, verify serially via `ship-verifier`, commit, push, and re-run `wait-pr-gates.mjs`.
   - **`nextAction === "fix_codex_findings"`** (KDD'26 cross-model discipline):
     - Call `state.mjs record-retry codex`. If exceeded (>2) -> `state.mjs escalate` and stop.
     - For factual P0/P1 bugs or security/contract violations: write a failing reproducing test first (Red), fix (Green, via `ship-implementer` or Micro-Fix Policy if single-file `< 50` lines), verify serially via `ship-verifier`, reply concisely on the PR, push, and re-run `wait-pr-gates.mjs`.
     - For architectural/contract disagreements with Codex: do NOT argue; run `state.mjs escalate` and return the disagreement to the human.
   - **`nextAction === "escalate_human"`** (Codex timeout, still-running at cap, not configured, or ChatGPT Plus quota limit):
     - **DO NOT auto-merge.** Run `state.mjs escalate "<escalateReason>"` and return to the human with the PR link and CI status (resume later via `state.mjs resume`).
   - **`nextAction === "auto_merge"`** (`readyToAutoMerge === true`):
     - Transition via `node .agents/skills/ship/scripts/state.mjs transition S6_AUTO_MERGE_AND_DEPLOY --ci-passed --codex-passed`.

---

### `S6_AUTO_MERGE_AND_DEPLOY` — Autonomous Merge & Contract-Approved Delivery
1. Merge the green, oracle-verified, Codex-verified PR: `gh pr merge <pr-number> --squash --delete-branch`.
2. Verify `main` branch `CI` workflow run starts cleanly via `gh run list --branch main --limit 3`.
3. If and ONLY if the S2-approved contract specified `deployTarget === "release-tag"`:
   - Confirm version bump is merged on `main`, create and push annotated tag `v<version>`, and verify release artifacts publish cleanly.
4. Transition to `S7_POST_DEPLOY_VERIFY`.

---

### `S7_POST_DEPLOY_VERIFY` — Post-Merge / Release Artifact Verification
1. Poll the `main` (or `v*` tag) `CI` workflow completion via `gh run view` and confirm build, Valhalla JDK 28 integration test, and container build jobs pass cleanly.
2. If the post-merge/release build fails, open a high-priority GitHub Issue with compressed LogSage triage and escalate to the human.
3. Transition to `S8_HARNESS_LEARN`.

---

### `S8_HARNESS_LEARN` — "2 Strikes -> Guard" & Final Report
1. Review `findingsHistory` via `state.mjs`. For any defect pattern occurring **2+ times**, propose adding a deterministic guard in `scripts/guards/run-all-guards.mjs` or a rule in `.agents/rules/` / `AGENTS.md`.
2. Transition to `DONE`: `node .agents/skills/ship/scripts/state.mjs transition DONE`.
3. Output a concise delivery report (PR URL, merge commit / Release URL, Clean-Room Oracle evidence, Acceptance checklist status, CI/Valhalla verification status, and any 2-strikes guard proposals).
