# NGJ (Next-Gen JVM Language)

**NGJ (`ngj`)** is a statically typed, expression-oriented JVM language designed for AI agents to generate and repair human-reviewable applications. It combines modern JVM foundations with a compiler that exposes types, effects, ownership constraints and actionable diagnostics during generation.

**Status: specification draft v0.2.** This repository currently contains design documents and illustrative source examples. There is no compiler, implemented standard library, or executable conformance suite yet. Features below are design goals, not measured or delivered guarantees.

## Design principles

- **Value-first data:** Immutable data and closed ADTs, with a Java 25 baseline and an optional pinned Valhalla backend. Allocation elimination and flattening are optimization opportunities, not unconditional promises.
- **Structured concurrency:** Direct-style `scope` / `fork`, bounded channels, explicit cancellation and resource lifetimes, without `async/await` coloring.
- **Checkable authority:** Effects, host-issued capabilities, ownership and typestate constrain operations. Java interoperability is a declared trust boundary.
- **Compiler support for agents:** Typed holes, partial checking, structured diagnostics, type-directed context retrieval and revision-aware patch validation.
- **Verification and observability:** Contracts, counterexamples, Secret flow checks, static OTel/JFR instrumentation and scoped I/O replay.
- **Static interop and AOT:** Direct class-file output; dependency-locked Java adapters and separately validated Native Image profiles.

## Specification

- [Language specification](docs/SPECIFICATION.md): semantics, safety boundaries, JVM mapping and roadmap.
- [Agent tooling protocol](docs/AGENT_TOOLING.md): proposed CLI, JSON diagnostics and context API.
- [Validation plan](docs/VALIDATION.md): conformance cases, performance checks and controlled AI-generation experiments.
- [Research basis](docs/RESEARCH.md): primary sources and the limits of transferring their findings to NGJ.
- [Examples and adapter contracts](examples/README.md): illustrative REST, payment, binary parser, CLI and typed-hole examples.

The compiler is intended to remain deterministic and usable without an LLM. Agent frameworks, model calls and token-level constrained decoding are optional clients of its APIs.
