# Feature Contract: NGJ Phase 2–4 Type System, Protocols, Formal Proofs & AI Tooling

- **Slug**: `phase2-3-4-type-system-protocol-proofs`
- **PR / Branch**: PR #18 (`feat/ai-native-type-system-and-formal-spec`)
- **Delivery Target**: `merge-only` (commit & push directly to PR #18, squash-merge when green)
- **Protected Path Unlocks (`allowedProtectedPaths`)**: None (`[]`)
- **Normative References**:
  - `docs/SPECIFICATION.md` (Phase 2–4)
  - `docs/EBNF.md` (§7)
  - `docs/AGENT_TOOLING.md` (§1–6)
  - `docs/VALIDATION.md` (`CAP-02`, `SEP-01`, `REF-01`, `DIAG-01`, `GATE-01`, `PROTO-01`, `BUDGET-01`, `IFC-03`, `PROOF-01`, `CERT-01`)

---

## 1. Goals & Non-Goals

### Goals (Phase 2 + Phase 3 + Phase 4)
1. **Phase 2 (`CAP-02`, `SEP-01`, `REF-01`, `DIAG-01`, `GATE-01`)**:
   - Scala 3-style capability tracking (`caps(...)`, `read`/`update`, flow-sensitive `consume` revocation `NGJ-CAP-002`, Safe Mode `NGJ-SAFE-001`, `@trusted_pure` allowlist `NGJ-CAP-004`, `Secret.map`/`List.map` local purity `NGJ-CAP-003`, effect subtyping/exceedance `NGJ-EFFECT-001`).
   - Structured Concurrency separation (`scope`/`fork`, `NGJ-SEP-001`, `NGJ-SEP-002`, `Arena.close(consume arena)` live-view/unjoined-task checks).
   - First-class refinement & range types (`{v: T where expr}`, `T where expr`, `Int[lo..hi]`, `Int[lo..<hi]`, `Int[lo..]`, `Long[lo..hi]`, `type` aliases `NGJ-TYPE-026`, dependent parameter bounds, pure predicate `NGJ-REF-002`), Tier 1 deterministic solver (`val` selfification, E-graph, `BigInteger` QF-LIA intervals, `if`/`else`/`match` narrowing, `ranger`-style `while` monotonicity), `refine::<T>(expr)` (`NGJ-REF-003`), JEP 513 Valhalla record/variant `<init>` invariant checks.
   - `ngj.diagnostics/2`, `ngj.context/2` (`capability_mode`, `capture_lifetime`), `--diagnostic-detail=human|agent`, UTF-8 byte spans, `Secret(****)` masking, `requires_review` hints, `--max-bytes`, `ngjc obligations`, `ngjc check-patch --policy=strict-gates` (5 gates), `--profile=restricted`.
2. **Phase 3 (`PROTO-01`, `BUDGET-01`, `IFC-03`)**:
   - Finite-state `protocol` declarations (`initial`, `states`, `final`, `transition`, `@must_resolve`, `refines`), reachability/non-deadend checks (`NGJ-PROTO-001`), instance-bound `consume` transitions & exit resolution (`NGJ-PROTO-002`, `NGJ-PROTO-004`), `refines` trace-inclusion checking (`NGJ-PROTO-003`), Mermaid `stateDiagram-v2` export (`ngjc inspect --protocol=<id> --format=mermaid`).
   - Linear `Budget<Kind>` (`Budget.split`, conservation & bounds `NGJ-BUDGET-001`, no unauthorized minting `NGJ-BUDGET-002`).
   - Selective PC-Guard (`Untrusted<T>` branching restrictions against `privileged_effects` -> `NGJ-IFC-PC-001`, `Validated<T>`), `@boundary_schema` (pure-JVM JSON/binary decode + runtime refinement validation + `Secret(****)`-masked serialization, `NGJ-SCHEMA-001`).
3. **Phase 4 (`PROOF-01`, `CERT-01`)**:
   - `spec fn`, `spec predicate`, `requires`, `ensures`, `invariant`, `proof { ... }`, `lemma`, `?proof_hole` (`NGJ-PROOF-001`, `CheckStatus.INCOMPLETE_PROOF`, rejected in `--profile=proof-required` and `build` with exit `2`), spec/ghost isolation (`NGJ-PROOF-002`), complete JVM `.class` erasure of proof/spec constructs, `ngjc context --proof-hole=<id>`.
   - Tier 2 `--verify=smt` (`--smt-backend=jvm|z3|auto`): pure-JVM DPLL(T)/QF-LIA Simplex+Branch-and-Bound + optional `z3`, explicitly distinguishing `proved`, `counterexample`, `unproven`, `timeout`, `unsupported` (`NGJ-VERIFY-001`).
   - `ngjc certify-spec --format=json --symbol=<id> --revision=<hash>` (`ngj.certify/2`): Surface A (`proved`/`runtime_checked`/`unproven`), Surface B (`NGJ-CERT-VACUOUS-PRE`, `NGJ-CERT-WEAK-ENSURES`, `NGJ-CERT-ASSUME-PRESENT`), Surface C (`Post2Exe` negative-test rejection + 2-call uniqueness / deterministic mutation+PBT strength witness).

### Non-Goals
- Phase 5+ features, custom LLM training/agent frameworks, external network/LLM calls in `compiler/src/main/java/`, or modifying protected CI/harness files.
- Note: Existing `CliTest.versionCommand()` (`0.1.0-mvp` -> `0.3.0-valhalla`) and `TypeCheckerTest.rejectNonEmptyEffectBound()` (effect subtyping `AC-3`) will be updated in Wave 2d to match the v0.3 / Phase 2–4 spec, and `docs/MVP_COVERAGE.md` will be updated to reflect Phase 2–4 implementation status.

---

## 2. Public Contracts & Diagnostic IDs

### Diagnostic IDs
- Phase 2: `NGJ-EFFECT-001`, `NGJ-CAP-001`, `NGJ-CAP-002`, `NGJ-CAP-003`, `NGJ-CAP-004`, `NGJ-SEP-001`, `NGJ-SEP-002`, `NGJ-SAFE-001`, `NGJ-REF-001`, `NGJ-REF-002`, `NGJ-REF-003`, `NGJ-TYPE-026`, `NGJ-GATE-SPEC-DRIFT`, `NGJ-GATE-AXIOM-DRIFT`, `NGJ-GATE-FORBIDDEN`, `NGJ-GATE-SIBLING`, `NGJ-GATE-OBLIGATION`.
- Phase 3: `NGJ-PROTO-001`, `NGJ-PROTO-002`, `NGJ-PROTO-003`, `NGJ-PROTO-004`, `NGJ-BUDGET-001`, `NGJ-BUDGET-002`, `NGJ-IFC-PC-001`, `NGJ-SCHEMA-001`.
- Phase 4: `NGJ-PROOF-001`, `NGJ-PROOF-002`, `NGJ-VERIFY-001`, `NGJ-CERT-VACUOUS-PRE`, `NGJ-CERT-WEAK-ENSURES`, `NGJ-CERT-ASSUME-PRESENT`.

### Exit Codes & CLI Schemas
- `0`: Valid / operation succeeded / certified (`certify-spec`).
- `1`: Invalid / definite error / `strict-gates` rejection / certification failed.
- `2`: Incomplete (`?name` code holes or `?proof_hole` proof holes present without definite errors; rejected in `build` and `--profile=proof-required`).
- `3`: Revision mismatch / bad CLI option / invalid patch / missing hole or symbol / `TARGET-02`.
- `4`: Internal compiler error.
- Common JSON envelope: `schema_version`, `compiler_version` (`"0.3.0-valhalla"`), `source_revision`, `policy_digest`, `target_profile`.

---

## 3. Compiler Layer & Ownership Plan
- **Wave 1 (3 Parallel Workers)**: `W1-ParserAST` (`complex`), `W1-DiagRuntime` (`standard`), `W1-SolverProtoPatchCore` (`complex`).
- **Wave 2 (Sequential Integration)**: `W2a-TypeSystemAndCheckers` (`complex`) -> `W2b-CodegenAndPost2Exe` (`complex`) -> `W2c-GatesAndCli` (`complex`) -> `W2d-TestsFixturesDocs` (`standard`).

---

## 4. Rollback Plan
All changes are isolated to `feat/ai-native-type-system-and-formal-spec` (PR #18) and can be reverted cleanly without affecting `main` or protected CI/harness files.
