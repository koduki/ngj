# AGENTS.md — NGJ Repository Map & Invariants

> Single entry map shared by **Google Antigravity (`agy`)** and **OpenAI Codex**. Keep this file concise (<= 90 lines); detailed specs and rules live under `docs/` and `.agents/rules/`.

## 1. Repository Map (Progressive Disclosure)
- Product overview & design principles: `README.md`
- Normative language specification & v0.3 grammar: `docs/SPECIFICATION.md`, `docs/EBNF.md`, `docs/SYNTAX_MIGRATION.md`, `docs/STDLIB.md`
- Agent CLI & structured diagnostic protocol: `docs/AGENT_TOOLING.md`
- Conformance matrix & validation plan: `docs/VALIDATION.md`, `docs/MVP_COVERAGE.md`
- Compiler implementation & tests: `compiler/src/main/java/ngj/` (`parser`, `ast`, `typechecker`, `ir`, `codegen`, `cli`, `diagnostic`), `compiler/src/test/java/ngj/`, `compiler/src/test/resources/fixtures/`
- End-to-end autonomous workflow (`/ship`): `.agents/skills/ship/SKILL.md` (configured by `.agents/ship.config.json`; resume escalated runs via `state.mjs resume`)
- Tiered custom subagents (`.agents/agents/*.md`, validated by `custom-subagent-frontmatter` guard): `ship-recon` (`flash`), `ship-architect` (`pro`), `oracle-test-author` (`pro`), `ship-implementer` (`standard`: `flash` / `complex`: `inherit`), `ship-verifier` (`flash`), `ship-reviewer` (`pro`)
- Non-ignorable deterministic guards & staged boundary lock: `scripts/guards/run-all-guards.mjs`, `.agents/skills/ship/scripts/check-staged-boundaries.mjs` (wired in `.githooks/pre-commit`)

## 2. Hard Boundaries (AI Must Never Modify Without Explicit Human Approval in S2)
- Secret/signing files (NEVER unlockable): `.env*`, `*.keystore`, `*.jks`, `*.p12`
- CI/CD & harness controls: `.github/workflows/**`, `scripts/guards/**`, `.agents/hooks.json`, `.agents/ship.config.json`, `.agents/agents/**`
- Toolchain, container & legal supply chain: `gradle/wrapper/gradle-wrapper.properties`, `Dockerfile`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `LICENSES/**`
- Approved specifications & feature contracts (locked automatically after `/ship` S2 approval): `docs/SPECIFICATION.md`, `docs/EBNF.md`, `docs/AGENT_TOOLING.md`, `docs/VALIDATION.md`, `docs/adr/**`, `docs/specs/**/spec.md`
- Clean-room spec-derived oracle tests (authored in separate agent process during `S3a_ORACLE_TEST_GEN`, hard-locked during implementation): `tests/oracle/**`, `compiler/src/test/java/ngj/oracle/**`, `*.oracle.test.cjs`, `*OracleTest.java`, `*_oracle.py`
- Never delete, `@Disabled`, `@Ignore`, `.skip`, or weaken existing tests, and never add suppression comments/annotations (`eslint-disable`, `@ts-ignore`, `# noqa`, new `@SuppressWarnings`) to pass checks.

## 3. Fast Local Verification Commands
1. Non-ignorable guards: `node scripts/guards/run-all-guards.mjs`
2. Clean-room oracle suites: `node scripts/guards/run-oracle-tests.mjs`
3. Harness & script syntax checks: `bash -n scripts/*.sh .githooks/pre-commit && node --test tests/*.test.cjs`
4. Compiler JVM build & tests (when JDK 25 toolchain is present): `NGJ_BUILD_JDK_VERSION=25 ./gradlew test`

## 4. Truthfulness Invariant
- Host JDK 25 unit tests (`NGJ_SKIP_JDK_CHECK=true`, `NGJ_RUNTIME_CLASS_VERSION=69`) do **NOT** prove JDK 28 EA Valhalla `--enable-preview` runtime execution, Docker runtime image behavior, or Leyden AOT / Native Image support. Always record unexecuted JDK 28 EA / container / JDK 25 Gradle checks as `NOT RUN`.

## Code Review Rules

### Valhalla Semantics & Bytecode Fidelity
- Flag any value type (`record`, `data`, `enum` or variant) emitted with `ACC_IDENTITY` (`0x0020`), missing preview minor version where required, or breaking value equality / sealed `PermittedSubclasses`.
- Flag any acceptance of `null` literals in NGJ core (`NGJ-NULL-001`), omitted explicit `return` in non-`Unit` functions (`NGJ-RETURN-001`), or silent fallback on non-Valhalla runtimes (`TARGET-02`).

### Type/Effect Soundness, CLI Protocol & Harness Security
- Flag any change treating typed holes (`?name`) as `valid` or pure, allowing release builds with holes, or relaxing effect/ownership/contract bounds to make compilation pass.
- Flag CLI contract regressions (exit codes 0–4, stdout JSON vs stderr progress, UTF-8 byte spans, `source_revision`/`policy_digest` checks, or leaking stack traces/env vars/secrets in normal diagnostics) and any LLM/network dependency introduced into `compiler/src/main/java/`.
- Flag any weakening of `/ship` boundary locks (`neverUnlockablePatterns`, locked oracle/contract checks) or `wait-pr-gates.mjs` fail-closed handling of Codex `running`/`not_configured`/`quota_limited` states.

### Scope & Signal Hygiene
- Safe path: do not report stylistic nits or checks already enforced deterministically by `node scripts/guards/run-all-guards.mjs`.
- Focus strictly on P0/P1 defects: soundness/contract violations, Valhalla bytecode bugs, security/redaction regressions, determinism bugs, and data-loss risks.
