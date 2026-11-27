---
trigger: model_decision
description: "Apply whenever touching NGJ specifications, EBNF grammar, conformance fixtures, Dockerfile, or CI workflows."
---

# NGJ Specifications, Fixtures & CI Truthfulness Rules

1. **Normative Specification Consistency**:
   - Keep `docs/EBNF.md`, `docs/SPECIFICATION.md`, `docs/AGENT_TOOLING.md`, `docs/VALIDATION.md`, `docs/STDLIB.md`, and `examples/` aligned on v0.3 canonical syntax (`package`, `public`, `record`, `val`/`var`, mandatory `return`, `effects()`, mandatory semicolons).
   - Add independent positive and negative fixtures under `compiler/src/test/resources/fixtures/`; every negative fixture must assert a specific `NGJ-*` diagnostic ID.
2. **Valhalla JDK 28 & Container Truthfulness Invariant**:
   - Local JDK 25 host unit tests run with `NGJ_SKIP_JDK_CHECK=true` and `NGJ_RUNTIME_CLASS_VERSION=69`; they do **NOT** prove JDK 28 EA (`--enable-preview`) Valhalla runtime execution, Docker runtime image behavior, or Leyden AOT/Native Image compatibility.
   - Always record unexecuted JDK 28 EA Valhalla runtime, Docker, or JDK 25 Gradle checks as `NOT RUN` (never fabricate or imply pass status).
3. **Pinned Container & Supply-Chain Integrity**:
   - Keep `Dockerfile` pinned to the documented Valhalla JDK build with `--enable-preview` entrypoint and non-root `USER ngj`.
   - Keep `gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/**`, `scripts/guards/**`, and `.agents/` harness controls protected behind explicit S2 human approval.
