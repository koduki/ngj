# NGJ (Next-Gen JVM Language)

**NGJ (`ngj`)** is a statically typed, expression-oriented JVM language designed for AI agents to generate and repair human-reviewable applications. It combines modern JVM foundations with a compiler that exposes types, effects, ownership constraints and actionable diagnostics during generation.

**Status: v0.3 pure core MVP implemented.** The compiler parses, type-checks, and generates JVM bytecode (`--target=valhalla` default for JDK 28 EA with `--enable-preview` JEP 401 value classes; `--target=valhalla-compat` / `java25` fallback for JDK 25+ without preview) for the v0.3 sequential core (`fn`/`record`/`enum`/`Option`/`Result`/`List`, `for`/`while`, typed holes, JSON diagnostics). Ownership/borrowing, named `effects(...)`, concurrency (`scope`/`fork`), contracts, `Byte`/`Float`/`Double` codegen, lambdas, and Leyden/Native AOT remain staged (see [MVP_COVERAGE.md](docs/MVP_COVERAGE.md)). Syntax migration details are in [SYNTAX_MIGRATION.md](docs/SYNTAX_MIGRATION.md).

## Design principles

- **Value-first (Valhalla foundation):** Project Valhalla value classes are the foundation of the type system, not an optional optimization backend. All user-defined types are value types by default (identity-free, immutable, structural equality). Identity types exist only where explicitly needed (resources, actors, mutable state). No primitive/boxed split visible to users; `Int`/`Long`/`Bool` are value types. No `null` in the core language; absence is `Option<T>`. Generics over value types are a language guarantee.
- **Structured concurrency:** Direct-style `scope` / `fork`, bounded channels, explicit cancellation and resource lifetimes, without `async/await` coloring.
- **Checkable authority:** Effects, host-issued capabilities, ownership and typestate constrain operations. Java interoperability is a declared trust boundary.
- **Compiler support for agents:** Typed holes, partial checking, structured diagnostics, type-directed context retrieval and revision-aware patch validation.
- **Verification and observability:** Contracts, counterexamples, Secret flow checks, static OTel/JFR instrumentation and scoped I/O replay.
- **Static interop and AOT:** Direct class-file output; dependency-locked Java adapters and separately validated Native Image profiles.

## Specification

- [Canonical v0.3 grammar](docs/EBNF.md): one spelling per construct and explicit evaluation boundaries.
- [Syntax migration](docs/SYNTAX_MIGRATION.md): scope, compatibility and acceptance cases.
- [Language specification](docs/SPECIFICATION.md): semantics, safety boundaries, JVM mapping and roadmap.
- [Standard library](docs/STDLIB.md): core types (`Int`, `Long`, `Bool`, `String`, `Option`, `Result`, `List`) and their operations.
- [MVP coverage matrix](docs/MVP_COVERAGE.md): feature-by-feature implementation status.
- [Agent tooling protocol](docs/AGENT_TOOLING.md): proposed CLI, JSON diagnostics and context API.
- [AOT design direction](docs/AOT.md): post-MVP strategy for Leyden AOT and native-image support.
- [Container guide](docs/CONTAINER.md): building and running NGJ in Docker containers.
- [Validation plan](docs/VALIDATION.md): conformance cases, performance checks and controlled AI-generation experiments.
- [Research basis](docs/RESEARCH.md): primary sources and the limits of transferring their findings to NGJ.
- [Examples and adapter contracts](examples/README.md): legacy v0.2 design sketches, canonical `syntax_v03.ngj` (with hole), and pointers to complete buildable fixtures under `compiler/src/test/resources/fixtures/`.

The compiler is intended to remain deterministic and usable without an LLM. Agent frameworks, model calls and token-level constrained decoding are optional clients of its APIs.
