# NGJ (Next-Gen JVM Language)

**NGJ (`ngj`)** is a statically typed, expression-oriented JVM language designed for AI agents to generate and repair human-reviewable applications. It combines modern JVM foundations with a compiler that exposes types, effects, ownership constraints and actionable diagnostics during generation.

**Status: v0.3 specification implemented.** The compiler, tests and fixtures use v0.3 canonical syntax (`package`/`public`/`record`/`val`/`var`/`return`, `effects()`, mandatory `return` in functions, expression-block tail expressions, mandatory semicolons). The syntax migration is documented in [SYNTAX_MIGRATION.md](docs/SYNTAX_MIGRATION.md).

## Design principles

- **Value-first (Valhalla foundation):** Project Valhalla value classes are the foundation of the type system, not an optional optimization backend. All user-defined types are value types by default (identity-free, immutable, structural equality). Identity types exist only where explicitly needed (resources, actors, mutable state). No primitive/boxed split visible to users; `Int`/`Long`/`Bool` are value types. No `null` in the core language; absence is `Option<T>`. Generics over value types are a language guarantee.
- **Structured concurrency:** Direct-style `scope` / `fork`, bounded channels, explicit cancellation and resource lifetimes, without `async/await` coloring.
- **Checkable authority (Tracked Capabilities & Separation):** Scala 3-style capture checking, `read`/`update` separation, explicit `consume` revocation, and finite-state `protocol` types constrain operations and local purity without Rust-style lifetime syntax. Java interoperability is a declared trust boundary.
- **First-class value refinements:** Value refinement types (`{v: T where expr}`, `Int[lo..hi]`) are part of the Phase 2 core type syntax with partial-correctness semantics and deterministic E-graph/QF-LIA checking.
- **Compiler support for agents:** Typed code/proof holes (`?name`, `?proof_hole`), partial checking, two-tier `human`/`agent` structured diagnostics, type-directed context retrieval and trust-first `check-patch` gates.
- **Verification and observability:** Surface A/B/C verification reporting, `Post2Exe` & specification strength certification, Secret flow & Selective PC-Guard checks, static OTel/JFR instrumentation and scoped I/O replay.
- **Static interop and AOT:** Direct class-file output; dependency-locked Java adapters and separately validated Native Image profiles.

## Specification

- [Canonical v0.3 grammar](docs/EBNF.md): one spelling per construct and explicit evaluation boundaries.
- [Syntax migration](docs/SYNTAX_MIGRATION.md): scope, compatibility and acceptance cases.

- [Language specification](docs/SPECIFICATION.md): semantics, safety boundaries, JVM mapping and roadmap.
- [Agent tooling protocol](docs/AGENT_TOOLING.md): proposed CLI, JSON diagnostics and context API.
- [AOT design direction](docs/AOT.md): post-MVP strategy for Leyden AOT and native-image support.
- [Validation plan](docs/VALIDATION.md): conformance cases, performance checks and controlled AI-generation experiments.
- [Research basis](docs/RESEARCH.md): primary sources and the limits of transferring their findings to NGJ.
- [Examples and adapter contracts](examples/README.md): illustrative REST, payment, binary parser, CLI and typed-hole examples.

The compiler is intended to remain deterministic and usable without an LLM. Agent frameworks, model calls and token-level constrained decoding are optional clients of its APIs.
