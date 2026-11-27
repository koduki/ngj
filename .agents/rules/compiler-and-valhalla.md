---
trigger: glob
globs: "compiler/src/main/java/**/*.java, build.gradle.kts, compiler/build.gradle.kts"
description: "Valhalla foundation, syntax v0.3 semantics, bytecode generation, and build invariants for the NGJ compiler."
---

# NGJ Compiler & Valhalla Foundation Rules

1. **Value-First Semantics (JEP 401 / 539)**:
   - Emit user-defined value types (`record`, `data`, `enum` and variants) as Valhalla value classes (omit `ACC_IDENTITY` `0x0020`, emit preview minor version `65535` where required, and use `PermittedSubclasses` for sealed `enum` hierarchies).
   - Never admit `null` literals in NGJ core (`NGJ-NULL-001`); absence must be modeled via `Option<T>`.
   - Enforce value equality (`==` compares field values) and exhaustive `match` with missing variant names in diagnostics.
2. **Canonical v0.3 Syntax Invariants (`docs/EBNF.md`)**:
   - Enforce mandatory explicit `return <expr>;` in non-`Unit` functions (`NGJ-RETURN-001`), while allowing tail expressions in expression blocks (`if`/`match`/`for-yield`).
   - Require explicit `effects(...)` bounds and parentheses on enum variants/constructors (`None()`, `Some(x)`, `Ok(x)`, `Err(e)`).
3. **Class-File API & Build Toolchain**:
   - Generate JVM bytecode directly via `java.lang.classfile` (JEP 484) with zero external bytecode libraries.
   - Keep `--enable-preview` enabled across Gradle compile/test/exec tasks and Docker runtime entrypoint; keep `NGJ_BUILD_JDK_VERSION` default `25` and `VALHALLA_JDK_VERSION = 28`.
   - Maintain `TARGET-02`: running `ngjc` on a non-Valhalla JDK must fail explicitly rather than silently falling back to identity classes.
4. **Deterministic Compiler Core**:
   - Never add LLM SDKs, network calls, or non-deterministic runtime dependencies to `compiler/src/main/java/`. The compiler must stay deterministic and usable without an LLM.
5. **Test & Suppression Discipline**:
   - Never add `@Disabled`, `@Ignore`, or new `@SuppressWarnings(...)` annotations to hide compiler warnings or test failures.
