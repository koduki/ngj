# NGJ (Next-Gen JVM Language)

**NGJ (`ngj`)** is a statically typed, expression-oriented JVM language designed for AI agents to generate and repair human-reviewable applications. It combines modern JVM foundations with a compiler that exposes types, effects, ownership constraints and actionable diagnostics during generation.

**Status: v0.3 specification target, with an initial v0.2 compiler implementation.** Compiler source and tests are present. The canonical syntax migration is tracked in [SYNTAX_MIGRATION.md](docs/SYNTAX_MIGRATION.md); v0.3 examples require that migration before compilation.

## Design principles

- **Value-first data:** Immutable data and closed ADTs, with a Java 25 baseline using the standard Class-File API for bytecode generation, and an optional pinned Valhalla backend. Allocation elimination and flattening are optimization opportunities, not unconditional promises.
- **Structured concurrency:** Direct-style `scope` / `fork`, bounded channels, explicit cancellation and resource lifetimes, without `async/await` coloring.
- **Checkable authority:** Effects, host-issued capabilities, ownership and typestate constrain operations. Java interoperability is a declared trust boundary.
- **Compiler support for agents:** Typed holes, partial checking, structured diagnostics, type-directed context retrieval and revision-aware patch validation.
- **Verification and observability:** Contracts, counterexamples, Secret flow checks, static OTel/JFR instrumentation and scoped I/O replay.
- **Static interop and AOT:** Direct class-file output; dependency-locked Java adapters and separately validated Native Image profiles.

## Specification

- [Canonical v0.3 grammar](docs/EBNF.md): one spelling per construct and explicit evaluation boundaries.
- [Syntax migration](docs/SYNTAX_MIGRATION.md): scope, compatibility and acceptance cases.

- [Language specification](docs/SPECIFICATION.md): semantics, safety boundaries, JVM mapping and roadmap.
- [Agent tooling protocol](docs/AGENT_TOOLING.md): proposed CLI, JSON diagnostics and context API.
- [Validation plan](docs/VALIDATION.md): conformance cases, performance checks and controlled AI-generation experiments.
- [Research basis](docs/RESEARCH.md): primary sources and the limits of transferring their findings to NGJ.
- [Examples and adapter contracts](examples/README.md): illustrative REST, payment, binary parser, CLI and typed-hole examples.

The compiler is intended to remain deterministic and usable without an LLM. Agent frameworks, model calls and token-level constrained decoding are optional clients of its APIs.
