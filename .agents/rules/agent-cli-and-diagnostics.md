---
trigger: glob
globs: "compiler/src/main/java/ngj/cli/**/*.java, compiler/src/main/java/ngj/diagnostic/**/*.java, compiler/src/main/java/ngj/Compiler.java"
description: "CLI wire contract, structured diagnostics, typed holes, and authority boundaries for NGJ agent tooling."
---

# NGJ Agent CLI & Diagnostics Rules

1. **CLI Exit Code Contract (`docs/AGENT_TOOLING.md` §2)**:
   - `0`: operation succeeded (`valid` for `check`)
   - `1`: definite compilation error (`invalid`; takes precedence over `incomplete`)
   - `2`: typed holes / unresolved obligations (`incomplete`, no definite errors; `build` also rejects incomplete code with exit `2`)
   - `3`: revision mismatch or bad input/schema (including `TARGET-02` JDK check failure)
   - `4`: internal compiler failure (never treated as passing)
2. **Structured JSON & Stream Separation**:
   - Emit machine-readable JSON strictly to `stdout` and human progress/errors to `stderr`.
   - Include `schema_version`, `compiler_version`, `source_revision`, `policy_digest`, and `target_profile` metadata on protocol responses, and enforce `--revision` / `--max-bytes` deterministically.
   - Report source spans using half-open UTF-8 byte offsets and stable diagnostic IDs (`NGJ-*`).
3. **Typed Holes & Certainty Separation**:
   - Never treat programs containing `?name` holes as `valid` or assume unresolved hole effects are pure.
   - Distinguish definite contradictions (`certainty: "definite"`) from unresolved constraints.
4. **Authority & Secret Redaction**:
   - Never leak stack traces, environment variables, or `Secret` values in normal diagnostics or counterexamples.
   - Never emit automatic fixes that expand effects, weaken contracts, strip `Secret` labels, or add unsafe operations; any authority-sensitive hint must be marked `requires_review`.
