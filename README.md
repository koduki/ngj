# NGJ (Next-Gen JVM Language)

**NGJ (`ngj`)** is a statically-typed, expression-oriented modern systems programming language designed natively around OpenJDK's next-generation foundations (**Project Valhalla**, **Project Loom**, **Project Panama**, and **Project Leyden**).

## Key Features
- **Zero-Cost Data & Memory Layout (Valhalla First):** Immutable data (`data` / `enum`) compiles to identity-less Valhalla Value Classes by default.
- **Colorless Structured Concurrency (Loom First):** Virtual threads and structured concurrency (`scope` / `fork`) as first-class syntax without `async/await` function coloring.
- **Observability as a Language Feature (OTel & JFR Native):** Automatic OpenTelemetry context propagation via `ScopedValue`, compile-time static span instrumentation, PII protection (`Secret<T>`), and native JFR event definitions.
- **Zero-Reflection & Strict AOT Interop:** Direct JVM bytecode generation via Java's `java.lang.classfile` API (`ngjc`), static bytecode inspection of Java dependencies, and out-of-the-box GraalVM Native Image single-binary builds.

## Repository Structure
- `docs/SPECIFICATION.md`: Full architecture and language specification draft (v0.1)
- `examples/rest_api.ngj`: REST API server with JDBC database access, structured concurrency, and OTel/JFR integration
- `examples/payment_service.ngj`: High-throughput payment service with `Secret<T>` PII protection and auto-cancellation
- `examples/zero_copy_protocol.ngj`: Zero-copy binary protocol parser using `MemorySegment` (`Bytes`) and `build` freeze blocks
- `examples/cli_tool.ngj`: Single-binary CLI tool built with `@derive(CliArgs, JsonCodec)` for GraalVM Native Image
